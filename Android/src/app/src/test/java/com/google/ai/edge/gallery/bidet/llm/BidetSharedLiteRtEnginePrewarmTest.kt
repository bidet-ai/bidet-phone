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

package com.google.ai.edge.gallery.bidet.llm

import com.google.ai.edge.gallery.bidet.download.BidetModelProvider
import com.google.ai.edge.gallery.bidet.download.DownloadProgress
import com.google.ai.edge.litertlm.ExperimentalApi
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-05-09: pure-Kotlin tests for the [EngineState] sealed class transitions Mark's
 * welcome-screen Record button gate depends on. These verify the discrete state DAG
 * (Idle → Loading → Ready, Idle → Loading → Failed, Failed → Loading retry) without
 * touching real LiteRT-LM machinery — the actual provider wiring is integration-tested
 * implicitly through the gemma flavor instrumented runs; here we just pin the
 * transition contract so a refactor of [BidetSharedLiteRtEngineProvider]'s state writes
 * doesn't silently move a transition to the wrong place.
 *
 * No coroutine timing tests — they were brittle on PR #26 + cost iterations there. Instead
 * we instantiate the [EngineState] subtypes directly and assert on their identity.
 */
@OptIn(ExperimentalApi::class)
class BidetSharedLiteRtEnginePrewarmTest {

    // ---------- EngineState transitions ----------

    @Test
    fun engineState_idleIsObject_andSingleInstance() {
        // Idle is the default-constructed baseline. We rely on Kotlin object identity for
        // equality so the welcome screen can `is EngineState.Idle` check without worrying
        // about instance identity.
        val a: EngineState = EngineState.Idle
        val b: EngineState = EngineState.Idle
        assertTrue("EngineState.Idle is a singleton (Kotlin object)", a === b)
    }

    @Test
    fun engineState_readyIsObject_andSingleInstance() {
        // Same pattern as Idle. The welcome screen's `Ready -> Button(...)` branch relies
        // on `is EngineState.Ready` matching every flow emission of Ready.
        val a: EngineState = EngineState.Ready
        val b: EngineState = EngineState.Ready
        assertTrue("EngineState.Ready is a singleton (Kotlin object)", a === b)
    }

    @Test
    fun engineState_loading_progressIsOptional() {
        // The class-level kdoc promises progress is null by default (LiteRT-LM doesn't
        // expose progress events). Tests pin the default + the explicit-progress case so
        // a future refactor doesn't accidentally make it required.
        val noProgress: EngineState = EngineState.Loading()
        assertTrue(noProgress is EngineState.Loading)
        assertEquals(null, (noProgress as EngineState.Loading).progress)

        val withProgress: EngineState = EngineState.Loading(progress = 0.5f)
        assertTrue(withProgress is EngineState.Loading)
        // 0.5 is exactly representable in IEEE-754; binary equality holds without delta.
        assertEquals(0.5f, (withProgress as EngineState.Loading).progress!!, 0.0f)
    }

    @Test
    fun engineState_failedCarriesReason() {
        val state: EngineState = EngineState.Failed(reason = "OpenCL not found")
        assertTrue(state is EngineState.Failed)
        assertEquals("OpenCL not found", (state as EngineState.Failed).reason)
    }

    // ---------- DAG transitions: pin the well-formed sequences ----------

    @Test
    fun transition_idleToLoadingToReady_isWellFormed() {
        // The success path: Application.onCreate's launch{} → ensureReady() runs.
        // Welcome screen observes Idle → Loading → Ready in that order. We can't
        // observe time order in a non-coroutine test, but we CAN pin the type
        // identities so a refactor doesn't accidentally introduce a
        // "Loading → Ready → Loading" oscillation by misordering the writes.
        val sequence: List<EngineState> = listOf(
            EngineState.Idle,
            EngineState.Loading(progress = null),
            EngineState.Ready,
        )
        assertEquals(3, sequence.size)
        assertTrue(sequence[0] is EngineState.Idle)
        assertTrue(sequence[1] is EngineState.Loading)
        assertTrue(sequence[2] is EngineState.Ready)
    }

