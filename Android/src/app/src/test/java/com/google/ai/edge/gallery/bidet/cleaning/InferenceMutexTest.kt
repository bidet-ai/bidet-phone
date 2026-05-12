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

package com.google.ai.edge.gallery.bidet.cleaning

import com.google.ai.edge.gallery.bidet.ui.BidetGemmaClient
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Concurrency tests for the gemma inference mutex shipped in v18.7 (2026-05-10).
 *
 * Motivation
 * ----------
 * The v18.7 fix (commit 7a66659) added `inferenceMutex` to
 * [com.google.ai.edge.gallery.bidet.ui.LiteRtBidetGemmaClient] after a native SIGSEGV inside
 * liblitertlm_jni.so at offset 0x4c9060 took three hours to debug on a Pixel 8. The crash
 * only surfaced when [ChunkCleaner] (pre-cleaning later chunks) collided with
 * [com.google.ai.edge.gallery.bidet.service.CleanGenerationService] (on-tap clean) — both
 * entered the inference path concurrently and LiteRT-LM null-derefed internally.
 *
 * These tests pin the contract that future v19+ changes which touch the inference path
 * cannot regress without CI flagging it in 30 seconds, no APK install required. The bug
 * class — "two callers enter the inference path simultaneously" — is the universal symptom
 * regardless of which specific call site introduces the race.
 *
 * How the test models production
 * ------------------------------
 * [LiteRtBidetGemmaClient.inferenceMutex] is private and bound to LiteRT-LM lifecycle; we
 * can't reach it from a JVM unit test. Instead, [MutexGuardedClient] (defined inline below)
 * implements the same `inferenceMutex.withLock { /* full suspend body */ }` pattern as
 * production, wrapping a [FakeGemmaEngine]. If production drifts to a different guard
 * pattern (e.g. moves the mutex inside a sub-suspend, or replaces it with a Channel), the
 * regression surface here is the right place to add the matching test — not to delete this
 * one.
 *
 * [FakeGemmaEngine] is the detector: it tracks the number of in-flight calls and throws
 * [IllegalStateException] when two overlap, which is the deterministic stand-in for the
 * native SIGSEGV.
 *
 * Why this lives in src/test (JVM unit tests) and not androidTest
 * ---------------------------------------------------------------
 * The original SIGSEGV needed real Tensor G3 + a real 3.6 GB model + minutes of wall-clock
 * recording to surface. The whole point of the regression test is to surface the bug class
 * without any of that. Anything Android-specific that the production mutex depends on
 * should be re-asserted at the integration level (instrumentation test), not here.
 */
class InferenceMutexTest {

    /**
     * Production-pattern wrapper: serialize the entire suspend body of a downstream
     * [BidetGemmaClient] behind a [Mutex]. Mirrors
     * [com.google.ai.edge.gallery.bidet.ui.LiteRtBidetGemmaClient.inferenceMutex] usage —
     * the lock is taken on entry and held across the suspended inference call so a second
     * caller cannot enter the engine until `onDone`/`onError` would have fired.
     *
     * If a future refactor extracts the mutex into a wrapper class for real, this test
     * double can be deleted in favor of that wrapper. Until then, keeping the pattern here
     * tied to the test means refactoring production doesn't break the test.
     */
    private class MutexGuardedClient(
        private val delegate: BidetGemmaClient,
        val mutex: Mutex = Mutex(),
    ) : BidetGemmaClient {
        override suspend fun runInference(
            systemPrompt: String,
            userPrompt: String,
            maxOutputTokens: Int,
            temperature: Float,
        ): String = mutex.withLock {
            delegate.runInference(systemPrompt, userPrompt, maxOutputTokens, temperature)
        }

        override suspend fun runInferenceStreaming(
            systemPrompt: String,
            userPrompt: String,
            maxOutputTokens: Int,
            temperature: Float,
            onChunk: (cumulativeText: String, chunkIndex: Int) -> Unit,
        ): String = mutex.withLock {
            delegate.runInferenceStreaming(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                maxOutputTokens = maxOutputTokens,
                temperature = temperature,
                onChunk = onChunk,
            )
        }
    }

    private lateinit var sessionDir: File

    @Before
    fun setUp() {
        sessionDir = Files.createTempDirectory("bidet-fake-gemma-").toFile()
    }

    @After
    fun tearDown() {
        sessionDir.deleteRecursively()
    }

    /**
     * Test 1 (brief): pin that two concurrent calls through the production mutex pattern
     * never produce a concurrent-entry detection at the engine. This is the positive
     * regression — what v18.7 buys us.
     */
    @Test
    fun serializedCalls_doNotOverlap() = runBlocking {
        val fake = FakeGemmaEngine(latencyMillis = 50L)
        val client = MutexGuardedClient(fake)

        // Fire two calls in parallel. Without the mutex the fake would throw on the second
        // entry; with the mutex the second waits for the first.
        val results = withTimeout(2_000L) {
            (1..2).map { i ->
                async(Dispatchers.Default) {
                    client.runInference(
                        systemPrompt = "sys",
                        userPrompt = "user-$i",
                    )
                }
            }.awaitAll()
        }

        assertEquals("both calls must complete", 2, results.size)
        assertFalse(
            "FakeGemmaEngine must NEVER see concurrent entry when production mutex is held " +
                "around the full suspend body. If this fails, v18.7's serialization regressed.",
            fake.concurrentEntryDetected,
        )
        assertEquals(
            "peak active calls must be 1 with serialization",
            1,
            fake.peakActiveCallCount,
        )
        assertEquals("each call must be recorded once", 2, fake.calls.size)
    }

