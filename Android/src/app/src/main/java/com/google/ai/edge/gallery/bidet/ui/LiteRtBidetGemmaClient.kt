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
 *  - [BidetModelProvider] owns the on-disk model file (download + verify lifecycle). This
 *    client lazily constructs the LiteRT-LM [Engine] on the first [runInference] call once
 *    [BidetModelProvider.isModelReady] returns true. The Engine is reused across tabs.
 *  - Each [runInference] call resets the [Conversation] so the four tabs do not contaminate
 *    each other's KV cache. The system prompt is wired via
 *    [ConversationConfig.systemInstruction] on [Engine.createConversation] (closer to
 *    LiteRT-LM grain than concatenating into the user content per the brief recommendation).
 *  - On any exception during [Engine] init, the engine reference is cleared so the next call
 *    re-attempts initialization rather than wedging into a half-built state.
 *
 * If [BidetModelProvider.isModelReady] returns false, [runInference] throws
 * [BidetModelNotReadyException]. The four tab composables surface this error and route the
 * user to [GemmaDownloadScreen] (handled at the [BidetMainScreen] level).
 */
@Singleton
class LiteRtBidetGemmaClient @Inject constructor(
    @Suppress("UNUSED_PARAMETER") @ApplicationContext private val context: Context,
    private val modelProvider: BidetModelProvider,
) : BidetGemmaClient {

    /** Engine + active conversation. Lazily constructed on first runInference call. */
    private data class EngineHandle(val engine: Engine, var conversation: Conversation)

    @Volatile private var handle: EngineHandle? = null
    private val initMutex = Mutex()

    @OptIn(ExperimentalApi::class)
    override suspend fun runInference(
        systemPrompt: String,
        userPrompt: String,
        maxOutputTokens: Int,
        temperature: Float,
    ): String {
        if (!modelProvider.isModelReady()) {
            throw BidetModelNotReadyException(
                "Gemma 4 model is not downloaded. Complete the first-run download before " +
                    "generating CLEAN/ANALYSIS/FORAI tabs."
            )
        }

        // Build / rebuild the conversation under a mutex so concurrent tab generations don't
        // race the LiteRT-LM Conversation lifecycle.
        val conversation = initMutex.withLock {
            val engine = ensureEngine(maxOutputTokens)
            // Each tab call is one-shot: close the previous conversation (if any) and create a
            // fresh one with the per-call systemInstruction + sampler config. The brief calls
            // out passing system as `systemInstruction` instead of pre-concatenating into the
            // user prompt; this implements that.
            val existing = handle?.conversation
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
            handle = handle?.copy(conversation = conv)
            conv
        }

        return suspendCancellableCoroutine { cont ->
            val sb = StringBuilder()
            val callback = object : MessageCallback {
                override fun onMessage(message: Message) {
                    sb.append(message.toString())
                }

                override fun onDone() {
                    if (cont.isActive) cont.resume(sb.toString())
                }

                override fun onError(throwable: Throwable) {
                    if (cont.isActive) cont.resumeWithException(throwable)
                }
            }
            try {
                conversation.sendMessageAsync(
                    Contents.of(listOf(Content.Text(userPrompt))),
                    callback,
                    emptyMap(),
                )
            } catch (t: Throwable) {
                if (cont.isActive) cont.resumeWithException(t)
            }

            cont.invokeOnCancellation {
                try {
                    conversation.cancelProcess()
                } catch (e: Throwable) {
                    Log.w(TAG, "cancelProcess threw on coroutine cancellation", e)
                }
            }
        }
    }

    /** Construct the LiteRT-LM Engine on first call. Idempotent under [initMutex]. */
    @OptIn(ExperimentalApi::class)
    private fun ensureEngine(maxOutputTokens: Int): Engine {
        handle?.let { return it.engine }
        val modelFile = modelProvider.getModelPath()
            ?: throw BidetModelNotReadyException("Model path is unavailable.")
        if (!modelFile.exists() || modelFile.length() == 0L) {
            throw BidetModelNotReadyException(
                "Model file does not exist at ${modelFile.absolutePath}"
            )
        }
        val cacheDir = context.getExternalFilesDir(null)?.absolutePath
        val config = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.GPU(),
            visionBackend = null,
            audioBackend = null,
            maxNumTokens = maxOutputTokens,
            cacheDir = cacheDir,
        )
        val engine = try {
            Engine(config).also { it.initialize() }
        } catch (t: Throwable) {
            Log.e(TAG, "Engine.initialize failed", t)
            throw BidetModelNotReadyException(
                "Failed to initialize Gemma 4 LiteRT-LM engine: ${t.message}"
            )
        }
        // Conversation is constructed per-call so we only need a placeholder slot here. We
        // will overwrite it on the next createConversation. Building a no-op Conversation up
        // front is awkward (createConversation is synchronous and there is no "empty" ctor),
        // so instead we leave the conversation slot lazily owned: handle is built once
        // ensureEngine returns and runInference creates the per-call conversation.
        handle = EngineHandle(engine = engine, conversation = engine.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(
                    topK = DEFAULT_TOPK,
                    topP = DEFAULT_TOPP.toDouble(),
                    temperature = DEFAULT_TEMPERATURE.toDouble(),
                ),
                systemInstruction = null,
                tools = emptyList(),
            )
        ))
        return engine
    }

    companion object {
        private const val TAG = "BidetGemmaClient"

        // Conservative defaults; brief locks temperature at 0.4 per call, topK/topP follow
        // upstream Gallery's defaults (DEFAULT_TOPK=40, DEFAULT_TOPP=0.95).
        private const val DEFAULT_TOPK: Int = 40
        private const val DEFAULT_TOPP: Float = 0.95f
        private const val DEFAULT_TEMPERATURE: Float = 0.4f
    }
}

/** Thrown by [LiteRtBidetGemmaClient.runInference] when no Gemma model is available locally. */
class BidetModelNotReadyException(message: String) : IllegalStateException(message)
