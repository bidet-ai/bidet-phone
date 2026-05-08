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
     * One-shot signal: emitted when a tab generation throws [BidetModelNotReadyException].
     * The host screen ([BidetMainScreen]) collects this and routes the user back to
     * [GemmaDownloadScreen]. Buffered so an emission while the host is composing isn't lost.
     */
    private val _modelMissingSignal =
        MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val modelMissingSignal: SharedFlow<Unit> = _modelMissingSignal.asSharedFlow()

    /** Trigger CLEAN generation for the current RAW. Caches result by SHA-256 key. */
    fun generateClean(temperature: Float = DEFAULT_TEMPERATURE) {
        generate(TAB_CLEAN, _cleanState, ASSET_CLEAN, PROMPT_VERSION_CLEAN, temperature)
    }

    fun generateAnalysis(temperature: Float = DEFAULT_TEMPERATURE) {
        generate(TAB_ANALYSIS, _analysisState, ASSET_ANALYSIS, PROMPT_VERSION_ANALYSIS, temperature)
    }

    fun generateForai(temperature: Float = DEFAULT_TEMPERATURE) {
        generate(TAB_FORAI, _foraiState, ASSET_FORAI, PROMPT_VERSION_FORAI, temperature)
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
            val key = sha256("$rawSha|$promptVersion|$temperature")
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
     * Read a prompt template. Debug builds may override via DataStore; otherwise the locked
     * v1 asset under `assets/prompts/` is the source of truth.
     */
    private suspend fun loadPromptText(tabId: String, assetPath: String): String {
        val override = readPromptOverride(tabId)
        if (!override.isNullOrBlank()) return override
        return context.assets.open(assetPath).bufferedReader().use { it.readText() }
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
        const val PROMPT_VERSION_FORAI: String = "v1"

        const val DEFAULT_TEMPERATURE: Float = 0.4f
        const val MAX_OUTPUT_TOKENS: Int = 1024

        /**
         * No-op aggregator returned before the bound service publishes its Pipeline. Empty
         * `rawFlow` keeps the RAW tab benign rather than NPE-ing.
         */
        private val PLACEHOLDER_AGGREGATOR = TranscriptAggregator()

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
