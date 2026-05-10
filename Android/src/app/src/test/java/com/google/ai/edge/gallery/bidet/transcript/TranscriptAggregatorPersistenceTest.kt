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

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug-1 regression test (2026-05-10).
 *
 * Bug: [TranscriptAggregator] used to mutate `_rawFlow.value` inside its mutex but did NOT
 * persist to Room from the same code path. Persistence lived on a separate
 * `aggregator.rawFlow.collectLatest { dao.updateRawText(...) }` job in
 * [com.google.ai.edge.gallery.bidet.service.RecordingService.performAsyncStartup]. The
 * problem: stopRecording cancelled that persist job before the worker drained, so any
 * chunk that merged AFTER the user tapped Stop didn't get to Room — the in-memory
 * aggregator had the latest text, but the DB row was stale until finalize ran.
 *
 * Fix: TranscriptAggregator's constructor takes an `onMutation: suspend (text, mergedCount)
 * -> Unit` callback that fires INSIDE the mutex on every successful merge or failure
 * marker. RecordingService wires this to `BidetSessionDao.updateRawTextAndMergedChunkCount`.
 *
 * This test verifies that contract at the aggregator layer (no Android dependency, no
 * Room test in this file — Room is exercised by an instrumentation test). We use a
 * recording lambda to capture every onMutation invocation and assert:
 *
 *  1. Every successful append fires onMutation with the merged text + merged count.
 *  2. Every failure marker fires onMutation with the marker text + merged count.
 *  3. Duplicate-index appends do NOT fire onMutation (no-op when seenChunks rejects).
 *  4. `reset()` does NOT fire onMutation — the caller (RecordingService) creates a fresh
 *     aggregator per session, and the v0.2 schema doesn't have a "row was reset" concept.
 *  5. A throwing onMutation is absorbed — the next merge still succeeds (the contract is
 *     "DB hiccups never break transcription").
 *  6. The merged-count flow advances atomically with each merge so the History UI sees
 *     consistent N-of-M numbers.
 */
class TranscriptAggregatorPersistenceTest {

    private data class PersistCall(val text: String, val mergedCount: Int)

    @Test
    fun append_invokesOnMutation_withFullMergedText_andMergedCount() = runBlocking {
        val recorded = mutableListOf<PersistCall>()
        val aggregator = TranscriptAggregator(
            onMutation = { text, count -> recorded.add(PersistCall(text, count)) },
        )

        aggregator.append(idx = 0, startMs = 0L, text = "hello world")
        aggregator.append(idx = 1, startMs = 30_000L, text = "this is the second chunk")

        assertEquals("Two appends should fire two onMutation calls", 2, recorded.size)
        // First call: merged text equals first chunk's contents (no overlap to dedup against).
        assertEquals("hello world", recorded[0].text)
        assertEquals(1, recorded[0].mergedCount)
        // Second call: merged text contains both chunks (DedupAlgorithm may re-arrange / dedup).
        assertTrue(
            "Second persisted text must include 'second chunk' — got ${recorded[1].text}",
            recorded[1].text.contains("second chunk"),
        )
        assertEquals(2, recorded[1].mergedCount)
    }

    @Test
    fun appendFailureMarker_invokesOnMutation_withMarkerInText() = runBlocking {
        val recorded = mutableListOf<PersistCall>()
        val aggregator = TranscriptAggregator(
            onMutation = { text, count -> recorded.add(PersistCall(text, count)) },
        )

        aggregator.append(idx = 0, startMs = 0L, text = "good chunk")
        aggregator.appendFailureMarker(idx = 1)

        assertEquals(2, recorded.size)
        assertTrue(
            "Failure marker text must include the canonical '[chunk N transcription failed]' marker — got ${recorded[1].text}",
            recorded[1].text.contains("[chunk 1 transcription failed]"),
        )
        assertEquals(2, recorded[1].mergedCount)
    }

    @Test
    fun duplicateIdx_doesNotFireOnMutation() = runBlocking {
        val recorded = mutableListOf<PersistCall>()
        val aggregator = TranscriptAggregator(
            onMutation = { text, count -> recorded.add(PersistCall(text, count)) },
        )

        aggregator.append(idx = 0, startMs = 0L, text = "first")
        aggregator.append(idx = 0, startMs = 0L, text = "duplicate-redelivery") // SAME idx

        // Only the first append's onMutation should have fired. The duplicate is silently
        // rejected by seenChunks; persisting again would mean Room writes for no semantic
        // change, which over a 30-min session adds up.
        assertEquals(1, recorded.size)
        assertEquals("first", recorded[0].text)
    }

    @Test
    fun emptyText_isSilentlyDropped_noOnMutationFires() = runBlocking {
        val recorded = mutableListOf<PersistCall>()
        val aggregator = TranscriptAggregator(
            onMutation = { text, count -> recorded.add(PersistCall(text, count)) },
        )

        aggregator.append(idx = 0, startMs = 0L, text = "")

        // Empty transcribe results are dropped before the mutex even runs — they're noise,
        // not a real chunk. No persist call.
        assertTrue("Empty append must not fire onMutation; got $recorded", recorded.isEmpty())
        assertEquals(0, aggregator.mergedCountFlow.value)
    }

    @Test
    fun reset_doesNotFireOnMutation() = runBlocking {
        val recorded = mutableListOf<PersistCall>()
        val aggregator = TranscriptAggregator(
            onMutation = { text, count -> recorded.add(PersistCall(text, count)) },
        )

        aggregator.append(idx = 0, startMs = 0L, text = "text")
        recorded.clear() // baseline
        aggregator.reset()

        // Reset is a session-boundary signal, not a row-clearing signal. The caller
        // (RecordingService) creates a fresh aggregator per session; a fresh row gets a
        // fresh INSERT. There's no semantics for "blank out the row" in Bug-3's flow.
        assertTrue("reset must not fire onMutation; got $recorded", recorded.isEmpty())
    }

    @Test
    fun onMutationThrows_subsequentAppendsStillSucceed() = runBlocking {
        var firstCallSeen = false
        var secondCallSeen = false
        val aggregator = TranscriptAggregator(
            onMutation = { _, count ->
                if (count == 1) {
                    firstCallSeen = true
                    throw IllegalStateException("simulated DB write failure")
                }
                if (count == 2) {
                    secondCallSeen = true
                }
            },
        )

        aggregator.append(idx = 0, startMs = 0L, text = "first")
        // The throwing callback must not propagate — it's caught inside the aggregator's
        // private persistInsideLock helper. The next append should run cleanly.
        aggregator.append(idx = 1, startMs = 30_000L, text = "second")

        assertTrue("First call's throwing callback must have been invoked", firstCallSeen)
        assertTrue(
            "Second call must still fire even after the first onMutation threw — the contract is 'DB hiccups never break transcription'",
            secondCallSeen,
        )
        // The aggregator's own state must still reflect both merges — the in-memory
        // rawFlow + mergedCount are independent of the DB callback's success.
        assertEquals(2, aggregator.mergedCountFlow.value)
    }

    @Test
    fun mergedCountFlow_advancesInLockstepWithOnMutation() = runBlocking {
        val seenCounts = mutableListOf<Int>()
        val aggregator = TranscriptAggregator(
            onMutation = { _, count ->
                // The flow value MUST already reflect the new count by the time onMutation
                // runs — otherwise a History UI listener could read mergedCountFlow=N
                // while Room still has the row at N-1, drifting the "Transcribing N of M…"
                // numbers.
                seenCounts.add(count)
                assertEquals(
                    "mergedCountFlow.value must equal the count passed to onMutation",
                    count,
                    aggregator.mergedCountFlow.value,
                )
            },
        )

        aggregator.append(idx = 0, startMs = 0L, text = "a")
        aggregator.append(idx = 1, startMs = 30_000L, text = "b")
        aggregator.append(idx = 2, startMs = 60_000L, text = "c")

        assertEquals(listOf(1, 2, 3), seenCounts)
    }

    @Test
    fun defaultConstructor_isNoOpOnMutation() = runBlocking {
        // Existing call sites — BidetTabsViewModel.PLACEHOLDER_AGGREGATOR, the legacy
        // TranscriptionWorker tests — construct TranscriptAggregator with no args. The
        // default onMutation parameter must be a no-op so these sites keep compiling
        // without forcing every caller to pass an explicit lambda.
        val aggregator = TranscriptAggregator()

        // Should not throw — the default lambda accepts and ignores both args.
        aggregator.append(idx = 0, startMs = 0L, text = "no-op test")

        // State still updates normally even with the no-op callback.
        assertEquals("no-op test", aggregator.currentText())
        assertEquals(1, aggregator.mergedCountFlow.value)
        assertFalse("rawFlow must reflect the merged text", aggregator.currentText().isEmpty())
    }
}
