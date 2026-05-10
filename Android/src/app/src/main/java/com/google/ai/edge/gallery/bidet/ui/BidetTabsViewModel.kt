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

package com.google.ai.edge.gallery.bidet.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.bidet.service.CleanGenerationService
import com.google.ai.edge.gallery.bidet.transcript.TranscriptAggregator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Top-level state holder for [BidetTabsScreen]. v0.2 three-tab restructure:
 *  - Tab 1 RAW                  — owned by the aggregator, no state in this VM.
 *  - Tab 2 "Clean for me"       — RECEPTIVE Support axis ([SupportAxis.RECEPTIVE]).
 *  - Tab 3 "Clean for others"   — EXPRESSIVE Support axis ([SupportAxis.EXPRESSIVE]).
 *
 * Each Clean tab carries its own active-preset selection + its own free-form custom-prompt
 * draft, both persisted to DataStore. The cache key includes a hash of the resolved system
 * prompt so flipping presets shows fresh output rather than a stale cache hit.
 *
 * v0.2 streaming-generation refactor (2026-05-09): generation now runs in
 * [com.google.ai.edge.gallery.bidet.service.CleanGenerationService] (a foreground dataSync
 * service) so a 30-min brain dump's 3-4 minute generation actually finishes — even if the
 * screen sleeps mid-decode. The ViewModel binds to the service, observes its [StateFlow], and
 * maps the streaming state into the per-axis [TabState] flow that the Compose UI already
 * collects. The on-disk cache shape (`tab_cache_<sha>`) is unchanged — we still write the
 * final text on success.
 */
