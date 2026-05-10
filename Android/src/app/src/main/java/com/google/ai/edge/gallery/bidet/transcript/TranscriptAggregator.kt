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

package com.google.ai.edge.gallery.bidet.transcript

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Maintains the running RAW transcript built from successive chunk transcriptions.
 *
 * Per brief §5: holds a [MutableStateFlow]&lt;String&gt; that the RAW tab subscribes to,
 * accepts [append] / [appendFailureMarker] / [reset] from [TranscriptionWorker], merges each
 * incoming chunk via [DedupAlgorithm.mergeWithDedup].
 *
 * Thread safety: [Mutex] serializes all mutations. The single-threaded [TranscriptionWorker]
 * is the only writer; the mutex defends against accidental concurrent UI-side `reset()`.
 */
class TranscriptAggregator {

    private val mutex = Mutex()

    private val _rawFlow = MutableStateFlow("")
    /** Live RAW transcript. Compose collects this with `collectAsStateWithLifecycle`. */
    val rawFlow: StateFlow<String> = _rawFlow.asStateFlow()

    /**
     * Track which chunk indices have been merged so re-deliveries (e.g. retry after worker
     * restart on app force-kill) don't double-count.
     */
    private val seenChunks: HashSet<Int> = HashSet()

    /**
     * Merge a successful Whisper transcription for chunk [idx] into the running text.
     *
     * @param idx chunk index from [com.google.ai.edge.gallery.bidet.chunk.Chunk.Audio.idx].
     * @param startMs window-start ms (currently unused by the merger; kept for future
     *   chunk-aware UI features such as scrubbing).
     * @param text the Whisper transcription for this chunk. Empty strings are silently dropped.
     */
    suspend fun append(idx: Int, startMs: Long, text: String) {
        Log.w(
            "BidetTranscriptAggregator",
            "append ENTER idx=$idx textLen=${text.length} empty=${text.isEmpty()}",
        )
        if (text.isEmpty()) return
        mutex.withLock {
            if (!seenChunks.add(idx)) {
                Log.w("BidetTranscriptAggregator", "append SKIP_DUP idx=$idx")
                return // already merged
            }
            val merged = DedupAlgorithm.mergeWithDedup(_rawFlow.value, text.trim())
            _rawFlow.value = merged
            Log.w(
                "BidetTranscriptAggregator",
                "append MERGED idx=$idx newTotalLen=${merged.length}",
            )
        }
    }

    /**
     * Splice a "[chunk N transcription failed]" marker into the running transcript so the user
     * sees the gap explicitly rather than an unexplained jump.
     */
    suspend fun appendFailureMarker(idx: Int) {
        mutex.withLock {
            if (!seenChunks.add(idx)) return
            val marker = "[chunk $idx transcription failed]"
            val cur = _rawFlow.value
            _rawFlow.value = if (cur.isEmpty()) marker else "$cur $marker"
        }
    }

    /** Clear all state — typically called when a new recording session starts. */
    suspend fun reset() {
        mutex.withLock {
            _rawFlow.value = ""
            seenChunks.clear()
        }
    }

    /** Snapshot the current text without subscribing — used by tab generators. */
    fun currentText(): String = _rawFlow.value
}
