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
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 2026-05-10: tests for the NPU-first / CPU-fallback decision in
 * [BidetSharedLiteRtEngineProvider.tryNpuThenCpu].
 *
 * Why a separate test file:
 *  [BidetSharedLiteRtEngineProviderTest] pins the [buildEngineConfig] decision logic.
 *  This file pins the NEW dimension the 2026-05-10 experiment adds — given a working /
 *  failing engine factory, does the helper produce the right config sequence and the
 *  right [BackendAttempt.backendKind] tag? The helper is generic over the engine type so
 *  we can stand in `String` for the real LiteRT-LM [com.google.ai.edge.litertlm.Engine]
 *  (final + native, can't be mocked without a heavyweight framework — see the existing
 *  test file's kdoc for the same constraint).
 *
 * The contract:
 *  1. NPU attempt happens FIRST. The first config the helper builds has
 *     `backend is Backend.NPU` and `nativeLibraryDir` matches the resolver.
 *  2. If NPU build succeeds, CPU is never attempted; result is the NPU engine, kind=NPU.
 *  3. If NPU build throws a generic Throwable, helper logs and retries with CPU. The
 *     second config has `backend is Backend.CPU`; result is the CPU engine, kind=CPU.
 *  4. If CPU also throws, the throwable propagates (no second fallback).
 *  5. OutOfMemoryError and InterruptedException from the NPU attempt propagate
 *     unmodified — they're not "NPU not available" signals.
 *  6. The fresh-config-per-attempt invariant: if the helper retries CPU after NPU
 *     fails, the CPU config gets `audioBackend != null` when `requireAudio` was true on
 *     the NPU attempt. Tested by inspecting both calls' configs through buildConfig.
 */
@OptIn(ExperimentalApi::class)
class BidetEngineNpuFallbackTest {

    /** Capture each (Backend, EngineConfig) pair that flows through the helper. */
    private class CapturingProbe {
        data class ConfigCall(val backend: Backend, val config: EngineConfig)

        val configCalls = mutableListOf<ConfigCall>()
        val engineCalls = mutableListOf<EngineConfig>()
    }

    /**
     * Build a buildConfig lambda that records the requested backend and returns a
     * minimal valid [EngineConfig] for the helper to forward. We build configs through
     * [BidetSharedLiteRtEngineProvider.buildEngineConfig] so the audioBackend wiring is
     * exercised on each attempt — that's the property test 6 above pins.
     */
    private fun buildConfigFn(
        modelFile: File,
        requireAudio: Boolean,
        probe: CapturingProbe,
    ): (Backend) -> EngineConfig = { backend ->
        val cfg = BidetSharedLiteRtEngineProvider.buildEngineConfig(
            modelProvider = StubModelProvider(modelFile),
            requireAudio = requireAudio,
            maxNumTokens = 1024,
            cacheDir = "/tmp/cache",
            backend = backend,
        )
        probe.configCalls += CapturingProbe.ConfigCall(backend, cfg)
        cfg
    }

    @Test
    fun npuSucceeds_returnsNpuEngine_andDoesNotAttemptCpu() {
        val modelFile = createTempModelFile()
        try {
            val probe = CapturingProbe()
            val expectedEngine = "fake-npu-engine"
            val attempt = BidetSharedLiteRtEngineProvider.tryNpuThenCpu(
                buildConfig = buildConfigFn(modelFile, requireAudio = true, probe = probe),
                buildEngine = { cfg -> probe.engineCalls += cfg; expectedEngine },
                nativeLibraryDir = "/data/app/lib/arm64-v8a",
            )

            assertSame("Successful NPU attempt must return its engine.", expectedEngine, attempt.engine)
            assertEquals("backendKind must be NPU on success.", "NPU", attempt.backendKind)
            assertEquals(
                "Helper must build exactly one config when NPU succeeds — the CPU " +
                    "fallback path must not run.",
                1, probe.configCalls.size,
            )
            assertTrue(
                "First (and only) attempt must be Backend.NPU.",
                probe.configCalls[0].backend is Backend.NPU,
            )
            assertEquals(
                "NPU backend must carry the resolver's nativeLibraryDir.",
                "/data/app/lib/arm64-v8a",
                (probe.configCalls[0].backend as Backend.NPU).nativeLibraryDir,
            )
            assertEquals(
                "Engine builder must be invoked exactly once on the NPU success path.",
                1, probe.engineCalls.size,
            )
        } finally {
            modelFile.delete()
        }
    }

    @Test
    fun npuThrows_fallsBackToCpu_andReturnsCpuEngine() {
        val modelFile = createTempModelFile()
        try {
            val probe = CapturingProbe()
            val cpuEngine = "fake-cpu-engine"
            val callCount = AtomicInteger(0)
            val attempt = BidetSharedLiteRtEngineProvider.tryNpuThenCpu(
                buildConfig = buildConfigFn(modelFile, requireAudio = true, probe = probe),
                buildEngine = { cfg ->
                    probe.engineCalls += cfg
                    val n = callCount.incrementAndGet()
                    if (n == 1) {
                        // Simulate the realistic NPU failure: missing native shim libs
                        // (the most likely failure mode on a generic AAR install where
                        // the device-specific NPU runtime isn't bundled).
                        throw UnsatisfiedLinkError(
                            "dlopen failed: library \"libnpu_runtime.so\" not found",
                        )
                    }
                    cpuEngine
                },
                nativeLibraryDir = "/data/app/lib/arm64-v8a",
            )

            assertSame("After fallback, returned engine must be the CPU one.", cpuEngine, attempt.engine)
            assertEquals("backendKind must be CPU after NPU fallback.", "CPU", attempt.backendKind)
            assertEquals(
                "Two configs must be built: NPU first, then CPU.",
                2, probe.configCalls.size,
            )
            assertTrue(
                "First config must be Backend.NPU.",
                probe.configCalls[0].backend is Backend.NPU,
            )
            assertTrue(
                "Second config must be Backend.CPU after NPU throws.",
                probe.configCalls[1].backend is Backend.CPU,
            )
            // The audio backend must survive the fallback. Reproducing this property
            // matters because the PR's stated motivation is unblocking audio mode — a
            // fallback that silently dropped audioBackend would produce empty
            // transcripts (the bug we hit on 2026-05-09 — see Rule 3 in
            // reference_litertlm_tensor_g3_lessons_2026-05-09.md).
            assertTrue(
                "After fallback, CPU config must still have audioBackend wired.",
                probe.configCalls[1].config.audioBackend is Backend.CPU,
            )
            assertEquals(
                "Engine builder must be invoked twice (failed NPU + successful CPU).",
                2, probe.engineCalls.size,
            )
        } finally {
            modelFile.delete()
        }
    }

    @Test
    fun cpuFallbackAlsoThrows_propagatesCpuFailure() {
        // If the NPU AND CPU attempts both fail, the helper has nothing more to try.
        // The CPU throwable propagates — caller (acquire / ensureReadyImpl) maps it to
        // EngineState.Failed. We pin this to make sure a future refactor doesn't
        // accidentally swallow the CPU failure (which would leave callers stuck with no
        // engine and no error).
        val modelFile = createTempModelFile()
        try {
            val probe = CapturingProbe()
            val callCount = AtomicInteger(0)
            try {
                BidetSharedLiteRtEngineProvider.tryNpuThenCpu<String>(
                    buildConfig = buildConfigFn(modelFile, requireAudio = false, probe = probe),
                    buildEngine = { _ ->
                        val n = callCount.incrementAndGet()
                        if (n == 1) throw IllegalStateException("npu init refused config")
                        throw IllegalStateException("cpu init also refused")
                    },
                    nativeLibraryDir = null,
                )
                fail("Expected IllegalStateException from the CPU fallback attempt.")
            } catch (e: IllegalStateException) {
                assertEquals(
                    "When CPU fallback fails, that failure (not the NPU one) must propagate.",
                    "cpu init also refused", e.message,
                )
                assertEquals(
                    "Both NPU and CPU configs must have been built before propagation.",
                    2, probe.configCalls.size,
                )
            }
        } finally {
            modelFile.delete()
        }
    }

    @Test
    fun outOfMemoryFromNpu_propagates_withoutFallback() {
        // OOM is a signal that the device cannot host this model under any backend, so
        // the CPU retry would just OOM again. Better to fail fast than spend another
        // ~60 seconds reloading 2.6 GB only to die.
        val modelFile = createTempModelFile()
        try {
            val probe = CapturingProbe()
            try {
                BidetSharedLiteRtEngineProvider.tryNpuThenCpu<String>(
                    buildConfig = buildConfigFn(modelFile, requireAudio = false, probe = probe),
                    buildEngine = { _ -> throw OutOfMemoryError("simulated allocation failure") },
                    nativeLibraryDir = null,
                )
                fail("OutOfMemoryError from the NPU attempt must propagate, not trigger fallback.")
            } catch (e: OutOfMemoryError) {
                assertEquals("simulated allocation failure", e.message)
                assertEquals(
                    "OOM must short-circuit before the helper builds a CPU config.",
                    1, probe.configCalls.size,
                )
                assertTrue(probe.configCalls[0].backend is Backend.NPU)
            }
        } finally {
            modelFile.delete()
        }
    }

    @Test
    fun interruptedExceptionFromNpu_propagates_withoutFallback() {
        // InterruptedException carries cancellation semantics. Swallowing it would mask
        // a coroutine cancellation and let the helper run a CPU init that the caller
        // has already abandoned — wasted work AND a memory leak (the engine never gets
        // closed). Pin propagation.
        val modelFile = createTempModelFile()
        try {
            val probe = CapturingProbe()
            try {
                BidetSharedLiteRtEngineProvider.tryNpuThenCpu<String>(
                    buildConfig = buildConfigFn(modelFile, requireAudio = false, probe = probe),
                    buildEngine = { _ -> throw InterruptedException("coroutine cancelled") },
                    nativeLibraryDir = null,
                )
                fail("InterruptedException must propagate unmodified.")
            } catch (e: InterruptedException) {
                assertEquals("coroutine cancelled", e.message)
                assertEquals(1, probe.configCalls.size)
            }
        } finally {
            modelFile.delete()
        }
    }

    @Test
    fun nullNativeLibraryDir_isCoercedToEmptyString_onNpuConfig() {
        // Tests / cold-start race conditions can produce a null nativeLibraryDir. The
        // upstream Backend.NPU default is `""`, so the helper must coerce null → "" so
        // we don't crash with NPE constructing the Backend before the engine even gets
        // a chance to fall back to CPU.
        val modelFile = createTempModelFile()
        try {
            val probe = CapturingProbe()
            val cpuEngine = "cpu"
            val callCount = AtomicInteger(0)
            val attempt = BidetSharedLiteRtEngineProvider.tryNpuThenCpu(
                buildConfig = buildConfigFn(modelFile, requireAudio = false, probe = probe),
                buildEngine = { _ ->
                    if (callCount.incrementAndGet() == 1) throw RuntimeException("npu nope")
                    cpuEngine
                },
                nativeLibraryDir = null,
            )

            assertSame(cpuEngine, attempt.engine)
            assertEquals("CPU", attempt.backendKind)
            val firstNpu = probe.configCalls[0].backend as Backend.NPU
            assertEquals(
                "Null nativeLibraryDir must coerce to empty string per upstream default.",
                "", firstNpu.nativeLibraryDir,
            )
        } finally {
            modelFile.delete()
        }
    }

    // ---------- helpers ----------

    private fun createTempModelFile(): File {
        val f = File.createTempFile("bidet-npu-test-", ".litertlm")
        f.writeBytes(byteArrayOf(0x42))
        f.deleteOnExit()
        return f
    }

    private class StubModelProvider(private val file: File?) : BidetModelProvider {
        override fun isModelReady(): Boolean = file != null && file.exists() && file.length() > 0L
        override fun getModelPath(): File? = file
        override val progress: StateFlow<DownloadProgress> = MutableStateFlow(DownloadProgress.Idle)
        override fun startDownload() = throw UnsupportedOperationException("not used in unit tests")
        override fun cancelDownload() {}
        override suspend fun fetchExpectedTotalBytes(): Long = 0L
    }
}
