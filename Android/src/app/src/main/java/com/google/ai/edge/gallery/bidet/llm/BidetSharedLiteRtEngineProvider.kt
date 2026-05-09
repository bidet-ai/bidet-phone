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

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.bidet.download.BidetModelProvider
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * F3.2 fix (2026-05-09): a single, shared LiteRT-LM [Engine] for the gemma flavor.
 *
 * Why this exists:
 *  Before this fix, [com.google.ai.edge.gallery.bidet.ui.LiteRtBidetGemmaClient] (chat-tab
 *  generation) and [com.google.ai.edge.gallery.bidet.transcription.GemmaAudioEngine]
 *  (audio transcription) each constructed their OWN `Engine(EngineConfig(modelPath = ...))`
 *  against the same 3.6 GB Gemma 4 E4B file. Two engines holding the same weights ≈ 7+ GB
 *  resident on the Pixel 8 Pro — usually surfaces as a silent init failure on the audio
 *  path (the second engine to come up loses the GPU race), but on a tight-memory device it
 *  can also OOM the process during a session that uses both chat + recording in the same
 *  minute. Demo risk: judge installs the gemma APK, taps Record, taps a tab, app dies.
 *
 * After this fix:
 *  Both call sites go through this provider. The first call wins on audio mode — if the
 *  FIRST caller is [com.google.ai.edge.gallery.bidet.transcription.GemmaAudioEngine.initialize],
 *  the engine comes up with `audioBackend = Backend.CPU()` enabled; if the first caller is
 *  the chat client for text-only, the engine comes up with `audioBackend = null`. Subsequent
 *  callers reuse whatever was built. Conversations are cheap — every call site creates its
 *  own [com.google.ai.edge.litertlm.Conversation] off the shared engine.
 *
 * Threading:
 *  Lazy init is guarded by a coroutine [Mutex] (matches the existing
 *  [com.google.ai.edge.gallery.bidet.ui.LiteRtBidetGemmaClient.initMutex] convention).
 *
 * Trade-offs we accepted:
 *  - First-access-wins for audio mode means: if the user opens the chat tab BEFORE tapping
 *    Record for the first time (most common path on a fresh install), the engine comes up
 *    text-only and the very first record-tap rebuilds the engine with audio enabled. We
 *    detect that case in [acquire] and rebuild in place rather than failing — see the
 *    `existing != null && requireAudio && !existing.audioEnabled` branch. The rebuild is
 *    the only path where we tear down the old engine; subsequent acquires reuse.
 *  - The engine's `maxNumTokens` is taken from the FIRST acquire that needed it. We log a
 *    warning if a later acquire wanted more — the existing call sites use the same
 *    constants, so a discrepancy here would be a code-change regression worth a log line,
 *    not a runtime rebuild.
 */
