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
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
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
 *   3. We convert back to int16 PCM bytes (Content.AudioBytes wants int16 16 kHz mono).
 *   4. Send `Contents.of(Content.AudioBytes(pcmBytes), Content.Text("Transcribe verbatim."))`
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
     * Live conversation off the shared engine. We create one and reuse it across chunks
     * (one Conversation = one stateful audio stream from Gemma's POV; per turn we send a
     * fresh AudioBytes + Text Contents pair). Provider owns the Engine; we own the
     * Conversation lifetime.
     */
    private val conversationRef = AtomicReference<Conversation?>(null)

    /**
     * True once the LiteRT-LM Engine + Conversation are alive. We don't peek at the engine
     * directly — the provider owns engine lifecycle. Conversation presence is sufficient
     * (the provider can't hand us a conversation off a dead engine).
     */
    override val isReady: Boolean
        get() = conversationRef.get() != null

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
            conversationRef.set(conv)
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
        val conv = conversationRef.get()
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

        val pcmBytes = float32ToInt16Bytes(samples)

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
                            Content.AudioBytes(pcmBytes),
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
    }

    @Synchronized
    override fun close() {
        // F3.2 (2026-05-09): close the conversation only. The Engine is owned by
        // [BidetSharedLiteRtEngineProvider] — closing it here would yank the model out
        // from under the chat client (BidetGemmaClient) too. Provider releases the engine
        // on process death.
        try {
            conversationRef.getAndSet(null)?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close: conversation.close threw: ${t.message}")
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
