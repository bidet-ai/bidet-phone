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
import com.google.ai.edge.gallery.bidet.cleaning.Glossary
import com.google.ai.edge.gallery.bidet.service.CleanGenerationService
import com.google.ai.edge.gallery.bidet.transcript.TranscriptAggregator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Top-level state holder for [BidetTabsScreen] under the three-tab restructure (v20,
 * 2026-05-11; was two-tab 2026-05-10):
 *  - RAW lives at the top of the screen as a reading base, owned by the aggregator.
 *  - THREE GENERATED tabs live below it. All tabs' label + prompt template are user-editable
 *    via [TabPrefEditorSheet]; persistence is delegated to [TabPrefRepository].
 *
 * Internal axis names ([SupportAxis.RECEPTIVE] / [SupportAxis.EXPRESSIVE] / [SupportAxis.JUDGES])
 * are retained because [com.google.ai.edge.gallery.bidet.service.CleanGenerationService] uses
 * them to pick a notification text and to keep the streams distinguishable.
 *
 * Generation flow (unchanged contract):
 *  1. Read the active [TabPref] for the axis.
 *  2. Resolve the system prompt (debug override → user-edited prompt → bundled default).
 *  3. Hash the resolved prompt + RAW + temperature → cache key.
 *  4. Cache hit → publish Cached state and return. Cache miss → start
 *     [CleanGenerationService] and let its StateFlow drive [TabState] updates.
 */
