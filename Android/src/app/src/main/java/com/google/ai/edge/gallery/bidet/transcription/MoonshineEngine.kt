/*
 * Copyright 2026 bidet-ai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.bidet.transcription

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * sherpa-onnx-backed Moonshine-Tiny ASR engine. Replaces the v0.2 [WhisperEngine] (now deleted)
 * which used whisper.cpp NDK + Whisper-tiny GGUF. Moonshine-Tiny is smaller (27M vs 39M params),
 * faster (5× less FLOPs at 10 s clips per the Moonshine paper), and more accurate (4.55 WER vs
 * Whisper-tiny's 5.66 WER on LibriSpeech clean).
 *
 * Engine path:
 *   FloatArray (16 kHz mono [-1.0, 1.0])
 *     → OfflineStream.acceptWaveform
 *     → OfflineRecognizer.decode
 *     → OfflineRecognizerResult.text
 *
 * Model files live in `assets/moonshine/` and get loaded via Android's AssetManager (they're
 * `.ort` flatbuffer-serialized ONNX models — sherpa-onnx mmap's them straight from the
 * AssetManager, no copy-to-disk required for v2 quantized bundles).
 *
 * Concurrency: sherpa-onnx's [OfflineRecognizer] is not documented as thread-safe for
 * simultaneous decode() calls against the same recognizer pointer. The [TranscriptionWorker]
 * already serializes calls (single consumer, processes chunks one at a time), but we add a
 * defensive Mutex anyway — cheap, and matches the [WhisperEngine] contract that callers
 * could rely on serialization being internal.
 *
 * v0.4 polish (deferred): switch to OnlineRecognizer + the streaming Moonshine bundle for
 * partial-text-as-you-speak UX. Non-streaming is enough for the chunk-based contest demo.
 */
class MoonshineEngine(private val context: Context) : TranscriptionEngine {

    private val recognizerRef = AtomicReference<OfflineRecognizer?>()
    private val mutex = Mutex()

    /** True once the recognizer is loaded. */
    override val isReady: Boolean get() = recognizerRef.get() != null