@HiltViewModel
class BidetTabsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private var _aggregator: TranscriptAggregator? = null
    /** Aggregator surface for the RAW tab. Backed by an empty placeholder until [attachAggregator]. */
    val aggregator: TranscriptAggregator
        get() = _aggregator ?: PLACEHOLDER_AGGREGATOR

    /** True when the service-owned aggregator has been attached and the RAW tab will be live. */
    val hasAggregator: Boolean
        get() = _aggregator != null

    /**
     * Wire in the live [TranscriptAggregator] supplied by the bound [com.google.ai.edge.gallery.bidet.service.RecordingService].
     * Idempotent — re-attaching the same instance is a no-op.
     */
    fun attachAggregator(a: TranscriptAggregator) {
        if (_aggregator !== a) {
            _aggregator = a
        }
    }

    /** Tab 2 "Clean for me" / RECEPTIVE Support output state. */
    private val _receptiveState = MutableStateFlow<TabState>(TabState.Idle)
    val receptiveState: StateFlow<TabState> = _receptiveState.asStateFlow()

    /** Tab 3 "Clean for others" / EXPRESSIVE Support output state. */
    private val _expressiveState = MutableStateFlow<TabState>(TabState.Idle)
    val expressiveState: StateFlow<TabState> = _expressiveState.asStateFlow()

    /** Active preset chip on the Receptive (Clean for me) tab. Default = Summary. */
    private val _receptivePreset = MutableStateFlow(SupportPreset.RECEPTIVE_SUMMARY.id)
    val receptivePreset: StateFlow<String> = _receptivePreset.asStateFlow()

    /** User's free-form Receptive custom prompt (used iff active preset = RECEPTIVE_CUSTOM). */
    private val _receptiveCustomPrompt = MutableStateFlow("")
    val receptiveCustomPrompt: StateFlow<String> = _receptiveCustomPrompt.asStateFlow()

    /** Active preset chip on the Expressive (Clean for others) tab. Default = AI-ingestion markdown. */
    private val _expressivePreset = MutableStateFlow(SupportPreset.EXPRESSIVE_AI_INGEST.id)
    val expressivePreset: StateFlow<String> = _expressivePreset.asStateFlow()

    /** User's free-form Expressive custom prompt (used iff active preset = EXPRESSIVE_CUSTOM). */
    private val _expressiveCustomPrompt = MutableStateFlow("")
    val expressiveCustomPrompt: StateFlow<String> = _expressiveCustomPrompt.asStateFlow()

    /**
     * One-shot signal: emitted when a tab generation throws [BidetModelNotReadyException].
     * The host screen ([BidetMainScreen]) collects this and routes the user back to
     * [GemmaDownloadScreen]. Buffered so an emission while the host is composing isn't lost.
     */
    private val _modelMissingSignal =
        MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val modelMissingSignal: SharedFlow<Unit> = _modelMissingSignal.asSharedFlow()

    /**
     * Service-binding state. `boundService` is non-null while the activity holds a binding;
     * the per-axis `observerJob` is the coroutine collecting the service's StateFlow → mapping
     * into [TabState].
     */
    @Volatile private var boundService: CleanGenerationService? = null
    private var serviceConnection: ServiceConnection? = null
    private var receptiveObserverJob: Job? = null
    private var expressiveObserverJob: Job? = null
    /** Tracks the in-flight cache key per axis so the StateFlow observer can write on Done. */
    private val pendingCacheKey = mutableMapOf<SupportAxis, String>()

    init {
        // Hydrate per-axis preset selection + custom prompt from DataStore so persistence
        // survives across recompositions and app restarts.
        viewModelScope.launch {
            val prefs = context.bidetDataStore.data.first()
            prefs[KEY_RECEPTIVE_PRESET]?.let { saved ->
                val p = SupportPreset.byId(saved)
                if (p != null && p.axis == SupportAxis.RECEPTIVE) _receptivePreset.value = saved
            }
            prefs[KEY_RECEPTIVE_CUSTOM_PROMPT]?.let { _receptiveCustomPrompt.value = it }
            prefs[KEY_EXPRESSIVE_PRESET]?.let { saved ->
                val p = SupportPreset.byId(saved)
                if (p != null && p.axis == SupportAxis.EXPRESSIVE) _expressivePreset.value = saved
            }
            prefs[KEY_EXPRESSIVE_CUSTOM_PROMPT]?.let { _expressiveCustomPrompt.value = it }
        }
        bindCleanGenerationService()
    }

    /** Trigger Receptive (Clean for me) generation against the current RAW. */
    fun generateReceptive(temperature: Float = DEFAULT_TEMPERATURE) {
        generate(SupportAxis.RECEPTIVE, _receptiveState, temperature)
    }

    /** Trigger Expressive (Clean for others) generation against the current RAW. */
    fun generateExpressive(temperature: Float = DEFAULT_TEMPERATURE) {
        generate(SupportAxis.EXPRESSIVE, _expressiveState, temperature)
    }

    /**
     * Switch the active preset chip on a given axis. Persisted to DataStore. Resets the cached
     * tab state to [TabState.Idle] so the user is not left looking at output generated under
     * the previous preset's prompt.
     */
    fun setPreset(axis: SupportAxis, presetId: String) {
        val preset = SupportPreset.byId(presetId) ?: return
        if (preset.axis != axis) return
        val flow = presetFlowFor(axis)
        if (flow.value == presetId) return
        flow.value = presetId
        stateFlowFor(axis).value = TabState.Idle
        viewModelScope.launch {
            context.bidetDataStore.edit { prefs ->
                prefs[presetKeyFor(axis)] = presetId
            }
        }
    }

    /**
     * Persist the user's free-form custom prompt for an axis. If the active preset on that
     * axis is the axis-specific custom preset, reset the cached tab state (the prompt body —
     * and therefore expected output — has changed).
     */
    fun setCustomPrompt(axis: SupportAxis, text: String) {
        val flow = customPromptFlowFor(axis)
        if (flow.value == text) return
        flow.value = text
        val customId = when (axis) {
            SupportAxis.RECEPTIVE -> SupportPreset.RECEPTIVE_CUSTOM.id
            SupportAxis.EXPRESSIVE -> SupportPreset.EXPRESSIVE_CUSTOM.id
        }
        if (presetFlowFor(axis).value == customId) {
            stateFlowFor(axis).value = TabState.Idle
        }
        viewModelScope.launch {
            context.bidetDataStore.edit { prefs ->
                prefs[customPromptKeyFor(axis)] = text
            }
        }
    }

    private fun generate(
        axis: SupportAxis,
        target: MutableStateFlow<TabState>,
        temperature: Float,
    ) {
        viewModelScope.launch {
            val raw = aggregator.currentText()
            if (raw.isBlank()) {
                target.value = TabState.Failed("RAW transcript is empty.")
                return@launch
            }
            val systemPrompt = resolveSystemPrompt(axis)
            val rawSha = sha256(raw)
            // Cache key includes a short hash of the system prompt so any prompt change
            // (preset switch, custom-prompt edit) invalidates the cache without requiring a
            // manual promptVersion bump.
            val promptHash = sha256(systemPrompt).take(8)
            val promptVersion = when (axis) {
                SupportAxis.RECEPTIVE -> PROMPT_VERSION_RECEPTIVE
                SupportAxis.EXPRESSIVE -> PROMPT_VERSION_EXPRESSIVE
            }
            val key = sha256("$rawSha|$promptVersion|$promptHash|$temperature")
            val cached = readCache(key)
            if (cached != null) {
                target.value = TabState.Cached(cached.first, cached.second)
                return@launch
            }
            // Move to a known-streaming initial state so the UI flips immediately to the
            // progress affordance. The token count is 0 until the first chunk lands.
            target.value = TabState.Streaming(
                partialText = "",
                tokenCount = 0,
                tokenCap = CLEAN_TAB_OUTPUT_TOKEN_CAP,
            )
            pendingCacheKey[axis] = key
            startGenerationService(
                axis = axis,
                systemPrompt = systemPrompt,
                userPrompt = raw,
                temperature = temperature,
                cacheKey = key,
            )
        }
    }

    /**
     * Resolve the system-prompt body for [axis]. Resolution order:
     *  1. The active preset selection (which may be a built-in or the axis-specific custom).
     *     [SupportPromptResolver] handles unknown ids + blank custom prompts.
     *  2. Debug-build override: the [BidetSettingsScreen] writes raw template text to
     *     DataStore for prompt-iteration without rebuilding the APK. Honoured AFTER the
     *     preset selection so a debug user can still test their preset chips.
     *
     * This is the unified resolver — there is no longer a separate FORAI-default fallthrough
     * because every preset (including the legacy "FORAI default") is first-class in
     * [SupportPreset].
     */
    private suspend fun resolveSystemPrompt(axis: SupportAxis): String {
        val activePresetId = presetFlowFor(axis).value
        val customPrompt = customPromptFlowFor(axis).value

        val override = readPromptOverride(axis)
        if (!override.isNullOrBlank()) return override

        return SupportPromptResolver.resolve(
            axis = axis,
            activePresetId = activePresetId,
            customPrompt = customPrompt,
            loadAsset = { path ->
                context.assets.open(path).bufferedReader().use { it.readText() }
            },
        )
    }

    private suspend fun readPromptOverride(axis: SupportAxis): String? {
        val prefs = context.bidetDataStore.data.first()
        return prefs[promptOverrideKey(axis)]
    }

    /** Persist an in-memory cache entry alongside the existing DataStore. */
    private suspend fun writeCache(key: String, text: String, generatedAt: Long) {
        context.bidetDataStore.edit { prefs ->
            prefs[stringPreferencesKey("tab_cache_$key")] = "$generatedAt|$text"
        }
    }

    /** @return Pair<text, generatedAt> if a cache entry exists for [key], else null. */
    private suspend fun readCache(key: String): Pair<String, Long>? {
        val prefs = context.bidetDataStore.data.first()
        val raw = prefs[stringPreferencesKey("tab_cache_$key")] ?: return null
        val sep = raw.indexOf('|')
        if (sep <= 0) return null
        val ts = raw.substring(0, sep).toLongOrNull() ?: return null
        val txt = raw.substring(sep + 1)
        return txt to ts
    }

    /** Reset all tab states (called when a new recording session starts). */
    fun resetTabs() {
        _receptiveState.value = TabState.Idle
        _expressiveState.value = TabState.Idle
    }

    /**
     * Bind to the [CleanGenerationService] so the ViewModel can start generations + observe
     * their progress. The binding outlives the Compose host: even if the user navigates away
     * from the Clean tabs, the service keeps generating and the StateFlow keeps updating
     * `_receptiveState` / `_expressiveState`. When the user returns, the flow already holds
     * the latest progress (or the final Done text).
     */
    private fun bindCleanGenerationService() {
        if (serviceConnection != null) return
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = (binder as? CleanGenerationService.LocalBinder)?.service() ?: return
                boundService = svc
                // Re-attach observer jobs against the live StateFlow. We collect once per
                // axis; the service publishes to the same flow regardless of axis, so each
                // observer filters on its own axis.
                receptiveObserverJob?.cancel()
                receptiveObserverJob = viewModelScope.launch {
                    svc.stateFlow.collect { handleServiceState(it, SupportAxis.RECEPTIVE) }
                }
                expressiveObserverJob?.cancel()
                expressiveObserverJob = viewModelScope.launch {
                    svc.stateFlow.collect { handleServiceState(it, SupportAxis.EXPRESSIVE) }
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                boundService = null
            }
        }
        serviceConnection = connection
        val intent = Intent(context, CleanGenerationService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Map a single [CleanGenerationService.GenerationState] event into our per-axis
     * [TabState]. Filters by axis so the receptive observer ignores expressive events and
     * vice-versa. Done events are persisted to the DataStore cache via [writeCache].
     */
    internal fun handleServiceState(
        state: CleanGenerationService.GenerationState,
        observerAxis: SupportAxis,
    ) {
        when (state) {
            is CleanGenerationService.GenerationState.Idle -> { /* keep current */ }
            is CleanGenerationService.GenerationState.Streaming -> {
                if (state.axis != observerAxis) return
                stateFlowFor(observerAxis).value = TabState.Streaming(
                    partialText = state.partialText,
                    tokenCount = state.tokenCount,
                    tokenCap = state.tokenCap,
                )
            }
            is CleanGenerationService.GenerationState.Done -> {
                if (state.axis != observerAxis) return
                stateFlowFor(observerAxis).value = TabState.Cached(state.text, state.finishedAtMs)
                val key = pendingCacheKey.remove(observerAxis)
                if (key != null) {
                    viewModelScope.launch {
                        try { writeCache(key, state.text, state.finishedAtMs) }
                        catch (_: Throwable) { /* DataStore IO; caller already sees the text. */ }
                    }
                }
            }
            is CleanGenerationService.GenerationState.Failed -> {
                if (state.axis != observerAxis) return
                stateFlowFor(observerAxis).value = TabState.Failed(state.message)
                pendingCacheKey.remove(observerAxis)
                if (state.modelMissing) {
                    _modelMissingSignal.tryEmit(Unit)
                }
            }
        }
    }

    /** Fire the foreground service start intent with all generation parameters. */
    private fun startGenerationService(
        axis: SupportAxis,
        systemPrompt: String,
        userPrompt: String,
        temperature: Float,
        cacheKey: String,
    ) {
        ContextCompat.startForegroundService(
            context,
            CleanGenerationService.startIntent(
                context = context,
                sessionId = cacheKey,
                axis = axis,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                tokenCap = CLEAN_TAB_OUTPUT_TOKEN_CAP,
                temperature = temperature,
            ),
        )
    }

    override fun onCleared() {
        super.onCleared()
        receptiveObserverJob?.cancel()
        expressiveObserverJob?.cancel()
        serviceConnection?.let {
            try { context.unbindService(it) } catch (_: Throwable) { /* never bound or already unbound */ }
        }
        serviceConnection = null
        boundService = null
    }

    private fun presetFlowFor(axis: SupportAxis): MutableStateFlow<String> = when (axis) {
        SupportAxis.RECEPTIVE -> _receptivePreset
        SupportAxis.EXPRESSIVE -> _expressivePreset
    }

    private fun customPromptFlowFor(axis: SupportAxis): MutableStateFlow<String> = when (axis) {
        SupportAxis.RECEPTIVE -> _receptiveCustomPrompt
        SupportAxis.EXPRESSIVE -> _expressiveCustomPrompt
    }

    private fun stateFlowFor(axis: SupportAxis): MutableStateFlow<TabState> = when (axis) {
        SupportAxis.RECEPTIVE -> _receptiveState
        SupportAxis.EXPRESSIVE -> _expressiveState
    }

    /** Snapshot of the current RAW transcript — used by the diff-toggle UI. */
    fun currentRaw(): String = aggregator.currentText()

    companion object {
        // Bumping these strings invalidates cached generations. Bump when the resolver layer
        // changes shape. The promptHash component of the cache key absorbs day-to-day
        // preset / custom-prompt edits.
        // Bumped to v2 (2026-05-09): Clean-tab default prompts rewritten for fidelity-first
        // behavior — Gemma 4 E4B was hallucinating proper nouns ("Hasspin", "Zenabria") not
        // present in the RAW transcript. New rules forbid invention and require verbatim
        // preservation of names/numbers. See fix/clean-tab-prompt-fidelity.
        const val PROMPT_VERSION_RECEPTIVE: String = "v2"
        const val PROMPT_VERSION_EXPRESSIVE: String = "v2"

        // Lowered from 0.4f → 0.3f (2026-05-09) alongside the v2 fidelity prompts. Lower
        // temperature reduces the model's tendency to invent plausible-sounding words —
        // exactly the failure mode that produced the hallucinated names. The Gemma-audio
        // path's much lower 0.05 temperature (set by PR #23 for transcription accuracy) is
        // unrelated and untouched.
        const val DEFAULT_TEMPERATURE: Float = 0.3f

        // On-device Gemma 4 generation runs at ~10 tk/s on a Pixel 8 Pro, so 2048 tokens
        // ≈ 3-4 minutes wall-clock — the upper bound users will tolerate before feedback.
        // Not tied to any contest word limit. Input cap is separate (full Gemma 4 context
        // window of 128k); only output is bounded here.
        const val CLEAN_TAB_OUTPUT_TOKEN_CAP: Int = 2048

        /**
         * No-op aggregator returned before the bound service publishes its Pipeline. Empty
         * `rawFlow` keeps the RAW tab benign rather than NPE-ing.
         */
        private val PLACEHOLDER_AGGREGATOR = TranscriptAggregator()

        // DataStore keys for the v0.2 per-axis preset selection + custom prompt.
        val KEY_RECEPTIVE_PRESET: Preferences.Key<String> =
            stringPreferencesKey("receptive_active_preset")
        val KEY_RECEPTIVE_CUSTOM_PROMPT: Preferences.Key<String> =
            stringPreferencesKey("receptive_custom_prompt")
        val KEY_EXPRESSIVE_PRESET: Preferences.Key<String> =
            stringPreferencesKey("expressive_active_preset")
        val KEY_EXPRESSIVE_CUSTOM_PROMPT: Preferences.Key<String> =
            stringPreferencesKey("expressive_custom_prompt")

        fun presetKeyFor(axis: SupportAxis): Preferences.Key<String> = when (axis) {
            SupportAxis.RECEPTIVE -> KEY_RECEPTIVE_PRESET
            SupportAxis.EXPRESSIVE -> KEY_EXPRESSIVE_PRESET
        }

        fun customPromptKeyFor(axis: SupportAxis): Preferences.Key<String> = when (axis) {
            SupportAxis.RECEPTIVE -> KEY_RECEPTIVE_CUSTOM_PROMPT
            SupportAxis.EXPRESSIVE -> KEY_EXPRESSIVE_CUSTOM_PROMPT
        }

        fun promptOverrideKey(axis: SupportAxis): Preferences.Key<String> {
            val tag = when (axis) {
                SupportAxis.RECEPTIVE -> "receptive"
                SupportAxis.EXPRESSIVE -> "expressive"
            }
            return stringPreferencesKey("bidet_prompt_override_$tag")
        }

        internal fun sha256(s: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/** Bidet-scoped DataStore. One file per app process. */
val Context.bidetDataStore by preferencesDataStore(name = "bidet")
