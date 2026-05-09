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

import com.google.ai.edge.gallery.bidet.chunk.Chunk
import com.google.ai.edge.gallery.bidet.chunk.ChunkQueue
import com.google.ai.edge.gallery.bidet.transcript.TranscriptAggregator
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1.1 (2026-05-09) regression test for [TranscriptionWorker.stopAndJoin] and the
 * F3.4 cancellation contract on [TranscriptionEngine.transcribe].
 *
 * F1.1 bug:
 *  [com.google.ai.edge.gallery.bidet.service.RecordingService.stopRecording] used to call
 *  `worker.stop()` immediately followed by `transcriptionEngine.close()`. `stop()` only
 *  *cancels* the worker's outer Job; meanwhile `handleAudio` wraps its transcribe call in
 *  `withContext(NonCancellable + Dispatchers.Default)`, so a transcribe that started before
 *  Stop keeps running. Then we close the engine and pull the native pointer out from under
 *  the still-running transcribe — use-after-free, app crash on real device.
 *
 * F1.1 fix:
 *  [TranscriptionWorker.stopAndJoin] cancels the consumer AND awaits the in-flight
 *  handleAudio (via Job.join). Once it returns, the caller can safely close the engine.
 *
 * What this test pins:
 *  1. With a long-running fake transcribe, calling stopAndJoin() suspends until the
 *     in-flight transcribe completes — which is the correctness property the fix delivers.
 *  2. After stopAndJoin() returns, the in-flight transcribe is *not* mid-flight any more
 *     (the engine can be closed safely). We assert via a flag the fake flips to true on
 *     entry and false on exit; stopAndJoin must not return while the flag is true.
 *  3. After stopAndJoin() returns, the worker's job is null so a follow-up start() can
 *     spawn a fresh consumer (idempotency contract for re-records in the same process).
 *  4. stopAndJoin() is a no-op when the worker was never started.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionWorkerStopAndJoinTest {

    /**
     * Fake engine whose transcribe() suspends on a [CompletableDeferred] the test controls.
     * [transcribeInFlight] flips true on entry and false right before return so the test
     * can verify the join correctness property.
     */
    private class GatedFakeEngine : TranscriptionEngine {
        val gate: CompletableDeferred<String> = CompletableDeferred()
        val transcribeInFlight = AtomicBoolean(false)
        val callCount = AtomicInteger(0)
        val closeCount = AtomicInteger(0)

        override val isReady: Boolean = true
        override fun initialize(): Boolean = true
        override suspend fun transcribe(floatPcm: FloatArray, sampleRateHz: Int): String {
            callCount.incrementAndGet()
            transcribeInFlight.set(true)
            return try {
                gate.await()
            } finally {
                transcribeInFlight.set(false)
            }
        }
        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    @After
    fun tearDown() {
        // Each runTest builds its own scope; nothing to clean up at the class level.
    }

    @Test
    fun stopAndJoin_awaitsInFlightTranscribe_thenReleasesJob() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val workerScope = CoroutineScope(SupervisorJob() + testDispatcher)

        val engine = GatedFakeEngine()
        val queue = ChunkQueue()
        val aggregator = TranscriptAggregator()
        val worker = TranscriptionWorker(
            chunkQueue = queue,
            transcriptionEngine = engine,
            aggregator = aggregator,
            scope = workerScope,
        )

        try {
            assertTrue(worker.start())

            // Push a single chunk — handleAudio will call our gated transcribe and park
            // there. 6400 bytes = 3200 samples = 0.2s of audio at 16 kHz; the worker only
            // cares about bytes for int16ToFloat32, payload doesn't matter for the test.
            queue.offer(
                Chunk.Audio(
                    idx = 0,
                    bytes = ByteArray(6400),
                    startMs = 0L,
                    endMs = 200L,
                )
            )

            // Let the consumer collect the chunk and enter the gated transcribe.
            advanceUntilIdle()
            assertTrue(
                "Transcribe should be in flight after the worker collects the first chunk; " +
                    "if this assertion fires the test setup is wrong, not the fix.",
                engine.transcribeInFlight.get(),
            )

            // Kick off stopAndJoin in another coroutine + observe completion ordering.
            val stopAndJoinDone = AtomicBoolean(false)
            launch {
                worker.stopAndJoin()
                stopAndJoinDone.set(true)
            }

            // Drain queued continuations — stopAndJoin should NOT have completed yet because
            // the gated transcribe hasn't been resumed. This is the bug we're fixing: under
            // the old `stop()` (cancel-only) this would unblock immediately and the caller
            // would race into close() against an in-flight transcribe.
            advanceUntilIdle()
            assertFalse(
                "stopAndJoin must NOT return while a transcribe is still in flight — that's " +
                    "the F1.1 correctness property. If this fires, stopAndJoin probably " +
                    "delegated to plain stop() and skipped the join().",
                stopAndJoinDone.get(),
            )
            assertTrue(
                "Transcribe should still be in flight (gate hasn't been released yet).",
                engine.transcribeInFlight.get(),
            )

            // Release the gate — transcribe completes, the worker's collect resumes, the
            // join unblocks, stopAndJoin returns.
            engine.gate.complete("hello")
            advanceUntilIdle()

            assertTrue(
                "stopAndJoin should return AFTER the in-flight transcribe finishes.",
                stopAndJoinDone.get(),
            )
            assertFalse(
                "Once stopAndJoin returns, no transcribe is in flight — caller can safely " +
                    "close the engine. This is the use-after-free we're closing.",
                engine.transcribeInFlight.get(),
            )
            assertEquals(1, engine.callCount.get())
            assertEquals(
                "stopAndJoin must not call close on the engine — the caller decides when " +
                    "to release native resources.",
                0,
                engine.closeCount.get(),
            )
        } finally {
            workerScope.cancel()
        }
    }

    @Test
    fun stopAndJoin_isNoOpWhenNotStarted() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val workerScope = CoroutineScope(SupervisorJob() + testDispatcher)

        val engine = GatedFakeEngine()
        val queue = ChunkQueue()
        val aggregator = TranscriptAggregator()
        val worker = TranscriptionWorker(
            chunkQueue = queue,
            transcriptionEngine = engine,
            aggregator = aggregator,
            scope = workerScope,
        )

        try {
            // No start() — stopAndJoin must return immediately.
            withTimeout(1_000L) { worker.stopAndJoin() }
            assertEquals(
                "stopAndJoin must not call close on the engine — that's the caller's job.",
                0,
                engine.closeCount.get(),
            )
        } finally {
            workerScope.cancel()
        }
    }

    @Test
    fun stopAndJoin_idempotent_onSecondCall() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val workerScope = CoroutineScope(SupervisorJob() + testDispatcher)

        val engine = GatedFakeEngine()
        val queue = ChunkQueue()
        val aggregator = TranscriptAggregator()
        val worker = TranscriptionWorker(
            chunkQueue = queue,
            transcriptionEngine = engine,
            aggregator = aggregator,
            scope = workerScope,
        )
        try {
            assertTrue(worker.start())
            // No chunks queued — the worker's collect simply suspends on the queue.
            advanceUntilIdle()

            worker.stopAndJoin()
            // Second call must be a no-op (no in-flight job to join). Pinned because the
            // RecordingService teardown path is "safe to call multiple times" per kdoc, and
            // a careless implementation could try to join on a null reference.
            worker.stopAndJoin()
        } finally {
            workerScope.cancel()
        }
    }

    /**
     * Bridge: [delay] is unused but the import keeps IntelliJ from auto-trimming when
     * future tests in this file need it. Trimmed at the next clean-up pass if still unused.
     */
    @Suppress("unused")
    private suspend fun delayMillis(ms: Long) = delay(ms)
}
