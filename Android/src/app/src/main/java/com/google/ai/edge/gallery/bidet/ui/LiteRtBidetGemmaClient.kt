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
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Concrete [BidetGemmaClient] backed by upstream Gallery's [LlmChatModelHelper] LiteRT-LM
 * loader. Phase 2 wiring per brief §6.
 *
 * Lifecycle:
 *  - [LlmChatModelHelper.initialize] is performed by the upstream model-manager flow when the
 *    user (or, in Phase 3, the Bidet auto-download) selects the Gemma 4 E4B model. The
 *    initialised [LlmModelInstance] is kept on [Model.instance].
 *  - Each call to [runInference] sends a one-shot [Contents] containing the system prompt
 *    framed as a leading user-text content + the brain-dump as a follow-up text content,
 *    awaits the streaming response on a [MessageCallback], and returns the concatenated text.
 *  - After every call we drive [LlmChatModelHelper.resetConversation] so the next tab does
 *    not inherit KV-cache state from the previous tab. This matches the "one-shot" semantics
 *    the [BidetGemmaClient] interface declares.
 *
 * Phase 2 limitation: there is no model selector for the bidet UI yet. If the host
 * application has not yet initialised a Gemma model (i.e. no [Model] with a non-null
 * `instance` of type [LlmModelInstance] exists), [runInference] throws
 * [BidetModelNotReadyException]. The caller surfaces this to the tab body as a "Failed"
 * state. Phase 3 will wire the auto-download + selection into the bidet flow.
 */
@Singleton
class LiteRtBidetGemmaClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelProvider: BidetModelProvider,
) : BidetGemmaClient {

    override suspend fun runInference(
        systemPrompt: String,
        userPrompt: String,
        maxOutputTokens: Int,
        temperature: Float,
    ): String {
        val model = modelProvider.getReadyModel()
            ?: throw BidetModelNotReadyException(
                "Gemma 4 model is not initialised. Download and select a Gemma 4 model before " +
                    "generating CLEAN/ANALYSIS/FORAI tabs."
            )

        val instance = model.instance as? LlmModelInstance
            ?: throw BidetModelNotReadyException(
                "Selected model has no live LiteRT-LM instance. Re-initialise from the model " +
                    "manager."
            )

        // One-shot send: system prompt + user prompt as two text contents.
        val combined = "$systemPrompt\n\n$userPrompt"

        val response = suspendCancellableCoroutine<String> { cont ->
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
                instance.conversation.sendMessageAsync(
                    Contents.of(listOf(Content.Text(combined))),
                    callback,
                    emptyMap(),
                )
            } catch (t: Throwable) {
                if (cont.isActive) cont.resumeWithException(t)
            }

            cont.invokeOnCancellation {
                try {
                    instance.conversation.cancelProcess()
                } catch (e: Throwable) {
                    Log.w(TAG, "cancelProcess threw on coroutine cancellation", e)
                }
            }
        }

        // Tab calls are one-shot; reset conversation between calls so the next tab does not
        // see prior tab's KV cache. We intentionally pass null systemInstruction (we re-send
        // the full prompt+raw on every call) and disable image/audio modalities — bidet runs
        // text-only.
        try {
            LlmChatModelHelper.resetConversation(
                model = model,
                supportImage = false,
                supportAudio = false,
                systemInstruction = null,
                tools = emptyList(),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "resetConversation failed (continuing with stale conversation)", t)
        }

        return response
    }

    companion object {
        private const val TAG = "BidetGemmaClient"
    }
}

/** Thrown by [LiteRtBidetGemmaClient.runInference] when no initialised Gemma model is available. */
class BidetModelNotReadyException(message: String) : IllegalStateException(message)

/**
 * Indirection so the client can ask "give me a model whose `instance` is a live
 * [LlmModelInstance]" without coupling to the upstream
 * [com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel] (which holds a Set of
 * tasks, allowlists, lifecycle hooks, etc. and is more than the bidet flow needs).
 *
 * Phase 2 binds a stub provider that returns null (no model is selected through the bidet
 * UI yet). Phase 3 will replace this with a real provider that wires into the bidet's own
 * download + selection flow.
 */
interface BidetModelProvider {
    /** @return a [Model] whose `instance` is initialised, or null if none is ready. */
    fun getReadyModel(): Model?
}