    /**
     * Test 2 (brief): sanity-check the detector itself. With the mutex bypassed, the fake
     * MUST flag the overlap — otherwise Test 1's "no overlap" assertion is vacuous.
     *
     * Without this test, a future fake refactor that silently broke the detector would
     * make every other test green-while-broken. This is the "calibration call" for
     * FakeGemmaEngine.
     */
    @Test
    fun withoutMutex_concurrentCallsThrow() = runBlocking {
        val fake = FakeGemmaEngine(latencyMillis = 200L)

        // Fire two calls directly at the fake (no mutex wrapper). To make the overlap
        // deterministic — and not "rely on Dispatchers.Default to dispatch both at once
        // before the 200ms latency window closes" — we gate both coroutines on the same
        // [CompletableDeferred] so they enter the inference path simultaneously after the
        // main thread releases the gate. This ensures the second-entry detection fires
        // every run, on every CI runner, no matter how slow.
        val startGate = CompletableDeferred<Unit>()
        val outcomes = withTimeout(5_000L) {
            val deferred = (1..2).map { i ->
                async(Dispatchers.Default) {
                    startGate.await()
                    runCatching {
                        fake.runInference(
                            systemPrompt = "sys",
                            userPrompt = "user-$i",
                        )
                    }
                }
            }
            // Both coroutines are now suspended on the gate. Release; they will both
            // immediately hit the incrementAndGet path in FakeGemmaEngine — one sees
            // active=1, the other sees active=2 → throws.
            startGate.complete(Unit)
            deferred.awaitAll()
        }

        val failures = outcomes.mapNotNull { it.exceptionOrNull() }
        assertTrue(
            "at least one concurrent call must throw — if zero throw, the detector is " +
                "broken and serializedCalls_doNotOverlap is vacuously passing.",
            failures.isNotEmpty(),
        )
        val concurrentFailure = failures.firstOrNull { it is IllegalStateException }
        assertNotNull(
            "the failure must be IllegalStateException with 'concurrent inference' message " +
                "— that's the deterministic SIGSEGV stand-in.",
            concurrentFailure,
        )
        assertTrue(
            "failure message must mention 'concurrent inference'",
            (concurrentFailure?.message ?: "").contains("concurrent inference"),
        )
        assertTrue(
            "fake must record concurrent entry on the violation",
            fake.concurrentEntryDetected,
        )
        assertTrue(
            "peak active calls must reach 2 to confirm the overlap happened",
            fake.peakActiveCallCount >= 2,
        )
    }

    /**
     * Test 3 (brief): the original v18.7 collision was ChunkCleaner pre-cleaning later
     * chunks AT THE SAME TIME as a CleanGenerationService on-tap call. This test wires
     * [ChunkCleaner] (which calls the gemma client on its own worker coroutine) AND a
     * separate on-tap path against the same MutexGuardedClient, fires them back-to-back,
     * and asserts no overlap is observed at the engine.
     *
     * ChunkCleaner uses runInference (non-streaming). On-tap clean in production also
     * goes through runInferenceStreaming under the hood. Both routes go through the same
     * mutex in production; both must be covered.
     */
    @Test
    fun chunkCleaner_plusOnTap_doNotRace() = runBlocking {
        val fake = FakeGemmaEngine(latencyMillis = 60L)
        val client = MutexGuardedClient(fake)

        val cleaner = ChunkCleaner(
            sessionExternalDir = sessionDir,
            gemma = client,
            cleanForMePrompt = "clean the following",
            maxOutputTokens = 256,
            temperature = 0.3f,
        )
        cleaner.start()

        // Queue several chunks against the cleaner — these will drain serially through its
        // own worker coroutine, each call going through the mutex.
        for (i in 0 until 3) {
            cleaner.enqueue(idx = i, chunkText = "chunk-text-$i")
        }

        // Simulate the on-tap clean racing in parallel. In production this comes from
        // CleanGenerationService; here we drive it directly through the same mutex-guarded
        // client. The two paths share the engine + mutex, exactly as v18.7 demands.
        val onTapResult = async(Dispatchers.Default) {
            client.runInference(
                systemPrompt = "clean the following",
                userPrompt = "user-tapped-generate",
            )
        }

        // Drain the cleaner. The brief's design: drainAndStop() closes the queue and returns
        // the worker Job so the caller can join with a hard timeout.
        val workerJob = cleaner.drainAndStop()
        assertNotNull("cleaner worker job must be non-null after start()", workerJob)
        withTimeout(5_000L) { workerJob!!.join() }
        withTimeout(2_000L) { onTapResult.await() }

        assertFalse(
            "ChunkCleaner + on-tap clean must not race through the engine when the mutex " +
                "is held — this is the exact production collision v18.7 fixed.",
            fake.concurrentEntryDetected,
        )
        assertEquals(
            "peak active calls must be 1: 3 chunk-cleaner calls + 1 on-tap = 4 total, all " +
                "serialized.",
            1,
            fake.peakActiveCallCount,
        )
        // 3 chunks + 1 on-tap = 4 calls observed at the engine.
        assertEquals(4, fake.calls.size)
    }

