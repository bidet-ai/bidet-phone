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

/**
 * Common surface for any speech-to-text engine. Two implementations exist:
 *  - [WhisperEngine]      — whisper.cpp NDK + ggml-tiny.en
 *  - [GemmaAudioEngine]   — Gemma 4 E4B's built-in audio encoder via LiteRT-LM
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
     * @param floatPcm Float32 mono samples in the range [-1.0, 1.0].
     * @param sampleRateHz Audio sample rate in Hz. Both engines require 16 kHz.
     * @return The transcribed text, trimmed. May be empty for silence/non-speech.
     * @throws IllegalStateException if [initialize] has not succeeded.
     * @throws IllegalArgumentException if [sampleRateHz] is not 16 000.
     */
    fun transcribe(floatPcm: FloatArray, sampleRateHz: Int = 16_000): String

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
         */
        fun create(context: Context): TranscriptionEngine =
            if (BuildConfig.USE_GEMMA_AUDIO) {
                GemmaAudioEngine(context)
            } else {
                WhisperEngine(context)
            }
    }
}