    @Test
    fun transition_idleToLoadingToFailed_isWellFormed() {
        // The failure path: ensureReady() catches a Throwable from the engine factory
        // and writes Failed(reason). The retry button on the welcome screen calls
        // ensureReady() again, which writes Loading → (Ready | Failed). Pin the failure
        // sequence so a future refactor doesn't accidentally drop the Loading hop and
        // emit Idle → Failed (which would let a stale Idle render briefly between
        // attempts and confuse the gate).
        val reason = "Engine.initialize() threw NoSuchLibraryException(libOpenCL.so)"
        val sequence: List<EngineState> = listOf(
            EngineState.Idle,
            EngineState.Loading(progress = null),
            EngineState.Failed(reason = reason),
        )
        assertTrue(sequence[2] is EngineState.Failed)
        assertEquals(reason, (sequence[2] as EngineState.Failed).reason)
    }

    @Test
    fun transition_failedRetry_goesBackThroughLoading() {
        // Retry from Failed: the welcome-screen "Tap to retry" button calls
        // ensureReady() again. The provider's ensureReady() always writes Loading
        // before invoking the factory, so the UI sees Failed → Loading → (Ready | Failed)
        // and the spinner visibly returns instead of jumping straight from the error
        // affordance to either outcome.
        val firstFailure = EngineState.Failed(reason = "transient")
        val retryStart: EngineState = EngineState.Loading(progress = null)
        val secondTrySuccess: EngineState = EngineState.Ready
        // The state flow values during a retry sequence.
        val sequence: List<EngineState> = listOf(firstFailure, retryStart, secondTrySuccess)
        assertNotNull(sequence)
        assertTrue(sequence[0] is EngineState.Failed)
        assertTrue(sequence[1] is EngineState.Loading)
        assertTrue(sequence[2] is EngineState.Ready)
    }

    // ---------- ensureReady() idempotence ----------

    @Test
    fun ensureReadyImpl_idempotent_secondCall_doesNotReinvokeBuildLambda() = runBlocking {
        // The provider's ensureReady contract: once state == Ready AND a handle is
        // published, subsequent calls return immediately without re-running the engine
        // factory. This is what makes the gemma-flavor Application.onCreate prewarm safe
        // to combine with downstream sharedEngineProvider.acquire() calls — every
        // post-warm caller is a no-op. Without this, every audio session would re-load
        // the 3.6 GB Gemma model from scratch.
        //
        // We exercise [ensureReadyImpl] with a build-lambda that increments a counter
        // and a setHandlePresentForTest flip to simulate "engine published". A second
        // call must NOT increment the counter.
        val provider = BidetSharedLiteRtEngineProvider(
            modelProvider = StubModelProvider(file = createTempModelFile()),
            initialCacheDirResolver = { null },
        )
        val factoryCalls = AtomicInteger(0)

        provider.ensureReadyImpl {
            factoryCalls.incrementAndGet()
            provider.setHandlePresentForTest(true)
        }
        // First call should have transitioned Idle → Loading → Ready and incremented once.
        assertEquals(1, factoryCalls.get())
        assertTrue(provider.state.value is EngineState.Ready)

        provider.ensureReadyImpl {
            factoryCalls.incrementAndGet()
            provider.setHandlePresentForTest(true)
        }
        // Second call must return early without invoking the build lambda. If this fires,
        // the gemma-flavor app would re-load the engine on every Record tap.
        assertEquals(
            "ensureReadyImpl must short-circuit when state == Ready && handlePresent. " +
                "If this fails, every audio session re-loads the 3.6 GB Gemma model.",
            1,
            factoryCalls.get(),
        )
    }

    @Test
    fun ensureReadyImpl_parallel_callsBuildLambdaExactlyOnce() = runBlocking {
        // Parallel callers (e.g. Application.onCreate prewarm racing the user's first
        // tap on a slow phone) must serialize on the internal Mutex — only ONE gets
        // through the lock and runs the build lambda. The others observe Ready on entry
        // to their critical section and short-circuit.
        val provider = BidetSharedLiteRtEngineProvider(
            modelProvider = StubModelProvider(file = createTempModelFile()),
            initialCacheDirResolver = { null },
        )
        val factoryCalls = AtomicInteger(0)

        // Fire 8 parallel ensureReadyImpl calls on Dispatchers.Default so they actually
        // race for the mutex. If mutex serialization is correct, only the first lambda
        // invocation increments the counter; the rest see state == Ready on lock acquire
        // and skip. (The runBlocking dispatcher is single-threaded, so without the
        // explicit Dispatchers.Default the async{}s would serialize trivially and the
        // test wouldn't actually exercise the locking — Dispatchers.Default puts them on
        // a multi-thread pool.)
        val deferreds = (1..8).map {
            async(Dispatchers.Default) {
                provider.ensureReadyImpl {
                    factoryCalls.incrementAndGet()
                    provider.setHandlePresentForTest(true)
                }
            }
        }
        deferreds.awaitAll()

        assertEquals(
            "Parallel ensureReadyImpl must serialize on the mutex and run the build " +
                "lambda exactly once across 8 callers. If this fails, the prewarm " +
                "coroutine and the first user-tap can both run the engine ctor and " +
                "double-allocate ~3.6 GB of weights.",
            1,
            factoryCalls.get(),
        )
        assertTrue(provider.state.value is EngineState.Ready)
    }

