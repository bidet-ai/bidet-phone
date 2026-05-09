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
 * Per-tab UI state for CLEAN / ANALYSIS / FORAI. Brief §6.
 *
 * The RAW tab does not use this — it streams [com.google.ai.edge.gallery.bidet.transcript.TranscriptAggregator]'s
 * `rawFlow` directly.
 */
sealed class TabState {

    /** Tab has never been generated for the current RAW. "Generate" button visible. */
    object Idle : TabState()

    /**
     * Inference in flight. Kept for compatibility with the old short path (no streaming
     * surface yet). New code paths emit [Streaming] so the user sees tokens as they arrive.
     */
    object Generating : TabState()

    /**
     * Streaming generation in flight. The UI re-renders [partialText] as tokens land.
     * [tokenCount] is the running count of streamed chunks (a coarse approximation of
     * tokens; LiteRT-LM emits one Message per decoded token-block — close enough for the
     * user-facing progress bar). [tokenCap] is the hard ceiling so the UI can render a
     * percent estimate.
     */
    data class Streaming(
        val partialText: String,
        val tokenCount: Int,
        val tokenCap: Int,
    ) : TabState()

    /**
     * Generation complete. UI shows [text] and a "Regenerate" button. [generatedAt] is millis
     * since epoch; tab cache stores it alongside the SHA-256 cache key
     * (rawSha + promptVersion + temperature) per brief §7.
     */
    data class Cached(val text: String, val generatedAt: Long) : TabState()

    /** Generation failed. UI shows an error message + retry button. */
    data class Failed(val message: String) : TabState()
}
