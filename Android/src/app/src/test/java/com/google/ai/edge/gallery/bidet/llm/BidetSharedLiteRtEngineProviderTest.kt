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
import com.google.ai.edge.litertlm.ExperimentalApi
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * F3.2 (2026-05-09) decision-logic test for [BidetSharedLiteRtEngineProvider.Companion.buildEngineConfig].
 *
 * Why we test the helper instead of the full provider:
 *  [com.google.ai.edge.litertlm.Engine] is a final library class that requires a 3.6 GB
 *  model file to construct — we can't realistically instantiate it in JVM unit tests.
 *  The full [BidetSharedLiteRtEngineProvider.acquire] flow chains:
 *      modelProvider.getModelPath() → buildEngineConfig → engineFactory(config) → Engine
 *  The "decision" the F3.2 fix introduced lives in [buildEngineConfig] — picking whether
 *  to set `audioBackend = Backend.CPU()` (audio caller) or `null` (text caller). The
 *  acquire method's reuse-vs-rebuild logic is straight-line conditionals on a private
 *  field; verifying it would require instantiating an Engine. Pinning the config-building
 *  decision plus the missing-model failure is the highest-value contract this layer can
 *  test in a JVM-only test runner.
 *
 * What this test pins:
 *  1. requireAudio=true → audioBackend is a [Backend.CPU] instance. This is the F3.2 fix's
 *     core property: previously [GemmaAudioEngine.initialize] hardcoded the audio backend
 *     directly; now the shared provider does it on behalf of audio callers only.
 *  2. requireAudio=false → audioBackend is null. The chat client must NOT inadvertently
 *     bring up an audio-enabled engine (the audio backend reserves resources).
 *  3. backend stays GPU regardless of audio mode (per LiteRT-LM 0.10.1 best practice for
 *     Gemma 4 E4B on Pixel 8 Pro).
 *  4. modelPath / maxNumTokens / cacheDir are wired through faithfully.
 *  5. A missing-model error is raised before the factory would be called — caller maps
 *     this to a user-actionable "complete first-run download" message.
 */
@OptIn(ExperimentalApi::class)
class BidetSharedLiteRtEngineProviderTest {

    /** Stub model provider returning a path to a (possibly fake) on-disk file. */
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

    @Test
    fun audioCaller_buildsConfigWithCpuAudioBackend() {
        val modelFile = createTempModelFile()
        try {
            val cfg = BidetSharedLiteRtEngineProvider.buildEngineConfig(
                modelProvider = StubModelProvider(modelFile),
                requireAudio = true,
                maxNumTokens = 1024,
                cacheDir = "/tmp/cache",
            )

            assertNotNull(
                "F3.2: requireAudio=true must set audioBackend != null. If this is null " +
                    "the audio path silently skips Gemma's audio encoder and produces empty " +
                    "transcripts on every chunk.",
                cfg.audioBackend,
            )
            assertTrue(
                "audioBackend must be a CPU backend (Gemma 4's audio encoder runs on CPU " +
                    "per LiteRT-LM 0.10.1+).",
                cfg.audioBackend is Backend.CPU,
            )
            assertTrue(
                "Text/main backend must remain GPU regardless of audio mode.",
                cfg.backend is Backend.GPU,
            )
            assertEquals(modelFile.absolutePath, cfg.modelPath)
            assertEquals(1024, cfg.maxNumTokens)
            assertEquals("/tmp/cache", cfg.cacheDir)
        } finally {
            modelFile.delete()
        }
    }

    @Test
    fun textOnlyCaller_buildsConfigWithNullAudioBackend() {
        val modelFile = createTempModelFile()
        try {
            val cfg = BidetSharedLiteRtEngineProvider.buildEngineConfig(
                modelProvider = StubModelProvider(modelFile),
                requireAudio = false,
                maxNumTokens = 512,
                cacheDir = null,
            )
            assertNull(
                "Text-only acquire must leave audioBackend null. The previous bug had the " +
                    "chat client constructing its own engine with audioBackend=null AND the " +
                    "audio engine constructing its own with audioBackend=Backend.CPU() — two " +
                    "engines in memory pointing at the same 3.6 GB model file. F3.2 collapses " +
                    "to one engine; the FIRST acquire's mode wins (text-only here), and an " +
                    "audio caller arriving later triggers an in-place rebuild via the " +
                    "acquire() upgrade path documented on the Handle.audioEnabled field.",
                cfg.audioBackend,
            )
            assertTrue(cfg.backend is Backend.GPU)
            assertEquals(512, cfg.maxNumTokens)
            assertEquals(null, cfg.cacheDir)
        } finally {
            modelFile.delete()
        }
    }

    @Test
    fun missingModelFile_throwsIllegalState_beforeReachingFactory() {
        val nonexistent = File("/this/path/does/not/exist/gemma.litertlm")
        try {
            BidetSharedLiteRtEngineProvider.buildEngineConfig(
                modelProvider = StubModelProvider(nonexistent),
                requireAudio = false,
                maxNumTokens = 256,
                cacheDir = null,
            )
            fail("Expected IllegalStateException when the model file is missing")
        } catch (e: IllegalStateException) {
            // The kdoc tells callers to distinguish "model missing" by message — we pin a
            // substring here so refactors don't lose the diagnostic.
            assertTrue(
                "Error message should mention the missing path so logs point at the file. " +
                    "Got: ${e.message}",
                e.message?.contains("missing") == true ||
                    e.message?.contains("unavailable") == true,
            )
        }
    }

    @Test
    fun emptyModelFile_throwsIllegalState() {
        // File exists but is empty — caller may have hit a partial-download corruption.
        // Provider must treat this as missing so the user gets routed to re-download.
        val empty = File.createTempFile("bidet-test-empty-", ".litertlm")
        try {
            assertEquals(0L, empty.length())
            try {
                BidetSharedLiteRtEngineProvider.buildEngineConfig(
                    modelProvider = StubModelProvider(empty),
                    requireAudio = false,
                    maxNumTokens = 256,
                    cacheDir = null,
                )
                fail("Expected IllegalStateException for an empty model file")
            } catch (e: IllegalStateException) {
                assertTrue(
                    "Error message must surface the path so logs point at the corrupt file.",
                    e.message?.contains("missing") == true,
                )
            }
        } finally {
            empty.delete()
        }
    }

    @Test
    fun nullModelPath_throwsIllegalState() {
        // BidetModelProvider.getModelPath() can return null when the external files dir
        // isn't yet resolvable (cold-start race). Provider must throw a sensible message.
        try {
            BidetSharedLiteRtEngineProvider.buildEngineConfig(
                modelProvider = StubModelProvider(file = null),
                requireAudio = false,
                maxNumTokens = 256,
                cacheDir = null,
            )
            fail("Expected IllegalStateException when getModelPath() returns null")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Error message should explain the null path.",
                e.message?.contains("unavailable") == true,
            )
        }
    }

    /**
     * Create a tiny temp file standing in for the on-disk Gemma model. The provider only
     * checks `exists() && length() > 0` so 1 byte is enough.
     */
    private fun createTempModelFile(): File {
        val f = File.createTempFile("bidet-test-gemma-", ".litertlm")
        f.writeBytes(byteArrayOf(0x42))
        return f
    }
}
