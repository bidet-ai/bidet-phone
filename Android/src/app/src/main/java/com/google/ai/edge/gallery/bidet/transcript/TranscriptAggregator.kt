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
 *
 * Bug-1 fix (2026-05-10): `onMutation` is invoked SYNCHRONOUSLY inside the mutex on every
 * successful merge. Persistence is the contract — the prior model relied on
 * `aggregator.rawFlow.collectLatest { dao.updateRawText(...) }` running on a separate scope,
 * which loses chunks that merge between `sessionPersistJob.cancel()` and `finalizeSessionRow`.
 * On a 30-min recording with 60 chunks at 2× realtime CPU, that window is significant. The
 * synchronous callback closes the gap: the DB is updated before append() returns to the
 * worker, so the next chunk's transcribe can start knowing the previous chunk is durable.
 */
class TranscriptAggregator(
    /**
     * Called inside the merge mutex AFTER `_rawFlow.value` is updated, with the new full
     * transcript text and the merged-chunk count. Intended for synchronous Room writes from
     * [com.google.ai.edge.gallery.bidet.service.RecordingService]. Production wires this to
     * `BidetSessionDao.updateRawTextAndMergedChunkCount`. Tests pass a no-op or a recording
     * lambda. Not called for [reset]; the lifecycle owner (RecordingService) creates a fresh
     * aggregator per session.
     */
    private val onMutation: suspend (String, Int) -> Unit = { _, _ -> },
    /**
     * Path B (2026-05-10): called per chunk merge with `(chunkIdx, justThisChunkText)` so the
     * lifecycle owner can enqueue background pre-cleaning of each chunk independently. The
     * full cumulative text is also still delivered via [onMutation] — the two callbacks are
     * independent. Failures in this callback are absorbed by [persistInsideLock] semantics —
     * a cleaner crash must not break the live transcription pipeline.
     */
    private val onChunkAppended: suspend (Int, String) -> Unit = { _, _ -> },
) {

    private val mutex = Mutex()

    private val _rawFlow = MutableStateFlow("")
    /** Live RAW transcript. Compose collects this with `collectAsStateWithLifecycle`. */
    val rawFlow: StateFlow<String> = _rawFlow.asStateFlow()

    /**
     * Track which chunk indices have been merged so re-deliveries (e.g. retry after worker
     * restart on app force-kill) don't double-count.
     */
    private val seenChunks: HashSet<Int> = HashSet()

    private val _mergedCountFlow = MutableStateFlow(0)
    /**
     * Bug-3 fix (2026-05-10): merged-chunk count for the live progress indicator. UI
     * compares this against the produced-chunk count from [com.google.ai.edge.gallery.bidet.audio.AudioCaptureEngine]
     * to render "Transcribing N of M chunks…" in History rows AND a banner in
     * SessionDetailScreen. Updated atomically with `_rawFlow` inside the mutex.
     */
    val mergedCountFlow: StateFlow<Int> = _mergedCountFlow.asStateFlow()

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
            _mergedCountFlow.value = seenChunks.size
            Log.w(
                "BidetTranscriptAggregator",
                "append MERGED idx=$idx newTotalLen=${merged.length}",
            )
            // Bug-1 fix: persist before releasing the lock. Failures are logged but do not
            // re-throw — a transient DB write failure must not surface as a transcription
            // failure to the user. The next merge will overwrite the row with the latest
            // text, so a single dropped write self-heals on the next chunk.
            persistInsideLock(merged, seenChunks.size, "append idx=$idx")
            // Path B (2026-05-10): hand the per-chunk text to the pre-cleaner. Wrapped in
            // try/catch so a cleaner crash never breaks the live transcription pipeline.
            try {
                onChunkAppended(idx, text.trim())
            } catch (t: Throwable) {
                Log.w("BidetTranscriptAggregator", "onChunkAppended threw at idx=$idx: ${t.message}", t)
            }
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
            _mergedCountFlow.value = seenChunks.size
            // Bug-1 fix: failure markers are also user-visible RAW content, so they must
            // persist on the same code path as successful merges.
            persistInsideLock(_rawFlow.value, seenChunks.size, "failureMarker idx=$idx")
        }
    }

    /** Clear all state — typically called when a new recording session starts. */
    suspend fun reset() {
        mutex.withLock {
            _rawFlow.value = ""
            seenChunks.clear()
            _mergedCountFlow.value = 0
        }
    }

    /**
     * Bug-1 fix (2026-05-10): per-mutation Room write, called from inside [mutex.withLock].
     * Catches and logs throwables — by contract this never propagates a failure to the
     * caller (the worker) because a DB hiccup must not break transcription. The next merge
     * will re-write the row with up-to-date content, so any single missed write self-heals.
     */
    private suspend fun persistInsideLock(text: String, mergedCount: Int, label: String) {
        try {
            onMutation(text, mergedCount)
        } catch (t: Throwable) {
            Log.w("BidetTranscriptAggregator", "onMutation threw at $label: ${t.message}", t)
        }
    }

    /** Snapshot the current text without subscribing — used by tab generators. */
    fun currentText(): String = _rawFlow.value
}