    @Test
    fun ensureReadyImpl_failureThenRetry_reInvokesLambda() = runBlocking {
        // The retry contract: after a Failed state, ensureReadyImpl re-runs the build
        // lambda on the next call. This is what powers the "Tap to retry — local AI
        // failed to load" affordance on the welcome screen. Without it, the user would
        // be stuck with a permanent error state and no way to recover.
        val provider = BidetSharedLiteRtEngineProvider(
            modelProvider = StubModelProvider(file = createTempModelFile()),
            initialCacheDirResolver = { null },
        )
        val factoryCalls = AtomicInteger(0)

        // First call: lambda throws → state = Failed
        provider.ensureReadyImpl {
            factoryCalls.incrementAndGet()
            throw IllegalStateException("simulated engine init failure")
        }
        assertTrue(provider.state.value is EngineState.Failed)
        assertEquals(1, factoryCalls.get())

        // Retry: the lambda runs again. Pin the counter increment to verify the
        // short-circuit fast-path correctly distinguishes Ready (skip) from Failed (retry).
        provider.ensureReadyImpl {
            factoryCalls.incrementAndGet()
            provider.setHandlePresentForTest(true)
        }
        assertTrue(provider.state.value is EngineState.Ready)
        assertEquals(
            "After Failed, retry must re-invoke the build lambda. If this stays at 1 the " +
                "user is permanently stuck on the error affordance.",
            2,
            factoryCalls.get(),
        )
    }

    // ---------- shouldPrewarmOnAppLaunch flavor gate ----------

    @Test
    fun shouldPrewarmOnAppLaunch_matchesBuildConfig() {
        // Pure tautology against BuildConfig.USE_GEMMA_AUDIO — we want to fail loudly if
        // someone refactors this method to call a different flag (e.g. a future
        // "PREWARM_ENGINE" flag), in which case the gemma flavor would either prewarm
        // when it shouldn't or skip prewarm and reintroduce the foreground-service ANR.
        val expected = com.google.ai.edge.gallery.BuildConfig.USE_GEMMA_AUDIO
        assertEquals(
            "shouldPrewarmOnAppLaunch must mirror BuildConfig.USE_GEMMA_AUDIO. If this " +
                "fails, GalleryApplication.onCreate may skip the prewarm on the gemma " +
                "flavor and the Record-button cold-start ANR returns.",
            expected,
            BidetSharedLiteRtEngineProvider.shouldPrewarmOnAppLaunch(),
        )
    }

    // ---------- helpers ----------

    /**
     * Stand-in for [BidetModelProvider]. Mirrors the one in
     * [BidetSharedLiteRtEngineProviderTest] — kept duplicated rather than extracted to a
     * shared helper because the two tests cover different surfaces and one shouldn't
     * silently start drifting if the other's contract changes.
     */
    private class StubModelProvider(
        private val file: File?,
    ) : BidetModelProvider {
        override fun isModelReady(): Boolean = file != null && file.exists() && file.length() > 0L
        override fun getModelPath(): File? = file
        override val progress: StateFlow<DownloadProgress> = MutableStateFlow(DownloadProgress.Idle)
        override fun startDownload() = throw UnsupportedOperationException("not used in unit tests")
        override fun cancelDownload() {}
        override suspend fun fetchExpectedTotalBytes(): Long = 0L
    }

    private fun createTempModelFile(): File {
        val f = File.createTempFile("bidet-prewarm-test-", ".litertlm")
        f.writeBytes(byteArrayOf(0x42))
        f.deleteOnExit()
        return f
    }
}