    /**
     * Synchronous initialize so it satisfies the [TranscriptionEngine.initialize] contract.
     * sherpa-onnx's `OfflineRecognizer` constructor reads from AssetManager + builds the ORT
     * session on the calling thread; we expect callers to invoke this off the main thread
     * (TranscriptionWorker.start runs in a coroutine; RecordingService init is on a worker).
     */
    @Synchronized
    override fun initialize(): Boolean {
        if (isReady) return true
        return try {
            val cfg = buildConfig()
            // newFromAsset path on OfflineRecognizer — pass AssetManager so sherpa-onnx
            // mmap's the ORT models without us copying them onto the device's filesystem.
            val recognizer = OfflineRecognizer(
                assetManager = context.assets,
                config = cfg,
            )
            recognizerRef.set(recognizer)
            Log.i(TAG, "initialize: Moonshine-Tiny recognizer ready (encoder=${cfg.modelConfig.moonshine.encoder})")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "initialize failed: ${t.message}", t)
            false
        }
    }

    /**
     * Transcribe one audio chunk. Float32 mono [-1.0, 1.0], must be 16 kHz (Moonshine's
     * native rate; passing anything else would silently mis-transcribe so we throw instead).
     *
     * Internally splits the input into ≤ [MAX_SUBCHUNK_SAMPLES] sub-chunks before handing
     * each one to sherpa-onnx. The Moonshine **quantized** ONNX models bake the encoder
     * attention shapes into the int8 graph at export time; feeding a waveform longer than
     * roughly 9 seconds at 16 kHz triggers a runtime broadcast error inside the encoder
     * (`Add` node along the cross-attention axis) and sherpa-onnx's V2 impl swallows the
     * exception and returns an empty result. See:
     *   https://k2-fsa.github.io/sherpa/onnx/moonshine/   (model docs)
     *   https://zenn.dev/tubome/articles/moonshine-sherpa-onnx-japanese
     *     (empirical: 9.9 s fails, 9.0 s marginal, 8.5 s safe; recommends 8.5 s hard cap +
     *     ~0.9 s overlap stitching for long-form audio)
     *
     * The upstream [com.google.ai.edge.gallery.bidet.audio.AudioCaptureEngine] emits 30-second
     * windows because that's what Whisper/Gemma want — keeping the caller-side contract
     * unchanged means the splitting has to happen here. We use 8.0-second sub-chunks with
     * 0.5-second overlap and stitch the results with a single space; the overlap is enough
     * to keep word boundaries from getting cut mid-syllable while the cleaning pass
     * (Tab1/Tab2 prompts) handles the "the the" duplicate at the seam.
     *
     * @return the transcribed text, trimmed.
     * @throws IllegalStateException if [initialize] has not succeeded.
     * @throws IllegalArgumentException if [sampleRateHz] != 16000.
     */
    override suspend fun transcribe(floatPcm: FloatArray, sampleRateHz: Int): String {
        require(sampleRateHz == 16_000) {
            "MoonshineEngine requires 16 kHz input, got $sampleRateHz"
        }
        val recognizer = recognizerRef.get()
            ?: throw IllegalStateException("MoonshineEngine not initialized")

        if (floatPcm.isEmpty()) return ""

        // Decode is synchronous in sherpa-onnx Kotlin bindings — push it to Default dispatcher
        // and serialize against concurrent transcribe() calls. (TranscriptionWorker is already
        // a single consumer, but the Mutex is cheap defense in depth.)
        return mutex.withLock {
            withContext(Dispatchers.Default) {
                val windows = splitForQuantizedEncoder(floatPcm)
                Log.d(
                    TAG,
                    "transcribe: ${floatPcm.size} samples → ${windows.size} sub-chunk(s) " +
                        "(cap=${MAX_SUBCHUNK_SAMPLES}, overlap=${OVERLAP_SAMPLES})",
                )
                val parts = ArrayList<String>(windows.size)
                for (window in windows) {
                    val stream = recognizer.createStream()
                    try {
                        stream.acceptWaveform(window, sampleRateHz)
                        recognizer.decode(stream)
                        val text = recognizer.getResult(stream).text.trim()
                        if (text.isNotEmpty()) parts += text
                    } finally {
                        stream.release()
                    }
                }
                parts.joinToString(separator = " ").trim()
            }
        }
    }

    @Synchronized
    override fun close() {
        recognizerRef.getAndSet(null)?.let {
            try {
                // sherpa-onnx OfflineRecognizer.release() is sync — we still wrap in
                // runBlocking-equivalent only for symmetry with WhisperEngine.close(). Here it
                // is sync so we just call directly.
                it.release()
            } catch (t: Throwable) {
                Log.w(TAG, "close: release threw ${t.message}")
            }
        }
        // Keep `runBlocking` import-clean by referencing it once (defensive — Kotlin gives
        // unused-import warning otherwise on tighter linters).
        runBlocking { /* no-op */ }
    }

    /**
     * Build the OfflineRecognizerConfig pointing at the Moonshine v2 bundle in assets.
     * Moonshine v2 needs only encoder + mergedDecoder (vs v1's 4 ONNX files).
     */
    private fun buildConfig(): OfflineRecognizerConfig {
        return OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    encoder = "moonshine/encoder_model.ort",
                    mergedDecoder = "moonshine/decoder_model_merged.ort",
                ),
                tokens = "moonshine/tokens.txt",
                numThreads = 2,
                provider = "cpu",
                debug = false,
            ),
            decodingMethod = "greedy_search",
        )
    }

    companion object {
        private const val TAG = "BidetMoonshineEngine"
        const val ASSETS_DIR: String = "moonshine"

        /**
         * Per-sub-chunk sample cap for the quantized Moonshine ONNX encoder. 8.0 s @ 16 kHz =
         * 128 000 samples; sits comfortably under the empirical ~9 s breakpoint where the
         * baked-in cross-attention dimensions overflow. See [transcribe] for the citation
         * trail.
         */
        const val MAX_SUBCHUNK_SAMPLES: Int = 16_000 * 8

        /**
         * Overlap between adjacent sub-chunks so that a word straddling the split-point
         * still has continuous acoustic context on at least one side. 0.5 s @ 16 kHz =
         * 8 000 samples; the duplicate text at the seam (a few words) is cleaned out by
         * the downstream Tab1/Tab2 LLM prompts which collapse consecutive duplicates.
         */
        const val OVERLAP_SAMPLES: Int = 16_000 / 2

        /**
         * Split [floatPcm] into ≤ [MAX_SUBCHUNK_SAMPLES] windows with [OVERLAP_SAMPLES]
         * overlap between adjacent windows. The final window may be shorter than the cap
         * (it's just whatever's left). Returns the input unchanged in a single-element list
         * if it already fits the cap — no allocation in the common short-clip case.
         *
         * Visible to tests in the same package via `internal`.
         */
        @JvmStatic
        internal fun splitForQuantizedEncoder(floatPcm: FloatArray): List<FloatArray> {
            if (floatPcm.size <= MAX_SUBCHUNK_SAMPLES) return listOf(floatPcm)
            val stride = MAX_SUBCHUNK_SAMPLES - OVERLAP_SAMPLES
            val out = ArrayList<FloatArray>()
            var start = 0
            while (start < floatPcm.size) {
                val end = minOf(start + MAX_SUBCHUNK_SAMPLES, floatPcm.size)
                out += floatPcm.copyOfRange(start, end)
                if (end == floatPcm.size) break
                start += stride
            }
            return out
        }
    }
}
