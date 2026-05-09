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

/**
 * Thin abstraction over Gemma 4 E4B inference for the on-demand tab generators.
 *
 * The tab content composables don't know — and don't need to know — about LiteRT-LM's
 * Engine / Conversation surface. They hand a system prompt + user prompt to this client and
 * receive a string. Concrete implementations live wherever LlmChatModelHelper-style state is
 * kept (typically in a Hilt-bound singleton); a stub stand-in lives next to this interface
 * for use in previews + early-development builds while the full LiteRT integration is
 * finalized in a follow-up PR.
 *
 * Per brief §6: after each call the implementation MUST `LlmChatModelHelper.resetConversation`
 * so the four tabs do not contaminate each other's KV cache. The interface enforces this by
 * making each call a one-shot — no streaming session, no multi-turn state.
 */
interface BidetGemmaClient {

    /**
     * Run a one-shot Gemma 4 inference.
     *
     * @param systemPrompt the locked v1 prompt for one of the four tabs (read from
     *   `assets/prompts/{clean,analysis,forai}.txt` or, in debug builds, the DataStore override).
     * @param userPrompt the current RAW transcript — the user's brain-dump.
     * @param maxOutputTokens hard cap on output length; default 16384 (v0.2 bump from 1024).
     *   The 1024 default was the upstream Gallery value and choked on real RAW inputs over
     *   ~1 minute (LiteRT-LM error: "input token IDs are too long … 1064 ≥ 1024"). Gemma 4
     *   E4B's context window is 128k so 16384 is still conservative.
     * @param temperature sampling temperature; defaults to the prompt-locked value of 0.4.
     * @return the model's response text.
     * @throws Exception any inference-side error. Caller surfaces via Cached(text) → toast or
     *   fallback message on the tab.
     */
    suspend fun runInference(
        systemPrompt: String,
        userPrompt: String,
        maxOutputTokens: Int = 16384,
        temperature: Float = 0.4f,
    ): String
}
