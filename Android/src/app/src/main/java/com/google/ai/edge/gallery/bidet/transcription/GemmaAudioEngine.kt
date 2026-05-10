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
import com.google.ai.edge.gallery.bidet.llm.BidetSharedLiteRtEngineProvider
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Gemma 4 E4B's built-in audio encoder used directly via LiteRT-LM ≥ 0.10.1. No Whisper.
 *
 * Selected by the `gemma` Gradle product flavor (see [TranscriptionEngine.create]).
 *
 * Pipeline per chunk:
 *   1. AudioCaptureEngine writes 30-sec int16 PCM at 16 kHz to a chunk file.
 *   2. TranscriptionWorker calls `int16ToFloat32` then `transcribe(floatPcm, 16_000)`.
 *   3. We re-encode the Float32 samples to a 44-byte RIFF/WAVE header + int16 little-endian
 *      PCM body. **The WAV header is required:** LiteRT-LM 0.11 decodes
 *      [Content.AudioBytes] via MiniAudio's `ma_decoder_init_memory()`, which only accepts
 *      container formats (WAV / FLAC / MP3). Headerless raw PCM is silently rejected by the
 *      audio preprocessor — the model sees no audio context, returns an empty string, and
 *      the user gets an empty RAW transcript with no error surface. This was the
 *      "Tonight, two attempts, two failures" symptom on 2026-05-09.
 *   4. Send `Contents.of(Content.AudioBytes(wavBytes), Content.Text("Transcribe verbatim."))`
 *      to a Conversation.
 *   5. Collect MessageCallback.onMessage stream, return the joined text.
 *
 * Hard limit: Gemma 4 audio = 30 s per turn. AudioCaptureEngine already chunks at 30 s, so
 * one chunk = one turn = one transcribe call.
 *
 * F3.2 (2026-05-09): the LiteRT-LM [com.google.ai.edge.litertlm.Engine] is now provided by
 * [BidetSharedLiteRtEngineProvider] — same engine the chat-tab generation uses. Two engines
 * pointing at the same 3.6 GB model file is the demo-OOM scenario we're closing.
 *
 * 2026-05-09 (fresh-conversation fix): each call to [transcribe] creates its OWN
 * [com.google.ai.edge.litertlm.Conversation] off the shared engine, sends one
 * `(AudioBytes, Text)` turn, then closes it. Symptom of the prior single-shared-Conversation
 * design: chunk 0 transcribes correctly, chunks 1+ silently empty due to LiteRT-LM
 * Conversation accumulating history across `sendMessageAsync` calls — by chunk 1 the
 * conversation already contains `[system, audio_0, text_0, "Transcribe..."]` and the model
 * treats the second send as a continuation, emitting stop tokens immediately. Conversations
 * are cheap (session-state on top of the warm engine); the shared Engine stays warm across
 * chunks so this does NOT reload the 3.6 GB model.
 *
 * F3.4 (2026-05-09): [transcribe] is now `suspend` and uses
 * [suspendCancellableCoroutine] + `cont.invokeOnCancellation { conv.cancelProcess() }`
 * instead of [java.util.concurrent.CountDownLatch]. The latch was not coroutine-cancel-aware
 * — when [com.google.ai.edge.gallery.bidet.transcription.TranscriptionWorker] was cancelled
 * mid-transcribe (e.g. user tapped Stop), the latch kept blocking a Default-dispatcher
 * thread for up to 60 s and `cancelProcess()` was never called, so the underlying LiteRT-LM
 * run kept burning GPU. The TranscriptionWorker already wraps this call in a
 * `NonCancellable` scope on the Stop path so an in-flight transcribe still completes;
 * suspend is correct even there.
 *
 * F5.1 (2026-05-09): when a chunk is longer than the 30-s audio cap, [transcribe] now keeps
 * the LAST 30 seconds. AudioCaptureEngine emits 30-s chunks with a 2-s overlap — the *new*
 * audio is at the END, not the start. Truncating from the front (the prior bug) dropped the
 * 2-s tail every time, killing the dedup window between adjacent chunks.
 *
 * 2026-05-09 STATUS: experimental. Lives in the `gemma` flavor only. The `whisper` flavor
 * uses [WhisperEngine] and is unaffected.
 */
