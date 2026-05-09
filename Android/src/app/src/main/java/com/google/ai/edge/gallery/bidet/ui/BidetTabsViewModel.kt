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

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.bidet.transcript.TranscriptAggregator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Top-level state holder for [BidetTabsScreen]. Owns:
 *  - per-tab [TabState] flows (CLEAN / ANALYSIS / FORAI; RAW is owned by the aggregator).
 *  - the prompt-template loader (asset → string, with an optional DataStore override in
 *    debug builds; brief §11 BidetSettingsScreen wires the override).
 *  - cache lookups keyed by SHA-256(rawSha + promptVersion + temperature) per brief §7.
 *
 * Phase 2 wiring: this is a [HiltViewModel]. The [BidetGemmaClient] binding is provided by
 * [com.google.ai.edge.gallery.bidet.di.BidetModule]. The aggregator is provided lazily —
 * [com.google.ai.edge.gallery.bidet.ui.BidetMainScreen] binds to [com.google.ai.edge.gallery.bidet.service.RecordingService]
 * and calls [attachAggregator] once the service's Pipeline is live. Until then the tab
 * generators no-op (returning a friendly empty-RAW message).
 */
@HiltViewModel
class BidetTabsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gemma: BidetGemmaClient,
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

    private val _cleanState = MutableStateFlow<TabState>(TabState.Idle)
    val cleanState: StateFlow<TabState> = _cleanState.asStateFlow()

    private val _analysisState = MutableStateFlow<TabState>(TabState.Idle)
    val analysisState: StateFlow<TabState> = _analysisState.asStateFlow()

    private val _foraiState = MutableStateFlow<TabState>(TabState.Idle)
    val foraiState: StateFlow<TabState> = _foraiState.asStateFlow()

    /**
     * Tab 4 preset state. Mirrors [Tab4Preset.id]. Default = [Tab4Preset.FORAI] so the v0.1
     * behaviour (structured-markdown-for-AI-ingestion) is preserved when the user has never
     * touched the chip row. Persisted to DataStore under [KEY_TAB4_ACTIVE_PRESET].
     */
    private val _tab4ActivePreset = MutableStateFlow(Tab4Preset.FORAI.id)
    val tab4ActivePreset: StateFlow<String> = _tab4ActivePreset.asStateFlow()

    /**
     * Free-form custom prompt the user has entered via the Tab 4 BottomSheet. Used only when
     * [tab4ActivePreset] == [Tab4Preset.CUSTOM]. Persisted to DataStore under
     * [KEY_TAB4_CUSTOM_PROMPT].
     */
    private val _tab4CustomPrompt = MutableStateFlow("")
    val tab4CustomPrompt: StateFlow<String> = _tab4CustomPrompt.asStateFlow()

    /**
     * One-shot signal: emitted when a tab generation throws [BidetModelNotReadyException].
     * The host screen ([BidetMainScreen]) collects this and routes the user back to
     * [GemmaDownloadScreen]. Buffered so an emission while the host is composing isn't lost.
     */
    private val _modelMissingSignal =
        MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val modelMissingSignal: SharedFlow<Unit> = _modelMissingSignal.asSharedFlow()

    init {
        // Hydrate Tab 4 selection from DataStore so it persists across recompositions and
        // app restarts. If nothing was ever saved, the defaults (FORAI preset, empty custom)
        // are kept.
        viewModelScope.launch {
            val prefs = context.bidetDataStore.data.first()
            prefs[KEY_TAB4_ACTIVE_PRESET]?.let { saved ->
                if (Tab4Preset.byId(saved) != null) _tab4ActivePreset.value = saved
            }
            prefs[KEY_TAB4_CUSTOM_PROMPT]?.let { _tab4CustomPrompt.value = it }
        }
    }

    /** Trigger CLEAN generation for the current RAW. Caches result by SHA-256 key. */
    fun generateClean(temperature: Float = DEFAULT_TEMPERATURE) {
        generate(TAB_CLEAN, _cleanState, ASSET_CLEAN, PROMPT_VERSION_CLEAN, temperature)
    }

    fun generateAnalysis(temperature: Float = DEFAULT_TEMPERATURE) {
        generate(TAB_ANALYSIS, _analysisState, ASSET_ANALYSIS, PROMPT_VERSION_ANALYSIS, temperature)
    }

    /**
     * Trigger Tab 4 (the customizable prompt tab) generation. The system prompt is resolved
     * at call time from the current [tab4ActivePreset] / [tab4CustomPrompt] selection. The
     * cache key incorporates a short hash of the active prompt so flipping presets shows
     * fresh output rather than the previous preset's cached result.
     */
    fun generateForai(temperature: Float = DEFAULT_TEMPERATURE) {
        generate(TAB_FORAI, _foraiState, ASSET_FORAI, PROMPT_VERSION_FORAI, temperature)
    }

    /**
     * Switch the active Tab 4 preset chip. Persisted to DataStore. Side-effect: the cached
     * Tab 4 state is reset to [TabState.Idle] so the user is not left looking at output
     * generated under the previous preset's prompt.
     */
    fun setTab4Preset(presetId: String) {
        if (Tab4Preset.byId(presetId) == null) return
        if (_tab4ActivePreset.value == presetId) return
        _tab4ActivePreset.value = presetId
        _foraiState.value = TabState.Idle
        viewModelScope.launch {
            context.bidetDataStore.edit { prefs ->
                prefs[KEY_TAB4_ACTIVE_PRESET] = presetId
            }
        }
    }

    /**
     * Persist the user's free-form custom prompt (saved when they tap "Save" on the
     * BottomSheet). If the active preset is currently [Tab4Preset.CUSTOM], the cached state
     * is reset because the prompt body — and therefore expected output — has changed.
     */
    fun setTab4CustomPrompt(text: String) {
        if (_tab4CustomPrompt.value == text) return
        _tab4CustomPrompt.value = text
        if (_tab4ActivePreset.value == Tab4Preset.CUSTOM.id) {
            _foraiState.value = TabState.Idle
        }
        viewModelScope.launch {
            context.bidetDataStore.edit { prefs ->
                prefs[KEY_TAB4_CUSTOM_PROMPT] = text
            }
        }
    }

    private fun generate(
        tabId: String,
        target: MutableStateFlow<TabState>,
        assetPath: String,
        promptVersion: String,
        temperature: Float,
    ) {
        viewModelScope.launch {
            val raw = aggregator.currentText()
            if (raw.isBlank()) {
                target.value = TabState.Failed("RAW transcript is empty.")
                return@launch
            }
            val systemPrompt = loadPromptText(tabId, assetPath)
            val rawSha = sha256(raw)
            // Cache key includes a short hash of the system prompt so any prompt change
            // (preset switch, custom-prompt edit, debug override edit) invalidates the
            // cache without requiring a manual promptVersion bump.
            val promptHash = sha256(systemPrompt).take(8)
            val key = sha256("$rawSha|$promptVersion|$promptHash|$temperature")
            val cached = readCache(key)
            if (cached != null) {
                target.value = TabState.Cached(cached.first, cached.second)
                return@launch
            }
            target.value = TabState.Generating
            try {
                val result = withContext(Dispatchers.Default) {
                    gemma.runInference(
                        systemPrompt = systemPrompt,
                        userPrompt = raw,
                        maxOutputTokens = MAX_OUTPUT_TOKENS,
                        temperature = temperature,
                    )
                }
                val now = System.currentTimeMillis()
                writeCache(key, result, now)
                target.value = TabState.Cached(result, now)
            } catch (t: Throwable) {
                target.value = TabState.Failed(t.message ?: "Generation failed")
                if (t is BidetModelNotReadyException) {
                    _modelMissingSignal.tryEmit(Unit)
                }
            }
        }
    }

    /**
     * Read a prompt template. Resolution order:
     *  1. For Tab 4 (FORAI): the active preset selection — including a free-form custom
     *     prompt — takes priority. This is the v0.2 customizable-prompt feature.
     *  2. Debug-build override: the [BidetSettingsScreen] writes raw template text to
     *     DataStore for prompt-iteration without rebuilding the APK.
     *  3. The bundled asset under `assets/prompts/` (v1, locked).
     */
    private suspend fun loadPromptText(tabId: String, assetPath: String): String {
        if (tabId == TAB_FORAI) {
            val tab4Resolved = resolveTab4Prompt()
            if (!tab4Resolved.isNullOrBlank()) return tab4Resolved
        }
        val override = readPromptOverride(tabId)
        if (!override.isNullOrBlank()) return override
        return context.assets.open(assetPath).bufferedReader().use { it.readText() }
    }

    /**
     * Resolve the Tab 4 prompt body from the currently active preset. Returns null when the
     * preset is [Tab4Preset.FORAI] (signaling the caller should fall through to the existing
     * debug override / bundled asset path), so the v0.1 default behaviour is byte-identical.
     *
     * Delegates rule logic to [Tab4PromptResolver] so the same state machine is exercised
     * in unit tests without needing Android Context.
     */
    private fun resolveTab4Prompt(): String? {
        return Tab4PromptResolver.resolve(
            activePresetId = _tab4ActivePreset.value,
            customPrompt = _tab4CustomPrompt.value,
            loadAsset = { path ->
                context.assets.open(path).bufferedReader().use { it.readText() }
            },
        )
    }

    private suspend fun readPromptOverride(tabId: String): String? {
        val prefs = context.bidetDataStore.data.first()
        return prefs[promptOverrideKey(tabId)]
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
        _cleanState.value = TabState.Idle
        _analysisState.value = TabState.Idle
        _foraiState.value = TabState.Idle
    }

    companion object {
        private const val TAB_CLEAN = "clean"
        private const val TAB_ANALYSIS = "analysis"
        private const val TAB_FORAI = "forai"

        private const val ASSET_CLEAN = "prompts/clean.txt"
        private const val ASSET_ANALYSIS = "prompts/analysis.txt"
        private const val ASSET_FORAI = "prompts/forai.txt"

        // Bumping these strings invalidates cached generations. Bump when a prompt changes.
        const val PROMPT_VERSION_CLEAN: String = "v1"
        const val PROMPT_VERSION_ANALYSIS: String = "v1"
        // v2 (2026-05-09): Tab 4 became the customizable-prompt tab. The cache key now
        // also hashes the active prompt body, but bumping the version invalidates any
        // pre-v0.2 FORAI entries that didn't include a promptHash component.
        const val PROMPT_VERSION_FORAI: String = "v2"

        const val DEFAULT_TEMPERATURE: Float = 0.4f
        const val MAX_OUTPUT_TOKENS: Int = 1024

        /**
         * No-op aggregator returned before the bound service publishes its Pipeline. Empty
         * `rawFlow` keeps the RAW tab benign rather than NPE-ing.
         */
        private val PLACEHOLDER_AGGREGATOR = TranscriptAggregator()

        // DataStore keys for the v0.2 customizable-prompt feature (Tab 4).
        val KEY_TAB4_ACTIVE_PRESET: Preferences.Key<String> =
            stringPreferencesKey("tab4_active_preset")
        val KEY_TAB4_CUSTOM_PROMPT: Preferences.Key<String> =
            stringPreferencesKey("tab4_custom_prompt")

        fun promptOverrideKey(tabId: String): Preferences.Key<String> =
            stringPreferencesKey("bidet_prompt_override_$tabId")

        internal fun sha256(s: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/** Bidet-scoped DataStore. One file per app process. */
val Context.bidetDataStore by preferencesDataStore(name = "bidet")
