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
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Thin wrapper around the vendored whisper.cpp Android JNI ([WhisperContext]).
 *
 * 2026-05-08 rewrite: replaced the broken `io.github.givimad:whisper-jni` desktop JAR (which
 * shipped no Android binaries — Bug C / Issue #10) with a proper NDK build of whisper.cpp.
 * Public API surface preserved: `initialize`, `transcribe`, `close`, `isReady`, `int16ToFloat32`.
 * Callers in [TranscriptionWorker] and [com.google.ai.edge.gallery.bidet.service.RecordingService]
 * compile unchanged.
 *
 * Behaviour:
 *  - Initialized once at app start.
 *  - Holds a single [WhisperContext]; whisper-tiny is single-threaded for inference, so we
 *    serialize calls externally (the [TranscriptionWorker] has only one consumer).
 *  - [transcribe] takes a Float32 PCM array (already normalized to ±1.0) at 16 kHz mono and
 *    returns the transcribed text.
 *  - The bundled model lives at `assets/whisper/ggml-tiny.en.bin` and is copied to
 *    `${context.filesDir}/whisper/ggml-tiny.en.bin` on first init (the native loader needs a
 *    real filesystem path).
 *
 * Concurrency: [WhisperContext.transcribeData] and [WhisperContext.release] are `suspend`.
 * F3.4 (2026-05-09): [transcribe] is now `suspend` directly so we call those without
 * `runBlocking`. [close] still uses `runBlocking` because the [TranscriptionEngine.close]
 * contract is sync (called during service teardown, not from a coroutine). The
 * RecordingService.stopRecording call site runs `close()` from a Dispatchers.Default
 * launch, so the runBlocking happens off the main thread.
 */
class WhisperEngine(private val context: Context) : TranscriptionEngine {

    private val ctxRef = AtomicReference<WhisperContext?>()

    /** True once the model has been copied out of assets and the native context is loaded. */
    override val isReady: Boolean get() = ctxRef.get() != null

    /**
     * One-time initialization. Safe to call multiple times — subsequent calls are no-ops.
     * @return true on success, false if the model could not be located or the native context
     *   could not be created.
     */
    @Synchronized
    override fun initialize(): Boolean {
        if (isReady) return true
        return try {
            val modelPath = ensureModelOnDisk()
            val ctx = WhisperContext.createContextFromFile(modelPath.absolutePath)
            ctxRef.set(ctx)
            Log.i(TAG, "initialize: loaded ${modelPath.absolutePath} (${modelPath.length()} bytes)")
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
    override suspend fun transcribe(floatPcm: FloatArray, sampleRateHz: Int): String {
        require(sampleRateHz == 16_000) {
            "WhisperEngine requires 16 kHz input, got $sampleRateHz"
        }
        val ctx = ctxRef.get()
            ?: throw IllegalStateException("WhisperEngine not initialized")
        // F3.4 (2026-05-09): now suspends. The interface is `suspend fun transcribe(...)`
        // (see TranscriptionEngine.kt rationale). [WhisperContext.transcribeData] is
        // already a `suspend` function in the upstream Kotlin bindings, so we just await
        // it directly — no `runBlocking` needed.
        val text = ctx.transcribeData(floatPcm, printTimestamp = false)
        return text.trim()
    }

    /** Release native resources. Idempotent. */
    @Synchronized
    override fun close() {
        ctxRef.getAndSet(null)?.let {
            try {
                runBlocking { it.release() }
            } catch (t: Throwable) {
                Log.w(TAG, "close: release threw ${t.message}")
            }
        }
    }

    /**
     * Copy the bundled model out of assets into [context.filesDir] on first call. Subsequent
     * calls return the existing file. The native loader `mmap`s by path, so we need a real
     * filesystem location.
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

    companion object {
        private const val TAG = "BidetWhisperEngine"
        const val ASSET_MODEL_NAME: String = "ggml-tiny.en.bin"
    }
}