@HiltViewModel
class BidetTabsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val tabPrefRepo: TabPrefRepository = DataStoreTabPrefRepository(context)

    private var _aggregator: TranscriptAggregator? = null
    val aggregator: TranscriptAggregator
        get() = _aggregator ?: PLACEHOLDER_AGGREGATOR

    val hasAggregator: Boolean
        get() = _aggregator != null

    fun attachAggregator(a: TranscriptAggregator) {
        if (_aggregator !== a) _aggregator = a
    }

    private val _receptiveState = MutableStateFlow<TabState>(TabState.Idle)
    val receptiveState: StateFlow<TabState> = _receptiveState.asStateFlow()

    private val _expressiveState = MutableStateFlow<TabState>(TabState.Idle)
    val expressiveState: StateFlow<TabState> = _expressiveState.asStateFlow()

    /**
     * v20 (2026-05-11): JUDGES state flow. The Clean-for-judges contest-pitch tab targets
     * ~800-1200 words of output — longer than the other two axes. Wall-clock on Tensor G3
     * CPU at the standard 2048 maxNumTokens cap is ~6-10 min (documented in the v20 PR body).
     * Same streaming contract as RECEPTIVE/EXPRESSIVE so the UI can render partial output
     * as Gemma decodes.
     */
    private val _judgesState = MutableStateFlow<TabState>(TabState.Idle)
    val judgesState: StateFlow<TabState> = _judgesState.asStateFlow()

    /**
     * Live [TabPref] list — index 0 is RECEPTIVE, index 1 is EXPRESSIVE, index 2 (v20) is
     * JUDGES. The chip row + sheet editor read this; [refreshTabPrefs] reloads it from the
     * repository.
     */
    private val _tabPrefs = MutableStateFlow<List<TabPref>>(
        SupportAxis.ALL.map { axis -> TabPref(axis, TabPref.defaultLabel(axis), "") }
    )
    val tabPrefs: StateFlow<List<TabPref>> = _tabPrefs.asStateFlow()

    /**
     * Currently-selected axis on the chip row. Drives which TabState the parent collects.
     * Defaults to RECEPTIVE (slot 0).
     */
    private val _activeAxis = MutableStateFlow(SupportAxis.RECEPTIVE)
    val activeAxis: StateFlow<SupportAxis> = _activeAxis.asStateFlow()

    private val _modelMissingSignal =
        MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val modelMissingSignal: SharedFlow<Unit> = _modelMissingSignal.asSharedFlow()

    @Volatile private var boundService: CleanGenerationService? = null
    private var serviceConnection: ServiceConnection? = null
    private var receptiveObserverJob: Job? = null
    private var expressiveObserverJob: Job? = null
    // v20 (2026-05-11): third observer job for the Clean-for-judges contest-pitch tab.
    private var judgesObserverJob: Job? = null
    private val pendingCacheKey = mutableMapOf<SupportAxis, String>()

    init {
        viewModelScope.launch { refreshTabPrefs() }
        bindCleanGenerationService()
    }

    /** Reload every axis's [TabPref] from the repository. Called on init + after each save/reset. */
    suspend fun refreshTabPrefs() {
        val refreshed = SupportAxis.ALL.map { axis ->
            val defaultPrompt = loadDefaultPromptAsset(axis)
            tabPrefRepo.read(axis, defaultPrompt)
        }
        _tabPrefs.value = refreshed
    }

    /** Persist a user edit (label and/or prompt). Refreshes the snapshot afterwards. */
    fun saveTabPref(pref: TabPref) {
        viewModelScope.launch {
            tabPrefRepo.write(pref)
            refreshTabPrefs()
            // A prompt edit invalidates the cache (the cache key includes the prompt hash),
            // but the user might also have rerun the tab UNDER the previous prompt. Reset the
            // axis's TabState to Idle so the next Generate runs the new prompt rather than
            // serving the previous Cached output.
            stateFlowFor(pref.axis).value = TabState.Idle
        }
    }

    /** Reset the [TabPref] for an axis back to the bundled defaults. */
    fun resetTabPref(axis: SupportAxis) {
        viewModelScope.launch {
            tabPrefRepo.resetToDefault(axis)
            refreshTabPrefs()
            stateFlowFor(axis).value = TabState.Idle
        }
    }

    /** Switch which axis the chip row is highlighting (and which TabState the parent collects). */
    fun selectAxis(axis: SupportAxis) {
        _activeAxis.value = axis
    }

    fun generateReceptive(temperature: Float = DEFAULT_TEMPERATURE) {
        generate(SupportAxis.RECEPTIVE, _receptiveState, temperature)
    }

    fun generateExpressive(temperature: Float = DEFAULT_TEMPERATURE) {
        generate(SupportAxis.EXPRESSIVE, _expressiveState, temperature)
    }

    /**
     * v20 (2026-05-11): trigger generation for the Clean-for-judges contest-pitch tab.
     * Reuses the same single-call / chunked generation path as the other two axes — the
     * longer ~800-1200 word target is enforced by the prompt body, not by a different
     * token cap (RawChunker still chunks when input exceeds the ~700-token input ceiling).
     */
    fun generateJudges(temperature: Float = DEFAULT_TEMPERATURE) {
        generate(SupportAxis.JUDGES, _judgesState, temperature)
    }

    /** Trigger generation for the currently-selected axis. */
    fun generateActive(temperature: Float = DEFAULT_TEMPERATURE) {
        when (_activeAxis.value) {
            SupportAxis.RECEPTIVE -> generateReceptive(temperature)
            SupportAxis.EXPRESSIVE -> generateExpressive(temperature)
            SupportAxis.JUDGES -> generateJudges(temperature)
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
            val promptHash = sha256(systemPrompt).take(8)
            val promptVersion = when (axis) {
                SupportAxis.RECEPTIVE -> PROMPT_VERSION_RECEPTIVE
                SupportAxis.EXPRESSIVE -> PROMPT_VERSION_EXPRESSIVE
                // v20 (2026-05-11): JUDGES cache key uses its own version tag so a future
                // edit to the contest-pitch prompt invalidates only JUDGES cached outputs.
                SupportAxis.JUDGES -> PROMPT_VERSION_JUDGES
            }
            val key = sha256("$rawSha|$promptVersion|$promptHash|$temperature")
            val cached = readCache(key)
            if (cached != null) {
                target.value = TabState.Cached(cached.first, cached.second)
                return@launch
            }
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
     * Resolve the system prompt for [axis]. Resolution order:
     *  1. Debug-build override from [BidetSettingsScreen] — written to a per-axis DataStore
     *     key. Wins if non-blank because debug iteration on prompt copy is more useful when
     *     it bypasses the user-facing tab pref.
     *  2. The user's edited prompt template for that axis (via [TabPrefRepository]).
     *  3. The bundled default asset for that axis (the PR #28 fidelity-first prompts).
     */
    private suspend fun resolveSystemPrompt(axis: SupportAxis): String {
        val override = readPromptOverride(axis)
        if (!override.isNullOrBlank()) return Glossary.withGlossary(override)

        val defaultPrompt = loadDefaultPromptAsset(axis)
        val pref = tabPrefRepo.read(axis, defaultPrompt)
        val trimmed = pref.promptTemplate.trim()
        val resolved = if (trimmed.isBlank()) defaultPrompt else pref.promptTemplate
        // v18.8 (2026-05-11): prepend the project-noun glossary so Gemma canonicalizes
        // Moonshine mishears. [Glossary.withGlossary] is idempotent — safe to call once.
        return Glossary.withGlossary(resolved)
    }

    private suspend fun loadDefaultPromptAsset(axis: SupportAxis): String =
        withContext(Dispatchers.IO) {
            context.assets.open(TabPref.defaultPromptAssetPath(axis))
                .bufferedReader()
                .use { it.readText() }
        }

    /**
     * v22 (2026-05-13): public bridge to the bundled default for an axis. Used by the
     * inline prompt editor on the EXPRESSIVE tab so its "Reset to default" button can
     * fall back to the same asset the bottom-sheet editor reads. Side-effect free —
     * just reads the asset off disk.
     */
    suspend fun defaultPromptFor(axis: SupportAxis): String = loadDefaultPromptAsset(axis)

    private suspend fun readPromptOverride(axis: SupportAxis): String? {
        val prefs = context.bidetDataStore.data.first()
        return prefs[promptOverrideKey(axis)]
    }

    private suspend fun writeCache(key: String, text: String, generatedAt: Long) {
        context.bidetDataStore.edit { prefs ->
            prefs[stringPreferencesKey("tab_cache_$key")] = "$generatedAt|$text"
        }
    }

    private suspend fun readCache(key: String): Pair<String, Long>? {
        val prefs = context.bidetDataStore.data.first()
        val raw = prefs[stringPreferencesKey("tab_cache_$key")] ?: return null
        val sep = raw.indexOf('|')
        if (sep <= 0) return null
        val ts = raw.substring(0, sep).toLongOrNull() ?: return null
        val txt = raw.substring(sep + 1)
        return txt to ts
    }

    fun resetTabs() {
        _receptiveState.value = TabState.Idle
        _expressiveState.value = TabState.Idle
        // v20 (2026-05-11): reset the Clean-for-judges tab when a new recording starts so
        // a previous session's contest writeup doesn't leak onto the new dump.
        _judgesState.value = TabState.Idle
    }

    private fun bindCleanGenerationService() {
        if (serviceConnection != null) return
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = (binder as? CleanGenerationService.LocalBinder)?.service() ?: return
                boundService = svc
                receptiveObserverJob?.cancel()
                receptiveObserverJob = viewModelScope.launch {
                    svc.stateFlow.collect { handleServiceState(it, SupportAxis.RECEPTIVE) }
                }
                expressiveObserverJob?.cancel()
                expressiveObserverJob = viewModelScope.launch {
                    svc.stateFlow.collect { handleServiceState(it, SupportAxis.EXPRESSIVE) }
                }
                // v20 (2026-05-11): third observer for the Clean-for-judges tab. Same
                // filter-on-axis pattern — events for RECEPTIVE/EXPRESSIVE land in their
                // respective collectors via [handleServiceState]'s axis check.
                judgesObserverJob?.cancel()
                judgesObserverJob = viewModelScope.launch {
                    svc.stateFlow.collect { handleServiceState(it, SupportAxis.JUDGES) }
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
                    // v25 (2026-05-14): forward the sticky "Cleaning part N of M…"
                    // banner from the service layer up to the UI layer so it can be
                    // pinned above the scrolling partial text.
                    chunkLabel = state.chunkLabel,
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
        // v20 (2026-05-11): cancel the third observer job alongside the original two.
        judgesObserverJob?.cancel()
        serviceConnection?.let {
            try { context.unbindService(it) } catch (_: Throwable) { /* never bound or already unbound */ }
        }
        serviceConnection = null
        boundService = null
    }

    private fun stateFlowFor(axis: SupportAxis): MutableStateFlow<TabState> = when (axis) {
        SupportAxis.RECEPTIVE -> _receptiveState
        SupportAxis.EXPRESSIVE -> _expressiveState
        SupportAxis.JUDGES -> _judgesState
    }

    /** Snapshot of the current RAW transcript (used by the always-visible RAW reading base). */
    fun currentRaw(): String = aggregator.currentText()

    companion object {
        // Bumping these strings invalidates cached generations. The promptHash component of
        // the cache key absorbs day-to-day prompt edits.
        const val PROMPT_VERSION_RECEPTIVE: String = "v2"
        const val PROMPT_VERSION_EXPRESSIVE: String = "v2"
        // v20 (2026-05-11): version tag for the Clean-for-judges contest-pitch tab. Bumping
        // this invalidates only JUDGES cached outputs — the other two axes keep their
        // cached generations through a contest-prompt iteration.
        const val PROMPT_VERSION_JUDGES: String = "v1"

        const val DEFAULT_TEMPERATURE: Float = 0.3f
        const val CLEAN_TAB_OUTPUT_TOKEN_CAP: Int = 2048

        /**
         * Per-window output cap used when [com.google.ai.edge.gallery.bidet.cleaning.RawChunker]
         * splits a long RAW into multiple chunks. The cleaning prompt already targets ≤30%
         * length reduction so each ~700-token-input window cleans to ~500 output tokens; we
         * cap at 512 to bound worst-case wall-clock per window without truncating reasonable
         * cleanings. With 6 windows on a 12k-char dump this trims total wall-clock from
         * ~30 min to ~6-10 min on Tensor G3 CPU.
         */
        const val CLEAN_TAB_CHUNKED_OUTPUT_TOKEN_CAP: Int = 512

        private val PLACEHOLDER_AGGREGATOR = TranscriptAggregator()

        fun promptOverrideKey(axis: SupportAxis): Preferences.Key<String> {
            val tag = when (axis) {
                SupportAxis.RECEPTIVE -> "receptive"
                SupportAxis.EXPRESSIVE -> "expressive"
                // v20 (2026-05-11): JUDGES debug-override key for the Clean-for-judges tab.
                SupportAxis.JUDGES -> "judges"
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