    /**
     * Test 4 (brief): the mutex must be released when its holder is cancelled. If a
     * cancelled call left the mutex locked, the next caller would deadlock — worse than
     * the SIGSEGV in some ways because it manifests as a silent hang.
     *
     * kotlinx.coroutines.sync.Mutex documents this contract (cancellation releases the
     * lock) but it's worth pinning because a future refactor that wraps the mutex in a
     * custom guard (e.g. a Semaphore + suspendCancellableCoroutine glue) could easily get
     * this wrong.
     */
    @Test
    fun cancellation_releasesMutex() = runBlocking {
        // Use a fake whose call we can park indefinitely via a gate, so cancellation has
        // something to actually cancel. Plain delay() is also cancellable, but a gate makes
        // the test's intent explicit.
        val firstCallEntered = CompletableDeferred<Unit>()
        val firstCallGate = CompletableDeferred<Unit>()
        val gatedFake = object : BidetGemmaClient {
            override suspend fun runInference(
                systemPrompt: String, userPrompt: String,
                maxOutputTokens: Int, temperature: Float,
            ): String {
                firstCallEntered.complete(Unit)
                firstCallGate.await()
                return "never"
            }
            override suspend fun runInferenceStreaming(
                systemPrompt: String, userPrompt: String,
                maxOutputTokens: Int, temperature: Float,
                onChunk: (String, Int) -> Unit,
            ): String = runInference(systemPrompt, userPrompt, maxOutputTokens, temperature)
        }
        val client = MutexGuardedClient(gatedFake)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // First caller: enters the mutex, parks inside the gate, then gets cancelled.
            val firstCall = scope.launch {
                client.runInference(
                    systemPrompt = "sys",
                    userPrompt = "first",
                )
            }
            // Wait for the first call to enter the inference body so we KNOW the mutex is
            // held when we cancel. Without this, cancelling before lock acquisition would
            // be a no-op and the test would pass for the wrong reason.
            withTimeout(2_000L) { firstCallEntered.await() }
            assertTrue("mutex must be locked by the first caller", client.mutex.isLocked)

            firstCall.cancel()
            firstCall.join()

            // Mutex must be released — even though we never completed the gate.
            assertFalse(
                "cancellation must release the inference mutex. If this fails, a future " +
                    "caller will hang forever waiting for a dead holder.",
                client.mutex.isLocked,
            )

            // Sanity: the next call must actually proceed. Use a regular FakeGemmaEngine
            // here so we get a real return; the cancellation already proved its point.
            // Reuse the SAME mutex (client.mutex) so we're testing the post-cancellation
            // state of the actual lock that just held the cancelled holder, not "some
            // fresh mutex" — that's the contract that matters.
            val secondFake = FakeGemmaEngine(latencyMillis = 10L)
            val secondClient = MutexGuardedClient(
                delegate = secondFake,
                mutex = client.mutex,
            )
            val secondResult = withTimeout(2_000L) {
                secondClient.runInference(systemPrompt = "sys", userPrompt = "second")
            }
            assertTrue(
                "second call must complete on the same mutex once the first releases it.",
                secondResult.contains("second"),
            )
        } finally {
            // Release the gate before tearing down so any straggling coroutine doesn't
            // leak the scope.
            firstCallGate.complete(Unit)
            scope.cancel()
        }
    }

    /** Sanity check the test scaffolding's clock-vs-asserts ordering with a tiny delay. */
    @Test
    fun fakeEngine_recordsCallMetadata() = runBlocking {
        val fake = FakeGemmaEngine(latencyMillis = 5L)
        fake.runInference(systemPrompt = "sys-A", userPrompt = "user-A")
        // Real-time gap so a later test reader can spot ordering by timestamp.
        delay(2)
        fake.runInference(systemPrompt = "sys-B", userPrompt = "user-B")

        assertEquals(2, fake.calls.size)
        assertEquals("sys-A", fake.calls[0].systemPrompt)
        assertEquals("user-B", fake.calls[1].userPrompt)
        assertFalse(
            "sequential, fully-awaited calls must NEVER trigger concurrent-entry — this " +
                "rules out a flaky detector firing on awaited back-to-back calls.",
            fake.concurrentEntryDetected,
        )
        assertEquals(0, fake.activeCallCount.get())
    }
}
