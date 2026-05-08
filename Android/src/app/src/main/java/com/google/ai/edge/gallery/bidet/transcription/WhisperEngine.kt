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
import io.github.givimad.whisperjni.WhisperContext
import io.github.givimad.whisperjni.WhisperFullParams
import io.github.givimad.whisperjni.WhisperJNI
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Thin Kotlin wrapper around whisper-jni 1.7.1 (`io.github.givimad:whisper-jni`).
 *
 * Behaviour per brief §4 / §10:
 *  - Initialized once at app start.
 *  - Holds a single [WhisperContext]; whisper-tiny is single-threaded for inference, so we
 *    serialize calls externally (the [TranscriptionWorker] has only one consumer).
 *  - [transcribe] takes a Float32 PCM array (already normalized to ±1.0) at 16 kHz mono and
 *    returns the transcribed text.
 *  - The bundled model lives at `assets/whisper/ggml-tiny.en.bin` and is copied to
 *    `${context.filesDir}/whisper/ggml-tiny.en.bin` on first init (whisper-jni cannot read
 *    directly out of an APK asset stream).
 *
 * Failure modes:
 *  - If model copy or context init fails, [isReady] returns false and [transcribe] throws.
 *    [TranscriptionWorker] catches this and emits the failure marker per chunk.
 */
class WhisperEngine(private val context: Context) {

    private val whisperJni: WhisperJNI = WhisperJNI()
    private val ctxRef = AtomicReference<WhisperContext?>()

    /** True once the model has been copied out of assets and the JNI context loaded. */
    val isReady: Boolean get() = ctxRef.get() != null

    /**
     * One-time initialization. Safe to call multiple times — subsequent calls are no-ops.
     * @return true on success, false if the model could not be located or the native context
     *   could not be created.
     */
    @Synchronized
    fun initialize(): Boolean {
        if (isReady) return true
        return try {
            WhisperJNI.loadLibrary()
            val modelPath = ensureModelOnDisk()
            val ctx = whisperJni.init(modelPath.toPath())
                ?: throw IllegalStateException("WhisperJNI.init returned null for $modelPath")
            ctxRef.set(ctx)
            Log.i(TAG, "initialize: loaded $modelPath (${modelPath.length()} bytes)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "initialize failed: ${t.message}", t)
            false
        }
    }

    /**
     * Transcribe one chunk's worth of audio.
     *
     * @param floatPcm Float32 mono samples in the range [-1.0, 1.0]. Length should be roughly
     *   `sampleRateHz * durationSec`.
     * @param sampleRateHz the input sample rate. Whisper requires 16 kHz; if a different rate
     *   is passed we throw rather than silently mis-transcribe.
     * @return the transcribed text, trimmed.
     * @throws IllegalStateException if [initialize] has not succeeded.
     * @throws IllegalArgumentException if [sampleRateHz] != 16000.
     */
    fun transcribe(floatPcm: FloatArray, sampleRateHz: Int = 16_000): String {
        require(sampleRateHz == 16_000) {
            "WhisperEngine requires 16 kHz input, got $sampleRateHz"
        }
        val ctx = ctxRef.get()
            ?: throw IllegalStateException("WhisperEngine not initialized")
        val params = WhisperFullParams().apply {
            // English-only model + greedy sampling: lowest latency + adequate quality for
            // brain-dump verbatim. Tweak in v0.2 if quality demands.
            language = "en"
            translate = false
            printRealtime = false
            printProgress = false
            printTimestamps = false
            noContext = true
            singleSegment = false
        }
        val rc = whisperJni.full(ctx, params, floatPcm, floatPcm.size)
        if (rc != 0) {
            throw IllegalStateException("WhisperJNI.full returned non-zero rc=$rc")
        }
        val n = whisperJni.fullNSegments(ctx)
        val sb = StringBuilder()
        for (i in 0 until n) {
            sb.append(whisperJni.fullGetSegmentText(ctx, i))
        }
        return sb.toString().trim()
    }

    /** Release native resources. Idempotent. */
    @Synchronized
    fun close() {
        ctxRef.getAndSet(null)?.let {
            try {
                whisperJni.free(it)
            } catch (t: Throwable) {
                Log.w(TAG, "close: free threw ${t.message}")
            }
        }
    }

    /**
     * Copy the bundled model out of assets into [context.filesDir] on first call. Subsequent
     * calls return the existing file. Whisper-jni's native side `mmap`s by path, so we need a
     * real filesystem location.
     */
    private fun ensureModelOnDisk(): File {
        val dest = File(context.filesDir, "whisper/$ASSET_MODEL_NAME").apply {
            parentFile?.mkdirs()
        }
        if (dest.exists() && dest.length() > 0) return dest
        context.assets.open("whisper/$ASSET_MODEL_NAME").use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
        return dest
    }

    /**
     * Convert int16 little-endian PCM bytes into a normalized Float32 array. Used by
     * [TranscriptionWorker] before each [transcribe] call.
     */
    fun int16ToFloat32(pcmBytes: ByteArray): FloatArray {
        val out = FloatArray(pcmBytes.size / 2)
        var j = 0
        var i = 0
        while (i + 1 < pcmBytes.size) {
            val lo = pcmBytes[i].toInt() and 0xFF
            val hi = pcmBytes[i + 1].toInt()
            val sample = (lo or (hi shl 8)).toShort().toInt()
            out[j++] = sample / 32_768f
            i += 2
        }
        return out
    }

    companion object {
        private const val TAG = "BidetWhisperEngine"
        const val ASSET_MODEL_NAME: String = "ggml-tiny.en.bin"
    }
}
