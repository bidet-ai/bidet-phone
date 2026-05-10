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
import androidx.annotation.VisibleForTesting
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.bidet.download.BidetModelProvider
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lifecycle states for the shared engine. Drives the welcome-screen Record button gate so
 * the user sees "Loading local AI…" until the 3.6 GB Gemma 4 E4B engine is warm. The state
 * flow is also the canonical signal for any future tab-side work that wants to wait on the
 * engine instead of triggering its own init.
 */
sealed class EngineState {
    /** No init attempt has been made yet. Pre-`ensureReady` baseline. */
    object Idle : EngineState()

    /**
     * An init is in flight. [progress] is null because the LiteRT-LM Engine ctor does not
     * surface progress events; the UI shows an indeterminate spinner. Reserved for a future
     * progress signal if upstream adds one.
     */
    data class Loading(val progress: Float? = null) : EngineState()

    /** Engine is initialized and ready for [Engine.createConversation]. */
    object Ready : EngineState()

    /**
     * Init failed. [reason] is the throwable's message (or class name if message is null).
     * The UI can offer a retry that calls [BidetSharedLiteRtEngineProvider.ensureReady]
     * again — the provider clears `Failed` on the next attempt.
     */
    data class Failed(val reason: String) : EngineState()
}

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
 * 2026-05-09 add: app-launch pre-warm.
 *  Tapping Record used to cold-start the 3.6 GB engine load synchronously inside
 *  [com.google.ai.edge.gallery.bidet.service.RecordingService.onStartCommand], which blew
 *  the Android `startForegroundService → startForeground` 5-second deadline (logcat showed
 *  `startForegroundDelayMs:68453`). We now expose [ensureReady] + [state]: the
 *  Application class kicks off [ensureReady] on a background coroutine in `onCreate`, and
 *  the welcome-screen Record button is gated on `state == Ready`. By the time the user
 *  finds the Record button the engine is warm; [RecordingService] just observes state.
 *
 * Threading:
 *  Lazy init is guarded by a coroutine [Mutex] (matches the existing
 *  [com.google.ai.edge.gallery.bidet.ui.LiteRtBidetGemmaClient.initMutex] convention).
 *  [ensureReady] is idempotent: parallel callers serialize on the same mutex; if a `Ready`
 *  handle already exists they return immediately without re-entering the factory.
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
class BidetSharedLiteRtEngineProvider @VisibleForTesting internal constructor(
    private val modelProvider: BidetModelProvider,
    initialCacheDirResolver: () -> String?,
    initialNativeLibraryDirResolver: () -> String? = { null },
) {

    /**
     * Resolves the LiteRT-LM `cacheDir` for [Engine.initialize] each time the provider
     * builds an engine handle. Production captures the injected ApplicationContext via
     * the [@Inject] secondary ctor; tests pass a fixed-string lambda via the primary
     * ctor so the full state-machine path is exercisable in pure-JVM tests without
     * needing an Android [Context].
     */
    @Volatile
    internal var cacheDirResolver: () -> String? = initialCacheDirResolver

    /**
     * Resolves the directory containing the NPU native libraries (passed to
     * [Backend.NPU.nativeLibraryDir]). LiteRT-LM 0.11 docs:
     *   "On Android, for apps with built-in NPU libraries, including NPU libraries
     *    delivered as Google Play Feature modules, set it to
     *    `Context.applicationInfo.nativeLibraryDir`."
     *
     * Returns null in tests / on non-Android paths; the NPU attempt is then either
     * skipped or attempted with the empty default (the upstream class default is `""`).
     * Any failure during NPU init falls back to CPU — see [buildHandle].
     */
    @Volatile
    internal var nativeLibraryDirResolver: () -> String? = initialNativeLibraryDirResolver

    /**
     * Hilt-injected ctor. Captures the [Context] inside both lambda resolvers so the only
     * Context-touching calls ([Context.getExternalFilesDir] + reading
     * `applicationInfo.nativeLibraryDir`) are deferred until the first [acquire] /
     * [ensureReady] actually needs them. Hilt sees `@Inject` and uses this constructor
     * exclusively at runtime.
     */
    @Inject constructor(
        @ApplicationContext context: Context,
        modelProvider: BidetModelProvider,
    ) : this(
        modelProvider = modelProvider,
        initialCacheDirResolver = { context.getExternalFilesDir(null)?.absolutePath },
        initialNativeLibraryDirResolver = { context.applicationInfo.nativeLibraryDir },
    )

    /**
     * Internal handle: the live [Engine] plus the construction parameters that decided how
     * it was built. Tracking the params on the handle lets [acquire] decide
     * "rebuild" vs "reuse" without poking at engine internals.
     *
     * 2026-05-10: [backendKind] records whether the engine actually came up on NPU or CPU
     * after the [buildHandle] try-NPU-fall-back-to-CPU dance. Surfaced in logs so logcat
     * shows which path Mark's session ran on.
     */
    private data class Handle(
        val engine: Engine,
        val audioEnabled: Boolean,
        val maxNumTokens: Int,
        val backendKind: String,
    )

    @Volatile private var handle: Handle? = null
    private val mutex = Mutex()

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    /**
     * Welcome-screen Record button + any future engine-readiness UI binds to this. Emits
     * Idle → Loading → Ready (or Failed) per call to [ensureReady]. Reset to Loading on a
     * retry from Failed. Stays at Ready for the lifetime of the process once warm —
     * subsequent [acquire] calls are no-ops on this flow.
     */
    val state: StateFlow<EngineState> = _state.asStateFlow()

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
     * Pre-warm the shared engine. Idempotent: if the engine is already [EngineState.Ready],
     * returns immediately. Parallel callers serialize on the internal [Mutex] and only one
     * of them runs the real init — the others observe `Ready` on entry to the locked
     * section and return.
     *
     * This is the entry point the [com.google.ai.edge.gallery.GalleryApplication]'s
     * `onCreate` calls on a background coroutine to warm the 3.6 GB Gemma engine before the
     * user finds the Record button. The welcome screen's Record button is gated on
     * [state] == [EngineState.Ready] so a slow phone shows "Loading local AI…" instead of
     * a broken record tap.
     *
     * On the moonshine flavor this path is unused (the moonshine engine doesn't go through
     * the shared LiteRT-LM provider). The [com.google.ai.edge.gallery.GalleryApplication]
     * checks `BuildConfig.USE_GEMMA_AUDIO` and skips the call there, but this method is also
     * safe to invoke on the moonshine flavor — it just incurs a no-op model-file check the
     * first time and then either reuses or rebuilds.
     *
     * We pre-warm with `requireAudio = true` and `maxNumTokens = MAX_OUTPUT_TOKENS_PREWARM`
     * because the gemma flavor's ONLY entry from the welcome Record button goes through
     * [com.google.ai.edge.gallery.bidet.transcription.GemmaAudioEngine] which needs the
     * audio backend. If a chat-tab caller arrives later wanting more tokens the existing
     * "max-tokens-mismatch" log warning still fires; if a text-only caller arrives first
     * during the prewarm window, the mutex serializes them and the first one to acquire the
     * lock wins (audio mode if prewarm wins, text-only if the chat client wins — either is
     * recoverable via the in-place rebuild path).
     */
    suspend fun ensureReady() {
        ensureReadyImpl(buildAndPublishHandle = ::buildAndPublishHandle)
    }

    /**
     * 2026-05-09: extracted state-machine entry point. The `buildAndPublishHandle` lambda
     * is the only piece that touches LiteRT-LM internals; tests pass a fake lambda to
     * verify idempotence (fake is called exactly once even on parallel ensureReadyImpl
     * calls) and the Idle → Loading → Ready / Failed transition contract.
     *
     * The lambda is side-effecting: it builds the engine AND publishes it to [handle].
     * Tests don't need to publish a real engine — they just need to mark "I would have
     * built one" by flipping their own counter; the [handle]-presence half of the
     * idempotence predicate is satisfied separately (the lambda itself is responsible for
     * setting [handle], and the test fake can use [setHandlePresentForTest]).
     */
    @VisibleForTesting
    internal suspend fun ensureReadyImpl(
        buildAndPublishHandle: suspend () -> Unit,
    ) {
        // Fast path: already Ready, no lock needed. Volatile read of `handle` paired with the
        // _state MutableStateFlow read keeps the common case (every call after the first
        // successful warm-up) lock-free.
        if (_state.value is EngineState.Ready && handlePresent()) return

        mutex.withLock {
            // Re-check inside the lock — another caller may have completed init while we
            // were waiting. This is the idempotence contract: parallel ensureReady() calls
            // run init exactly once.
            if (_state.value is EngineState.Ready && handlePresent()) return@withLock

            _state.value = EngineState.Loading(progress = null)
            try {
                buildAndPublishHandle()
                _state.value = EngineState.Ready
            } catch (t: Throwable) {
                Log.e(TAG, "ensureReady: engine init failed", t)
                _state.value = EngineState.Failed(
                    reason = t.message ?: t.javaClass.simpleName,
                )
                // Don't rethrow — the state flow IS the result channel for this method. The
                // caller (Application.onCreate's launch{} or RecordingService) just observes
                // state. A throw here would crash the application coroutine for no benefit.
            }
        }
    }

    private fun buildAndPublishHandle() {
        val newHandle = buildHandle(
            requireAudio = true,
            maxNumTokens = MAX_OUTPUT_TOKENS_PREWARM,
        )
        handle = newHandle
    }

    /**
     * 2026-05-09: test-only seam. Tests that drive [ensureReadyImpl] to verify
     * idempotence need to satisfy the dual `state == Ready && handle != null` predicate;
     * their fake build-and-publish lambda calls this to flip the [handle]-presence half
     * without exposing the private [Handle] type to test code. The boolean is checked
     * directly by [ensureReadyImpl] via the `handle != null` read; see [handlePresent]
     * which mirrors what tests actually need.
     */
    @VisibleForTesting
    internal fun setHandlePresentForTest(present: Boolean) {
        // Tests can't construct a real [Handle] (Engine is final + native). We store a
        // marker boolean in a separate Volatile field so [ensureReadyImpl] can short-
        // circuit on it without dereferencing the engine. Production paths still use
        // [handle] directly. Wired via [handlePresent] which folds both readers.
        testHandlePresent = present
    }

    @Volatile private var testHandlePresent: Boolean = false

    /**
     * Whether a usable handle is published. Production reads [handle] != null; tests
     * read [testHandlePresent]. Folded into one accessor so [ensureReadyImpl] doesn't
     * need a per-mode branch.
     */
    private fun handlePresent(): Boolean = handle != null || testHandlePresent

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
                // Mark Ready in case ensureReady never ran (e.g. unit-test path that calls
                // acquire directly). Production hits this only as a no-op state write since
                // ensureReady has already flipped Ready by the time any caller arrives.
                if (_state.value !is EngineState.Ready) {
                    _state.value = EngineState.Ready
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
            _state.value = EngineState.Loading(progress = null)
        }

        try {
            val newHandle = buildHandle(
                requireAudio = requireAudio,
                maxNumTokens = maxNumTokens,
            )
            handle = newHandle
            _state.value = EngineState.Ready
            return@withLock newHandle.engine
        } catch (t: Throwable) {
            _state.value = EngineState.Failed(
                reason = t.message ?: t.javaClass.simpleName,
            )
            throw t
        }
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
        _state.value = EngineState.Idle
        try { h.engine.close() } catch (t: Throwable) {
            Log.w(TAG, "release: engine.close threw", t)
        }
    }

    /**
     * 2026-05-10 experiment: try [Backend.NPU] first on Tensor G3. If
     * [Engine.initialize] (called by [engineFactory]) throws — e.g. the chip doesn't
     * support NPU for audio mode, native libs aren't present, or LiteRT-LM rejects the
     * config at runtime — fall back to [Backend.CPU] so the app stays usable instead of
     * propagating an unrecoverable engine init failure to the welcome screen.
     *
     * The NPU attempt is the test we want to run on Mark's Pixel 8 Pro: with
     * `Backend.CPU()`, Gemma 4 E2B audio mode runs ~2× slower than realtime (a 30 sec
     * audio chunk takes ~60 sec to transcribe), which is unusable for the 30-min
     * brain-dump UX. Tensor G3 has a dedicated NPU; if LiteRT-LM 0.11's NPU backend works
     * for audio mode we get realtime transcription. If it doesn't (very plausible — the
     * `nativeLibraryDir` requirement implies QC/MTK device-specific blobs that may not
     * ship with the AAR), the CPU fallback path matches the prior behaviour exactly.
     *
     * Trade-offs accepted:
     *  - Cold start cost: a failed NPU init plus a CPU retry costs whatever
     *    `Engine.initialize()` spent before throwing. Empirically (per logcat from prior
     *    sessions) failures surface in single-digit seconds on the gemma flavor; if a
     *    failure ever blocks for tens of seconds we'll need a watchdog timeout. For now
     *    we accept the synchronous fallback.
     *  - The Engine.initialize hang on Tensor G3 GPU (LiteRT-LM Issue #1860) is a SEPARATE
     *    failure mode that we deliberately do NOT trigger — we never construct
     *    [Backend.GPU] here. NPU's failure modes are different (per upstream Backend.NPU
     *    kdoc) and should surface as exceptions, not hangs.
     */
    private fun buildHandle(requireAudio: Boolean, maxNumTokens: Int): Handle {
        val cacheDir = cacheDirResolver()
        val nativeLibraryDir = nativeLibraryDirResolver()
        val attempt = tryNpuThenCpu(
            buildConfig = { backend ->
                buildEngineConfig(
                    modelProvider = modelProvider,
                    requireAudio = requireAudio,
                    maxNumTokens = maxNumTokens,
                    cacheDir = cacheDir,
                    backend = backend,
                )
            },
            buildEngine = { config -> engineFactory(config) },
            nativeLibraryDir = nativeLibraryDir,
            logTag = TAG,
        )
        Log.w(
            TAG,
            "buildHandle: shared engine ready backend=${attempt.backendKind} " +
                "audioEnabled=$requireAudio maxNumTokens=$maxNumTokens",
        )
        return Handle(
            engine = attempt.engine,
            audioEnabled = requireAudio,
            maxNumTokens = maxNumTokens,
            backendKind = attempt.backendKind,
        )
    }

    /**
     * 2026-05-10: result of the NPU-then-CPU dance in [tryNpuThenCpu]. Generic over
     * the engine type so the helper is unit-testable with a String stand-in for [Engine].
     */
    @VisibleForTesting
    internal data class BackendAttempt<E>(val engine: E, val backendKind: String)

    companion object {
        private const val TAG = "BidetSharedLiteRtEngine"

        /**
         * Backend-kind strings used in [Handle.backendKind] / [BackendAttempt.backendKind]
         * + logcat output. Kept as constants so a logcat grep ("backend=NPU" /
         * "backend=CPU") is stable across refactors.
         */
        @VisibleForTesting internal const val BACKEND_KIND_NPU = "NPU"
        @VisibleForTesting internal const val BACKEND_KIND_CPU = "CPU"

        /**
         * 2026-05-10 experiment helper: try [Backend.NPU] first; on any
         * non-fatal throwable from `buildEngine`, fall back to [Backend.CPU].
         *
         * Generic over the engine type so this is unit-testable in pure JVM with a
         * String stand-in for the LiteRT-LM [Engine] (which is final + native and not
         * mockable without a heavy mocking framework). Production passes the real
         * [Engine] type by binding `buildEngine = { config -> engineFactory(config) }`.
         *
         * Failure handling rationale:
         *  - Catches generic [Throwable] from the NPU attempt because LiteRT-LM's NPU
         *    init can surface as Kotlin exceptions, JNI exceptions, or
         *    [UnsatisfiedLinkError] depending on whether the device's NPU shim libs
         *    are present.
         *  - Re-raises [OutOfMemoryError] and [InterruptedException] without falling
         *    back: OOM is not a "NPU not available" signal (the CPU retry would
         *    likely OOM too and a process-level fail-fast is the right outcome);
         *    InterruptedException must propagate so a coroutine cancellation isn't
         *    silently swallowed.
         *  - All other throwables → log at WARN (we don't want a green CI to mask a
         *    real NPU regression) and retry with CPU. The CPU retry is allowed to
         *    propagate any throwable up — there's no second fallback.
         *
         * @param buildConfig given a [Backend], return the [EngineConfig] to pass to
         *   the engine builder. The helper invokes this twice in the fallback case
         *   (once with NPU, once with CPU) so the per-attempt config is fresh.
         * @param buildEngine constructs and initializes an engine from a config, or
         *   throws. The production binding calls
         *   [BidetSharedLiteRtEngineProvider.engineFactory].
         * @param nativeLibraryDir passed into [Backend.NPU.nativeLibraryDir]. May be
         *   null on test paths or if the Context resolver hasn't yielded a value;
         *   we coerce to an empty string in that case (matches the upstream default).
         * @param logTag the logcat tag for the warn-on-fallback line. Tests pass a
         *   sentinel; production passes [TAG].
         */
        @VisibleForTesting
        internal fun <E> tryNpuThenCpu(
            buildConfig: (Backend) -> EngineConfig,
            buildEngine: (EngineConfig) -> E,
            nativeLibraryDir: String?,
            logTag: String = TAG,
        ): BackendAttempt<E> {
            val npuConfig = buildConfig(Backend.NPU(nativeLibraryDir = nativeLibraryDir.orEmpty()))
            try {
                val engine = buildEngine(npuConfig)
                Log.w(
                    logTag,
                    "Engine acquired with backend=$BACKEND_KIND_NPU " +
                        "nativeLibraryDir=$nativeLibraryDir path=${npuConfig.modelPath}",
                )
                return BackendAttempt(engine = engine, backendKind = BACKEND_KIND_NPU)
            } catch (npuFailure: Throwable) {
                if (npuFailure is OutOfMemoryError || npuFailure is InterruptedException) {
                    throw npuFailure
                }
                Log.w(
                    logTag,
                    "NPU init failed (${npuFailure.javaClass.simpleName}: ${npuFailure.message}), " +
                        "falling back to CPU. nativeLibraryDir=$nativeLibraryDir",
                    npuFailure,
                )
            }

            val cpuConfig = buildConfig(Backend.CPU())
            val engine = buildEngine(cpuConfig)
            Log.w(
                logTag,
                "Engine acquired with backend=$BACKEND_KIND_CPU (after NPU fallback) " +
                    "path=${cpuConfig.modelPath}",
            )
            return BackendAttempt(engine = engine, backendKind = BACKEND_KIND_CPU)
        }

        /**
         * Pre-warm token budget. Matches [com.google.ai.edge.gallery.bidet.transcription.GemmaAudioEngine.MAX_OUTPUT_TOKENS]
         * (the audio engine itself is the one we're warming for the Record button gate).
         * The chat client uses the same constant; if it ever diverged a "wants more tokens"
         * warning would fire and we'd rebuild — for now the two are aligned.
         */
        private const val MAX_OUTPUT_TOKENS_PREWARM: Int = 16384

        /**
         * F3.2 (2026-05-09): pure-Kotlin helper extracted for unit testing. Builds the
         * [EngineConfig] the provider would pass to the [Engine] ctor, given a model
         * provider + the per-acquire flags. Contains the audio-mode decision (the F3.2
         * fix's key behaviour) without any [android.content.Context] machinery, so tests
         * can pin the decision without booting LiteRT-LM.
         *
         * 2026-05-09 update: backend = `Backend.CPU()` (was `Backend.GPU()`). Pixel 8 Pro's
         * Tensor G3 has no OpenCL library, and `Backend.GPU()`'s constructor silently
         * succeeds on G3 even though `Engine.initialize()` then hangs in OpenCL discovery.
         * That hang is the proximate cause of the 68-second `startForegroundDelayMs` ANR
         * Mark hit on 2026-05-09 with the gemma-flavor APK. Tracking upstream:
         * https://github.com/google-ai-edge/LiteRT-LM/issues/1860 (open as of 2026-05-09).
         * `Backend.CPU()` remains the production-stable fallback on Tensor devices today,
         * but [BidetSharedLiteRtEngineProvider.buildHandle] now tries [Backend.NPU] first
         * (2026-05-10 experiment) and falls back to CPU if NPU init throws. The visionBackend
         * stays null (we don't use vision); audioBackend stays Backend.CPU when [requireAudio]
         * — Gemma 4's audio encoder runs on CPU regardless of the main backend.
         *
         * 2026-05-10: a [backend] parameter was added with a default of `Backend.CPU()` so
         * existing callers and tests stay unchanged. The NPU-then-CPU dance lives in
         * [BidetSharedLiteRtEngineProvider.buildHandle], not here — this helper just
         * stamps whatever backend the caller asked for into the [EngineConfig].
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
            backend: Backend = Backend.CPU(),
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
                backend = backend,
                visionBackend = null,
                audioBackend = if (requireAudio) Backend.CPU() else null,
                maxNumTokens = maxNumTokens,
                cacheDir = cacheDir,
            )
        }

        /**
         * Whether app-launch prewarm should run for this build. Gates on the Gradle product
         * flavor: only the gemma flavor uses LiteRT-LM, the moonshine flavor uses sherpa-onnx
         * (which has its own much-cheaper init path; no app-launch prewarm needed).
         * Exposed via the companion so [com.google.ai.edge.gallery.GalleryApplication] can
         * decide whether to launch the prewarm coroutine without depending on [BuildConfig]
         * itself (keeps the Application class small).
         */
        fun shouldPrewarmOnAppLaunch(): Boolean = BuildConfig.USE_GEMMA_AUDIO

    }
}
