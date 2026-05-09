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
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Gemma 4 E4B's built-in audio encoder used directly via LiteRT-LM ≥ 0.10.1. No Whisper.
 *
 * Selected by the `gemma` Gradle product flavor (see [TranscriptionEngine.create]).
 *
 * Pipeline per chunk:
 *   1. AudioCaptureEngine writes 30-sec int16 PCM at 16 kHz to a chunk file.
 *   2. TranscriptionWorker calls `int16ToFloat32` then `transcribe(floatPcm, 16_000)`.
 *   3. We convert back to int16 PCM bytes (Content.AudioBytes wants int16 16 kHz mono).
 *   4. Send `Contents.of(Content.AudioBytes(pcmBytes), Content.Text("Transcribe verbatim."))`
 *      to a Conversation.
 *   5. Collect MessageCallback.onMessage stream, return the joined text.
 *
 * Hard limit: Gemma 4 audio = 30 s per turn. AudioCaptureEngine already chunks at 30 s, so
 * one chunk = one turn = one transcribe call.
 *
 * 2026-05-09 STATUS: experimental. Lives in the `gemma` flavor only. The `whisper` flavor
 * uses [WhisperEngine] and is unaffected.
 */
@OptIn(ExperimentalApi::class)
class GemmaAudioEngine(private val context: Context) : TranscriptionEngine {

    private val engineRef = AtomicReference<Engine?>(null)
    private val conversationRef = AtomicReference<Conversation?>(null)

    /** True once the LiteRT-LM Engine + Conversation are alive. */
    override val isReady: Boolean
        get() = engineRef.get() != null && conversationRef.get() != null

    @Synchronized
    override fun initialize(): Boolean {
        if (isReady) return true
        return try {
            // Resolve the same model path BidetModelProvider uses, without going through
            // the Hilt-injected impl (we can't @Inject into a manually-constructed class).
            val base = context.getExternalFilesDir(null)
                ?: throw IllegalStateException("No external files dir for Gemma model lookup")
            val modelFile = File(base, "models/v1/gemma-4-E4B-it.litertlm")
            if (!modelFile.exists() || modelFile.length() == 0L) {
                throw IllegalStateException(
                    "Gemma model missing at ${modelFile.absolutePath}; complete first-run download."
                )
            }
            val cacheDir = context.getExternalFilesDir(null)?.absolutePath
            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.GPU(),
                visionBackend = null,
                audioBackend = Backend.CPU(),
                maxNumTokens = MAX_OUTPUT_TOKENS,
                cacheDir = cacheDir,
            )
            val engine = Engine(config)
            engine.initialize()
            val conv = engine.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK = 40,
                        topP = 0.95,
                        temperature = 0.0,
                    ),
                    systemInstruction = Contents.of(SYSTEM_PROMPT),
                    tools = emptyList(),
                )
            )
            engineRef.set(engine)
            conversationRef.set(conv)
            Log.i(
                TAG,
                "initialize: Gemma 4 audio engine ready, model=${modelFile.absolutePath} (${modelFile.length()} bytes)"
            )
            true
        } catch (t: Throwable) {
            Log.e(TAG, "initialize failed: ${t.message}", t)
            false
        }
    }

    override fun transcribe(floatPcm: FloatArray, sampleRateHz: Int): String {
        require(sampleRateHz == 16_000) {
            "GemmaAudioEngine requires 16 kHz input, got $sampleRateHz"
        }
        val conv = conversationRef.get()
            ?: throw IllegalStateException("GemmaAudioEngine not initialized")

        // Gemma 4 audio max = 30 s per turn. AudioCaptureEngine chunks at 30 s, but slight
        // overlap could push us over. Hard-truncate at exactly 30 s of samples to stay safe.
        val maxSamples = sampleRateHz * 30
        val samples = if (floatPcm.size > maxSamples) {
            Log.w(TAG, "transcribe: chunk has ${floatPcm.size} samples (${floatPcm.size / 16_000}s); truncating to 30s for Gemma audio limit")
            floatPcm.copyOfRange(0, maxSamples)
        } else floatPcm

        val pcmBytes = float32ToInt16Bytes(samples)

        val sb = StringBuilder()
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        val callback = object : MessageCallback {
            override fun onMessage(message: Message) {
                sb.append(message.toString())
            }
            override fun onDone() {
                latch.countDown()
            }
            override fun onError(throwable: Throwable) {
                error = throwable
                latch.countDown()
            }
        }
        conv.sendMessageAsync(
            Contents.of(
                listOf(
                    Content.AudioBytes(pcmBytes),
                    Content.Text("Transcribe the speech verbatim. Output only the transcription, no commentary."),
                )
            ),
            callback,
            emptyMap(),
        )
        // Bounded wait — Gemma audio on Pixel 8 Pro should finish in ≤ 8 s for 30 s chunk.
        // 60-second timeout is generous; if we hit it, something is wrong (model not loaded
        // properly, audio backend mis-wired) and we surface as empty + log.
        if (!latch.await(60, TimeUnit.SECONDS)) {
            Log.w(TAG, "transcribe: timed out after 60s waiting for Gemma audio response")
            try { conv.cancelProcess() } catch (_: Throwable) {}
            return ""
        }
        error?.let {
            Log.e(TAG, "transcribe: Gemma audio onError: ${it.message}", it)
            return ""
        }
        return sb.toString().trim()
    }

    @Synchronized
    override fun close() {
        try {
            conversationRef.getAndSet(null)?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close: conversation.close threw: ${t.message}")
        }
        try {
            engineRef.getAndSet(null)?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close: engine.close threw: ${t.message}")
        }
    }

    /** Convert normalized Float32 [-1, 1] → little-endian int16 PCM bytes. */
    private fun float32ToInt16Bytes(floatPcm: FloatArray): ByteArray {
        val out = ByteArray(floatPcm.size * 2)
        for (i in floatPcm.indices) {
            val v = max(-1f, min(1f, floatPcm[i])) * 32767f
            val s = v.toInt()
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
        }
        return out
    }

    companion object {
        private const val TAG = "BidetGemmaAudioEngine"
        private const val MAX_OUTPUT_TOKENS = 1024
        private const val SYSTEM_PROMPT =
            "You are a verbatim transcription engine. Output only the spoken words exactly as said. " +
                "No timestamps, no speaker labels, no commentary, no punctuation cleanup beyond the natural sentence boundaries. " +
                "If the audio contains no speech, output an empty string."
    }
}