@Singleton
@OptIn(ExperimentalApi::class)
class BidetSharedLiteRtEngineProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelProvider: BidetModelProvider,
) {

    /**
     * Internal handle: the live [Engine] plus the construction parameters that decided how
     * it was built. Tracking the params on the handle lets [acquire] decide
     * "rebuild" vs "reuse" without poking at engine internals.
     */
    private data class Handle(
        val engine: Engine,
        val audioEnabled: Boolean,
        val maxNumTokens: Int,
    )

    @Volatile private var handle: Handle? = null
    private val mutex = Mutex()

    /**
     * Test-only override of the engine factory. Production keeps the default which calls
     * the real [Engine] ctor + initialize. Tests swap in a fake to verify the
     * "first-access wins on audio mode" + "rebuild on audio upgrade" decisions without
     * loading 3.6 GB of weights.
     */
    @Volatile internal var engineFactory: (EngineConfig) -> Engine = { config ->
        Engine(config).also { it.initialize() }
    }

    /**
     * Acquire the shared engine.
     *
     * @param requireAudio true if the caller needs `audioBackend = Backend.CPU()` enabled
     *   (i.e. [com.google.ai.edge.gallery.bidet.transcription.GemmaAudioEngine]). false for
     *   text-only callers.
     * @param maxNumTokens the caller's max output tokens. The engine is built with whatever
     *   the first caller asked for; later acquires that wanted more get a logged warning.
     * @return the live [Engine]. Caller wraps in a [com.google.ai.edge.litertlm.Conversation]
     *   for its own purposes.
     * @throws IllegalStateException if the model file is not on disk (download incomplete).
     * @throws Throwable whatever [Engine.initialize] propagates on init failure; caller maps
     *   to its own error path. We deliberately do NOT swallow here so the caller can
     *   distinguish "model missing" (recoverable — point user at download screen) from
     *   "engine init crashed" (terminal — log + abort the recording).
     */
    suspend fun acquire(requireAudio: Boolean, maxNumTokens: Int): Engine = mutex.withLock {
        val existing = handle
        if (existing != null) {
            if (!requireAudio || existing.audioEnabled) {
                if (maxNumTokens > existing.maxNumTokens) {
                    Log.w(
                        TAG,
                        "acquire: caller wants maxNumTokens=$maxNumTokens but existing engine was " +
                            "built with ${existing.maxNumTokens}. Reusing existing engine; if the " +
                            "lower cap matters this is the place to add a rebuild.",
                    )
                }
                return@withLock existing.engine
            }
            // Audio caller arrived AFTER a text-only engine was built. Rebuild in place
            // with audioBackend enabled. Tear down the old one first so we never have two
            // engines resident simultaneously (which would be the F3.2 bug we're fixing).
            Log.i(TAG, "acquire: upgrading shared engine from text-only to audio-enabled.")
            try { existing.engine.close() } catch (t: Throwable) {
                Log.w(TAG, "acquire: previous engine.close threw during upgrade", t)
            }
            handle = null
        }

        val newHandle = buildHandle(
            requireAudio = requireAudio,
            maxNumTokens = maxNumTokens,
        )
        handle = newHandle
        return@withLock newHandle.engine
    }

    /**
     * Tear down the shared engine and release its weights. Idempotent.
     *
     * NOT called on the per-recording teardown path — both call sites (chat client + audio
     * engine) use the shared engine for the lifetime of the process. Conversations close
     * independently; the engine stays warm so the next acquire is instant.
     *
     * Exposed for completeness + tests.
     */
    suspend fun release() = mutex.withLock {
        val h = handle ?: return@withLock
        handle = null
        try { h.engine.close() } catch (t: Throwable) {
            Log.w(TAG, "release: engine.close threw", t)
        }
    }

    private fun buildHandle(requireAudio: Boolean, maxNumTokens: Int): Handle {
        val cacheDir = context.getExternalFilesDir(null)?.absolutePath
        val config = buildEngineConfig(
            modelProvider = modelProvider,
            requireAudio = requireAudio,
            maxNumTokens = maxNumTokens,
            cacheDir = cacheDir,
        )
        val engine = engineFactory(config)
        Log.i(
            TAG,
            "buildHandle: shared engine ready audioEnabled=$requireAudio maxNumTokens=$maxNumTokens path=${config.modelPath}",
        )
        return Handle(
            engine = engine,
            audioEnabled = requireAudio,
            maxNumTokens = maxNumTokens,
        )
    }

    companion object {
        private const val TAG = "BidetSharedLiteRtEngine"

        /**
         * F3.2 (2026-05-09): pure-Kotlin helper extracted for unit testing. Builds the
         * [EngineConfig] the provider would pass to the [Engine] ctor, given a model
         * provider + the per-acquire flags. Contains the audio-mode decision (the F3.2
         * fix's key behaviour) without any [android.content.Context] machinery, so tests
         * can pin the decision without booting LiteRT-LM.
         *
         * @throws IllegalStateException if the model file is missing — the provider's
         *   [acquire] propagates this so callers (chat / audio engine) can surface a
         *   "complete first-run download" message.
         */
        internal fun buildEngineConfig(
            modelProvider: BidetModelProvider,
            requireAudio: Boolean,
            maxNumTokens: Int,
            cacheDir: String?,
        ): EngineConfig {
            val modelFile = modelProvider.getModelPath()
                ?: throw IllegalStateException(
                    "Model path is unavailable; download incomplete?",
                )
            if (!modelFile.exists() || modelFile.length() == 0L) {
                throw IllegalStateException(
                    "Gemma model missing at ${modelFile.absolutePath}; complete first-run download.",
                )
            }
            return EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.GPU(),
                visionBackend = null,
                audioBackend = if (requireAudio) Backend.CPU() else null,
                maxNumTokens = maxNumTokens,
                cacheDir = cacheDir,
            )
        }
    }
}
