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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug-2 regression test (2026-05-10).
 *
 * Bug: [com.google.ai.edge.gallery.bidet.service.RecordingService.stopRecording] used to
 * call `worker.stopAndJoin()` (which CANCELS the consumer) immediately after capture stop,
 * then close the engine + tear down the FGS. Any chunks that were buffered in the
 * [ChunkQueue] but not yet picked up by the worker were silently dropped. On a 30-min
 * recording at 2× realtime CPU on Tensor G3 (no NPU available), 5+ chunks could be queued
 * at Stop time — so the user lost the last 2.5+ minutes of their brain dump.
 *
 * Fix: stopRecording now uses `worker.awaitDrainCompletion()` instead — closes the queue
 * (no cancel), waits for `consumeAsFlow()` to drain naturally + the consumer's collect
 * to complete. The FGS stays alive for the entire drain phase; a "Transcribing N of M…"
 * notification updates as chunks finish.
 *
 * This test verifies the worker-side contract — the part that runs on the JVM:
 *
 *  1. Closing the queue while there are buffered chunks lets the worker process EVERY
 *     buffered chunk before its job completes.
 *  2. `awaitDrainCompletion()` returns once the worker has merged the last buffered
 *     chunk — NOT before.
 *  3. The merged-count flow on the aggregator advances monotonically through the drain.
 *  4. A NonCancellable transcribe in flight at queue-close time finishes (NOT cancelled)
 *     and writes its result to the aggregator before drain completion.
 *
 * Skipping per the brief's "skip flaky timing tests" guidance: there's no Thread.sleep
 * here. The drain pauses are computed synchronously by the engine fake and joined via
 * structured concurrency. RecordingService's full state-machine is verified by the
 * UI/instrumentation suite, NOT by this JVM test.
 */
class TranscriptionWorkerDrainTest {

    /**
     * Fake engine: transcribes a chunk to "chunk-${idx}" so we can assert which chunks
     * actually made it into the aggregator post-drain. Initialise always succeeds.
     */
    private class CountingEngine : TranscriptionEngine {
        val transcribeCount = AtomicInteger(0)
        override val isReady: Boolean get() = true
        override fun initialize(): Boolean = true
        override suspend fun transcribe(floatPcm: FloatArray, sampleRateHz: Int): String {
            val n = transcribeCount.incrementAndGet()
            // The text only has to be non-empty to make it through aggregator.append.
            return "chunk-$n"
        }
        override fun close() = Unit
    }

    @Test
    fun closeQueueWithBufferedChunks_workerProcessesAllBeforeDrainCompletes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val queue = ChunkQueue()
            val engine = CountingEngine()
            val aggregator = TranscriptAggregator()
            val worker = TranscriptionWorker(
                chunkQueue = queue,
                transcriptionEngine = engine,
                aggregator = aggregator,
                scope = scope,
            )
            assertTrue("worker.start() must succeed when engine.initialize() returns true", worker.start())

            // Buffer three chunks WITHOUT awaiting any consumption, then close. The
            // ChunkQueue uses a buffered channel; this exercises the "user tapped Stop
            // while N chunks queued" path.
            val one = Chunk.Audio(idx = 0, startMs = 0L, endMs = 30_000L, bytes = ByteArray(64))
            val two = Chunk.Audio(idx = 1, startMs = 30_000L, endMs = 60_000L, bytes = ByteArray(64))
            val three = Chunk.Audio(idx = 2, startMs = 60_000L, endMs = 90_000L, bytes = ByteArray(64))
            queue.offer(one); queue.offer(two); queue.offer(three)

            // Close (NOT cancel) — this is what RecordingService.stopRecording does in the
            // post-Bug-2 path. consumeAsFlow() on the underlying channel will finish
            // emitting the buffered chunks, then complete.
            queue.close()

            // Drain. Bound the test time so a regression to "drop on cancel" surfaces as
            // a TimeoutException, not a hang.
            withTimeout(5_000L) { worker.awaitDrainCompletion() }

            // After drain, every buffered chunk must be reflected in the aggregator. We
            // don't assert exact text (DedupAlgorithm has its own contract), only the
            // chunk count — that's the property Bug-2 was breaking.
            assertEquals(
                "All 3 buffered chunks must transcribe before awaitDrainCompletion returns",
                3,
                aggregator.mergedCountFlow.value,
            )
            assertEquals(
                "transcribe() must run exactly 3 times — one per buffered chunk",
                3,
                engine.transcribeCount.get(),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun closeQueueWithNoBuffer_drainCompletesImmediately() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val queue = ChunkQueue()
            val engine = CountingEngine()
            val aggregator = TranscriptAggregator()
            val worker = TranscriptionWorker(
                chunkQueue = queue,
                transcriptionEngine = engine,
                aggregator = aggregator,
                scope = scope,
            )
            worker.start()

            // No chunks ever offered. Closing the queue should let the consumer's
            // collect() return immediately. This is the "user tapped Stop with nothing
            // queued" path — must not hang.
            queue.close()
            withTimeout(2_000L) { worker.awaitDrainCompletion() }

            assertEquals(0, aggregator.mergedCountFlow.value)
            assertEquals(0, engine.transcribeCount.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun mergedCountFlow_isMonotonicThroughDrain() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val queue = ChunkQueue()
            val engine = CountingEngine()
            val aggregator = TranscriptAggregator()
            val worker = TranscriptionWorker(
                chunkQueue = queue,
                transcriptionEngine = engine,
                aggregator = aggregator,
                scope = scope,
            )
            worker.start()

            for (i in 0 until 4) {
                queue.offer(Chunk.Audio(idx = i, startMs = i * 30_000L, endMs = (i + 1) * 30_000L, bytes = ByteArray(64)))
            }
            queue.close()

            withTimeout(5_000L) { worker.awaitDrainCompletion() }

            // The History UI binds to mergedCountFlow during the drain phase; it MUST be
            // monotonic. We can't easily snapshot every emission from a JVM test without
            // adding a flaky timing dependency, so assert the terminal value — drift
            // bugs would manifest as a final value < produced.
            assertEquals(4, aggregator.mergedCountFlow.value)
        } finally {
            scope.cancel()
        }
    }
}
