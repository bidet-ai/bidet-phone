/*
 * Copyright 2026 bidet-ai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.bidet.transcription

import android.content.Context
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.bidet.llm.BidetSharedLiteRtEngineProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Common surface for any speech-to-text engine. Two implementations exist:
 *  - [MoonshineEngine]    — Moonshine-Tiny ONNX via sherpa-onnx (replaced WhisperEngine
 *                            in v0.3 — smaller, faster, more accurate; routed-STT story for
 *                            the Cactus prize).
 *  - [GemmaAudioEngine]   — Gemma 4 E4B's built-in audio encoder via LiteRT-LM (single-model
 *                            integrated path; demo's "Gemma everywhere" narrative).
 *
 * Selected per Gradle product flavor via [BuildConfig.USE_GEMMA_AUDIO]. Both APKs are
 * installable side-by-side on the same device with distinct applicationIds for A/B
 * comparison.
 *
 * The [TranscriptionWorker] consumes this interface; it doesn't care which engine is wired.
 */
interface TranscriptionEngine {

    /** True once the engine is ready to [transcribe]. */
    val isReady: Boolean

    /**
     * One-time initialization. Safe to call multiple times — subsequent calls are no-ops.
     * Returns true on success, false if the engine could not be initialized (e.g. model
     * file missing). On false, [transcribe] will throw if called.
     */
    fun initialize(): Boolean

    /**
     * Transcribe one chunk of audio.
     *
     * F3.4 (2026-05-09): now `suspend` so the LiteRT-LM-backed [GemmaAudioEngine] can use
     * [kotlinx.coroutines.suspendCancellableCoroutine] + `cont.invokeOnCancellation` to
     * release the underlying Gemma run when the parent coroutine is cancelled. The
     * previous sync contract forced [GemmaAudioEngine] to block on a
     * [java.util.concurrent.CountDownLatch], which pinned a Default-dispatcher thread for
     * up to 60 s and never propagated coroutine cancellation down to LiteRT-LM.
     * [MoonshineEngine] satisfies the suspend contract by wrapping the synchronous
     * sherpa-onnx [com.k2fsa.sherpa.onnx.OfflineRecognizer.decode] call in
     * `withContext(Dispatchers.Default)` (the decode itself is fast — typically 100-300 ms
     * per chunk on Pixel 8 Pro CPU — so we don't need cancellation propagation the way
     * GemmaAudioEngine does).
     *
     * @param floatPcm Float32 mono samples in the range [-1.0, 1.0].
     * @param sampleRateHz Audio sample rate in Hz. Both engines require 16 kHz.
     * @return The transcribed text, trimmed. May be empty for silence/non-speech.
     * @throws IllegalStateException if [initialize] has not succeeded.
     * @throws IllegalArgumentException if [sampleRateHz] is not 16 000.
     */
    suspend fun transcribe(floatPcm: FloatArray, sampleRateHz: Int = 16_000): String

    /** Convert int16 little-endian PCM bytes to a normalized Float32 array (helper). */
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

    /** Release native resources. Idempotent. */
    fun close()

    companion object {
        /**
         * Construct the engine for the current product flavor. Reads
         * [BuildConfig.USE_GEMMA_AUDIO] to pick.
         *
         * F3.2 (2026-05-09): the gemma flavor reaches into Hilt for the shared
         * [BidetSharedLiteRtEngineProvider] via an EntryPoint rather than `@Inject`-ing
         * directly into [GemmaAudioEngine] from this static factory. Tests for
         * [com.google.ai.edge.gallery.bidet.service.RecordingService] swap in their own
         * `engineFactory` (see the property of that name on `RecordingService`); they're
         * free to construct a fake engine without touching Hilt at all.
         */
        fun create(context: Context): TranscriptionEngine =
            if (BuildConfig.USE_GEMMA_AUDIO) {
                val provider = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    SharedEngineEntryPoint::class.java,
                ).sharedEngineProvider()
                GemmaAudioEngine(context.applicationContext, provider)
            } else {
                MoonshineEngine(context)
            }
    }

    /**
     * F3.2 (2026-05-09): Hilt EntryPoint reaching the shared LiteRT-LM engine provider
     * from the non-Hilt [create] factory. The Hilt graph itself sees the provider as a
     * `@Singleton` `@Inject`-able; this entry-point is just the bridge for the static
     * factory.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SharedEngineEntryPoint {
        fun sharedEngineProvider(): BidetSharedLiteRtEngineProvider
    }
}
