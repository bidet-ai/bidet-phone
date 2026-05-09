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

package com.google.ai.edge.gallery.bidet.ui

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.bidet.download.BidetModelProvider
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Concrete [BidetGemmaClient] backed directly by LiteRT-LM. Phase 3 wiring per brief §6 + §8.
 *
 * Lifecycle:
 *  - [BidetModelProvider] owns the on-disk model file (download + verify lifecycle).
 *  - [BidetSharedLiteRtEngineProvider] owns the live LiteRT-LM
 *    [com.google.ai.edge.litertlm.Engine].
 *
 *    F3.2 fix (2026-05-09): the engine is shared with
 *    [com.google.ai.edge.gallery.bidet.transcription.GemmaAudioEngine] so the gemma flavor
 *    holds exactly ONE engine in memory rather than two engines pointing at the same
 *    3.6 GB model file. See the provider's class-level kdoc for the rationale.
 *  - Each [runInference] call resets the [Conversation] so the four tabs do not contaminate
 *    each other's KV cache. The system prompt is wired via
 *    [ConversationConfig.systemInstruction] on
 *    [com.google.ai.edge.litertlm.Engine.createConversation] (closer to LiteRT-LM grain
 *    than concatenating into the user content per the brief).
 *
 * If [BidetModelProvider.isModelReady] returns false, [runInference] throws
 * [BidetModelNotReadyException]. The four tab composables surface this error and route the
 * user to [GemmaDownloadScreen] (handled at the [BidetMainScreen] level).
 */
@Singleton
class LiteRtBidetGemmaClient @Inject constructor(
    @Suppress("UNUSED_PARAMETER") @ApplicationContext private val context: Context,
    private val modelProvider: BidetModelProvider,
    private val sharedEngineProvider: BidetSharedLiteRtEngineProvider,
) : BidetGemmaClient {

    /**
     * F3.2 (2026-05-09): the [com.google.ai.edge.litertlm.Engine] is owned by
     * [BidetSharedLiteRtEngineProvider] now — we only track the active [Conversation]
     * locally. Closing the conversation when a new tab generation starts is still our job;
     * closing the engine is NOT (the audio engine shares it; the provider holds it for the
     * process lifetime).
     */
    @Volatile private var conversation: Conversation? = null
    private val initMutex = Mutex()

    @OptIn(ExperimentalApi::class)
    override suspend fun runInference(
        systemPrompt: String,
        userPrompt: String,
        maxOutputTokens: Int,
        temperature: Float,
    ): String = runInferenceStreaming(
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        maxOutputTokens = maxOutputTokens,
        temperature = temperature,
        onChunk = { _, _ -> /* non-streaming caller drops chunks; final result returned */ },
    )

    @OptIn(ExperimentalApi::class)
    override suspend fun runInferenceStreaming(
        systemPrompt: String,
        userPrompt: String,
        maxOutputTokens: Int,
        temperature: Float,
        onChunk: (cumulativeText: String, chunkIndex: Int) -> Unit,
    ): String {
        if (!modelProvider.isModelReady()) {
            throw BidetModelNotReadyException(
                "Gemma 4 model is not downloaded. Complete the first-run download before " +
                    "generating CLEAN/ANALYSIS/FORAI tabs."
            )
        }

        // Build / rebuild the conversation under a mutex so concurrent tab generations don't
        // race the LiteRT-LM Conversation lifecycle.
        val activeConversation = initMutex.withLock {
            val engine = try {
                sharedEngineProvider.acquire(
                    requireAudio = false,
                    maxNumTokens = maxOutputTokens,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "shared engine acquire failed", t)
                throw BidetModelNotReadyException(
                    "Failed to initialize Gemma 4 LiteRT-LM engine: ${t.message}"
                )
            }
            // Each tab call is one-shot: close the previous conversation (if any) and
            // create a fresh one with the per-call systemInstruction + sampler config. The
            // brief calls out passing system as `systemInstruction` instead of pre-
            // concatenating into the user prompt; this implements that.
            val existing = conversation
            if (existing != null) {
                try { existing.close() } catch (t: Throwable) {
                    Log.w(TAG, "previous conversation.close threw, continuing", t)
                }
            }
            val conv = engine.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK = DEFAULT_TOPK,
                        topP = DEFAULT_TOPP.toDouble(),
                        temperature = temperature.toDouble(),
                    ),
                    systemInstruction = Contents.of(systemPrompt),
                    tools = emptyList(),
                )
            )
            conversation = conv
            conv
        }

        return suspendCancellableCoroutine { cont ->
            val sb = StringBuilder()
            var chunkIndex = 0
            val callback = object : MessageCallback {
                override fun onMessage(message: Message) {
                    sb.append(message.toString())
                    chunkIndex += 1
                    // Hand the running cumulative text to the caller for live UI updates.
                    // We pass the cumulative String rather than the per-chunk delta so the
                    // caller can publish atomically without maintaining its own buffer.
                    onChunk(sb.toString(), chunkIndex)
                }

                override fun onDone() {
                    if (cont.isActive) cont.resume(sb.toString())
                }

                override fun onError(throwable: Throwable) {
                    if (cont.isActive) cont.resumeWithException(throwable)
                }
            }
            try {
                activeConversation.sendMessageAsync(
                    Contents.of(listOf(Content.Text(userPrompt))),
                    callback,
                    emptyMap(),
                )
            } catch (t: Throwable) {
                if (cont.isActive) cont.resumeWithException(t)
            }

            cont.invokeOnCancellation {
                try {
                    activeConversation.cancelProcess()
                } catch (e: Throwable) {
                    Log.w(TAG, "cancelProcess threw on coroutine cancellation", e)
                }
            }
        }
    }

    companion object {
        private const val TAG = "BidetGemmaClient"

        // Conservative defaults; brief locks temperature at 0.4 per call, topK/topP follow
        // upstream Gallery's defaults (DEFAULT_TOPK=40, DEFAULT_TOPP=0.95).
        private const val DEFAULT_TOPK: Int = 40
        private const val DEFAULT_TOPP: Float = 0.95f
    }
}

/** Thrown by [LiteRtBidetGemmaClient.runInference] when no Gemma model is available locally. */
class BidetModelNotReadyException(message: String) : IllegalStateException(message)