@OptIn(ExperimentalApi::class)
class GemmaAudioEngine @Inject constructor(
    // F3.2 (2026-05-09): kept on the constructor per the brief to align with the rest of
    // the bidet `@Inject`-eligible classes (LiteRtBidetGemmaClient, BidetSharedLiteRtEngineProvider).
    // The body doesn't reference it directly any more — model-path resolution moved to
    // the shared engine provider — but a future debug surface (e.g. a "dump model size to
    // notification" diagnostic) would naturally hang off the application context.
    @Suppress("unused") @ApplicationContext private val context: Context,
    private val sharedEngineProvider: BidetSharedLiteRtEngineProvider,
) : TranscriptionEngine {

    /**
     * 2026-05-09 fix: hold the shared LiteRT-LM [Engine] reference, NOT a long-lived
     * Conversation. Each [transcribe] call now spins up a fresh Conversation, sends one
     * `(AudioBytes, Text)` turn, and closes it. See [transcribe] for why.
     *
     * Provider owns the Engine lifecycle; we just cache the handle so [transcribe] doesn't
     * have to take the provider mutex on every chunk.
     */
    private val engineRef = AtomicReference<Engine?>(null)

    /**
     * True once we've successfully acquired the shared LiteRT-LM Engine. Engine presence is
     * sufficient — Conversations are now created per-chunk in [transcribe].
     */
    override val isReady: Boolean
        get() = engineRef.get() != null

    @Synchronized
    override fun initialize(): Boolean {
        if (isReady) return true
        return try {
            // Acquire the shared engine with audioBackend enabled. If the chat client
            // already brought up a text-only engine, the provider rebuilds in place — see
            // BidetSharedLiteRtEngineProvider.acquire kdoc for the upgrade path.
            //
            // runBlocking is intentional here: TranscriptionEngine.initialize() is a
            // synchronous Boolean contract honoured by both engine impls; the call site
            // (RecordingService.startRecording → EngineInitGate.tryInit) runs on
            // Dispatchers.Default, so the runBlocking is off the main thread. The shared
            // engine provider's mutex is fast (in-process Kotlin Mutex, not a JVM monitor)
            // and the actual engine boot is blocking native work either way.
            val engine = runBlocking {
                sharedEngineProvider.acquire(
                    requireAudio = true,
                    maxNumTokens = MAX_OUTPUT_TOKENS,
                )
            }
            engineRef.set(engine)
            Log.i(TAG, "initialize: Gemma 4 audio engine ready (shared LiteRT-LM engine)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "initialize failed: ${t.message}", t)
            false
        }
    }

    /**
     * F3.4 (2026-05-09): suspending. [TranscriptionWorker] is already a coroutine, so
     * keeping the contract synchronous with a CountDownLatch was needlessly thread-pinning
     * + cancellation-deaf. Now: [suspendCancellableCoroutine] resumes on `onDone`/`onError`,
     * and `cont.invokeOnCancellation { conv.cancelProcess() }` lets a coroutine cancel
     * actually free the underlying LiteRT-LM compute.
     */
    override suspend fun transcribe(floatPcm: FloatArray, sampleRateHz: Int): String {
        require(sampleRateHz == 16_000) {
            "GemmaAudioEngine requires 16 kHz input, got $sampleRateHz"
        }
        val engine = engineRef.get()
            ?: throw IllegalStateException("GemmaAudioEngine not initialized")

        // F5.1 fix (2026-05-09): keep the LAST 30 s of samples, not the first 30 s.
        // AudioCaptureEngine emits 30-s windows with a 2-s overlap. The NEW 2 seconds (the
        // dedup token) live at the END of the buffer, so truncating from the front
        // (`copyOfRange(0, maxSamples)`) silently drops the dedup window every time a
        // chunk crosses 30 s, breaking adjacency dedup. Keep `[size - maxSamples, size)`
        // so the most recent audio survives.
        val maxSamples = sampleRateHz * 30
        val samples = if (floatPcm.size > maxSamples) {
            Log.w(
                TAG,
                "transcribe: chunk has ${floatPcm.size} samples (~${floatPcm.size / 16_000}s); " +
                    "keeping the last 30s for Gemma audio limit (was previously dropping the " +
                    "tail and breaking dedup overlap).",
            )
            floatPcm.copyOfRange(floatPcm.size - maxSamples, floatPcm.size)
        } else floatPcm

        // 2026-05-09 fix: wrap the int16 PCM in a 44-byte RIFF/WAVE header before handing
        // off to LiteRT-LM. See [float32ToWavBytes] kdoc + class-level kdoc for why.
        val wavBytes = float32ToWavBytes(samples, sampleRateHz)

        // 2026-05-09 fix: fresh Conversation per chunk. Symptom: chunk 0 transcribes
        // correctly, chunks 1+ silently empty due to LiteRT-LM Conversation accumulating
        // history across `sendMessageAsync` calls — by chunk 1 the conversation already
        // contains [system, audio_0, text_0, "Transcribe..."] and the model treats the
        // next send as a continuation, emitting stop tokens immediately. Each chunk needs
        // a clean slate. The shared Engine stays warm across chunks (no model reload);
        // only the per-turn Conversation is recreated.
        val conv = engine.createConversation(
            ConversationConfig(
                // 2026-05-09 fix: temperature=0.0 with topK/topP set is mathematically
                // incoherent (greedy/argmax + nucleus sampling) and some LiteRT-LM
                // sampler paths divide-by-zero on it. For verbatim transcription we
                // want effectively-deterministic output without the degenerate edge
                // case — temperature=0.05 is the standard "low entropy, well defined"
                // setting. The chat client uses 0.4+; we stay much lower because
                // creative sampling on transcription introduces hallucinated words.
                samplerConfig = SamplerConfig(
                    topK = 40,
                    topP = 0.95,
                    temperature = TRANSCRIBE_TEMPERATURE,
                ),
                systemInstruction = Contents.of(SYSTEM_PROMPT),
                tools = emptyList(),
            )
        )

        try {
            return suspendCancellableCoroutine { cont ->
                val sb = StringBuilder()
                val callback = object : MessageCallback {
                    override fun onMessage(message: Message) {
                        sb.append(message.toString())
                    }
                    override fun onDone() {
                        if (cont.isActive) cont.resume(sb.toString().trim())
                    }
                    override fun onError(throwable: Throwable) {
                        Log.e(TAG, "transcribe: Gemma audio onError: ${throwable.message}", throwable)
                        // Empty string mirrors the legacy contract: an onError for a 30-s
                        // window is a soft-fail (transient GPU glitch, etc.) — the session
                        // should continue rather than poison the aggregator with a failure
                        // marker every time.
                        if (cont.isActive) cont.resume("")
                    }
                }
                try {
                    conv.sendMessageAsync(
                        Contents.of(
                            listOf(
                                Content.AudioBytes(wavBytes),
                                Content.Text(
                                    "Transcribe the speech verbatim. Output only the transcription, no commentary.",
                                ),
                            )
                        ),
                        callback,
                        emptyMap(),
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "transcribe: sendMessageAsync threw: ${t.message}", t)
                    if (cont.isActive) cont.resume("")
                }

                cont.invokeOnCancellation {
                    // F3.4 fix (2026-05-09): on coroutine cancellation, free the underlying
                    // LiteRT-LM compute so we don't keep burning GPU after the user tapped
                    // Stop. Previously the latch sat there for up to 60 s pinning a thread.
                    try {
                        conv.cancelProcess()
                    } catch (t: Throwable) {
                        Log.w(TAG, "cancelProcess threw on coroutine cancellation", t)
                    }
                }
            }
        } finally {
            try { conv.close() } catch (t: Throwable) {
                Log.w(TAG, "transcribe: per-chunk conversation.close threw", t)
            }
        }
    }

    @Synchronized
    override fun close() {
        // 2026-05-09 fix: drop our cached Engine reference only. The Engine is owned by
        // [BidetSharedLiteRtEngineProvider] — closing it here would yank the model out
        // from under the chat client (LiteRtBidetGemmaClient) too. Provider releases the
        // engine on process death. Per-chunk Conversations are now closed inside
        // [transcribe]'s finally block, so there's nothing else to release here.
        engineRef.set(null)
    }

    companion object {
        private const val TAG = "BidetGemmaAudioEngine"
        // v0.2 (2026-05-09): bumped 1024 → 16384 to match the chat path. Two consequences:
        //  (a) the audio engine itself never produces close to 1024 tokens of output (a 30 s
        //      verbatim transcription is at most a few hundred), but the shared LiteRT-LM
        //      Engine is constructed with the larger of the audio + chat acquirers'
        //      maxNumTokens. Bumping here lets the audio engine acquire the engine first
        //      without forcing a rebuild on the chat client's larger request.
        //  (b) Mark hit the upstream-Gallery 1024 cap on the chat side during live testing
        //      (LiteRT error: "1064 ≥ 1024"). 16384 gives plenty of headroom on both paths
        //      and is still well below Gemma 4 E4B's 128k context window.
        private const val MAX_OUTPUT_TOKENS = 16384
        // 2026-05-09: see ConversationConfig comment in [initialize]. 0.0 is the degenerate
        // case we're moving off of; 0.05 keeps the sampler well-defined while still being
        // effectively deterministic for verbatim transcription.
        private const val TRANSCRIBE_TEMPERATURE: Double = 0.05
        private const val SYSTEM_PROMPT =
            "You are a verbatim transcription engine. Output only the spoken words exactly as said. " +
                "No timestamps, no speaker labels, no commentary, no punctuation cleanup beyond the natural sentence boundaries. " +
                "If the audio contains no speech, output an empty string."
    }
}

/**
 * F5.1 (2026-05-09): pure-Kotlin helper extracted for unit testing. Returns the slice of
 * [floatPcm] that should be fed to Gemma's 30-s audio cap given an input that may exceed
 * the cap. This is the one-line bug we fixed: previously we kept `[0, maxSamples)` and
 * dropped the most recent audio + the 2-s overlap window with the previous chunk. Now
 * we keep `[size - maxSamples, size)`.
 */
internal fun gemmaAudioTruncatedTail(floatPcm: FloatArray, maxSamples: Int): FloatArray {
    return if (floatPcm.size > maxSamples) {
        floatPcm.copyOfRange(floatPcm.size - maxSamples, floatPcm.size)
    } else floatPcm
}

/**
 * 2026-05-09: pure-Kotlin helper extracted for unit testing. Encodes Float32 mono samples
 * (range ±1.0) as a complete RIFF/WAVE container: 44-byte canonical PCM header followed by
 * little-endian int16 PCM samples.
 *
 * Why this exists: LiteRT-LM 0.11's audio preprocessor uses MiniAudio's
 * `ma_decoder_init_memory()`, which only accepts container formats (WAV / FLAC / MP3).
 * Headerless raw PCM bytes are silently rejected — the audio executor decodes 0 frames,
 * the model sees no audio context, the user gets an empty transcript with no error
 * surface. Wrapping the PCM in a 44-byte canonical RIFF/WAVE header is the minimum-viable
 * fix; the same header layout is already used by [com.google.ai.edge.gallery.bidet.audio.WavConcatenator]
 * for the on-disk session WAV, so the format is well-known to work in this stack.
 *
 * The header is 44 bytes; the body is `2 * floatPcm.size` bytes (one int16 per sample).
 *
 * @param floatPcm normalized Float32 mono samples, clipped to ±1.0 then scaled to int16.
 * @param sampleRateHz sample rate in Hz; must match what the AudioCaptureEngine produced.
 * @return a complete WAV file as a ByteArray (header + interleaved-but-mono samples).
 */
internal fun float32ToWavBytes(floatPcm: FloatArray, sampleRateHz: Int): ByteArray {
    require(sampleRateHz > 0) { "sampleRateHz must be positive, got $sampleRateHz" }
    val dataLen = floatPcm.size * 2
    val out = ByteArray(WAV_HEADER_SIZE + dataLen)

    // --- Header (44 bytes, RIFF/WAVE PCM mono 16-bit) ---
    val channels = 1
    val bitsPerSample = 16
    val byteRate = sampleRateHz * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val bb = ByteBuffer.wrap(out, 0, WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    // "RIFF"
    bb.put('R'.code.toByte()); bb.put('I'.code.toByte()); bb.put('F'.code.toByte()); bb.put('F'.code.toByte())
    // ChunkSize = 36 + dataLen
    bb.putInt(36 + dataLen)
    // "WAVE"
    bb.put('W'.code.toByte()); bb.put('A'.code.toByte()); bb.put('V'.code.toByte()); bb.put('E'.code.toByte())
    // "fmt "
    bb.put('f'.code.toByte()); bb.put('m'.code.toByte()); bb.put('t'.code.toByte()); bb.put(' '.code.toByte())
    // Subchunk1Size = 16 (PCM)
    bb.putInt(16)
    // AudioFormat = 1 (PCM)
    bb.putShort(1)
    // NumChannels
    bb.putShort(channels.toShort())
    // SampleRate
    bb.putInt(sampleRateHz)
    // ByteRate
    bb.putInt(byteRate)
    // BlockAlign
    bb.putShort(blockAlign.toShort())
    // BitsPerSample
    bb.putShort(bitsPerSample.toShort())
    // "data"
    bb.put('d'.code.toByte()); bb.put('a'.code.toByte()); bb.put('t'.code.toByte()); bb.put('a'.code.toByte())
    // Subchunk2Size
    bb.putInt(dataLen)

    // --- Body: clipped Float32 → int16 LE ---
    var off = WAV_HEADER_SIZE
    for (i in floatPcm.indices) {
        val clipped = max(-1f, min(1f, floatPcm[i]))
        val s = (clipped * 32767f).toInt()
        out[off] = (s and 0xFF).toByte()
        out[off + 1] = ((s ushr 8) and 0xFF).toByte()
        off += 2
    }
    return out
}

/** Canonical PCM/WAV header size in bytes — referenced by tests. */
internal const val WAV_HEADER_SIZE: Int = 44
