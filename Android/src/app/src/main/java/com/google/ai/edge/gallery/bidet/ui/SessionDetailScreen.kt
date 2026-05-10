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
import com.google.ai.edge.gallery.bidet.cleaning.RawChunker
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.bidet.data.BidetSession
import com.google.ai.edge.gallery.bidet.data.BidetSessionDao
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SessionDetailScreen — Phase 4A + two-tab restructure (2026-05-10).
 *
 * Layout matches [BidetTabsScreen]: RAW at the top as the reading base, chip row of two
 * editable tabs, then the active tab's content body. The persisted [BidetSession] row is
 * the source of truth for RAW + the cached outputs.
 *
 * Persistence mapping retained from Phase 4A:
 *   bidet_sessions.cleanCached  ← Receptive  (Clean for me)  output
 *   bidet_sessions.foraiCached  ← Expressive (Clean for others) output
 *
 * Editing tab labels/prompts here updates the global [TabPref] (the same pair the live
 * recorder uses) — that's intentional. Renaming "Clean for others" to "Email tone" while
 * looking at yesterday's brain dump should make tomorrow's chip row read "Email tone" too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    sessionId: String,
    onBack: () -> Unit,
    viewModel: SessionDetailViewModel = hiltViewModel(key = "session-detail-$sessionId"),
) {
    LaunchedEffect(sessionId) { viewModel.bind(sessionId) }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val tabPrefs by viewModel.tabPrefs.collectAsStateWithLifecycle()
    val activeAxis by viewModel.activeAxis.collectAsStateWithLifecycle()
    val receptiveState by viewModel.receptiveState.collectAsStateWithLifecycle()
    val expressiveState by viewModel.expressiveState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var editingAxis by remember { mutableStateOf<SupportAxis?>(null) }

    val session = state.session

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (session == null) "Brain dump"
                        else "Brain dump · ${formatStartedAt(session.startedAtMs)}",
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (session?.audioWavPath != null) {
                        IconButton(onClick = { exportWav(context, session) }) {
                            Icon(
                                imageVector = Icons.Filled.Share,
                                contentDescription = "Share / Export WAV",
                            )
                        }
                    }
                },
            )
        }
    ) { contentPadding ->
        Column(modifier = Modifier.padding(contentPadding).fillMaxSize()) {
            if (session == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (state.notFound) "Session not found." else "Loading…")
                }
                return@Column
            }

            // Bug-3 fix (2026-05-10): non-blocking transcribing banner above the RAW base.
            // Same condition as the History row so the user gets a consistent signal —
            // "this session is still being transcribed; what you see below may grow."
            // Reuses the pure helper from SessionsListScreen; null is the fully-merged
            // terminal state.
            val rowProgress = sessionRowProgress(session)
            if (rowProgress != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = stringResource(
                                com.google.ai.edge.gallery.R.string.bidet_history_transcribing_format,
                                rowProgress.merged,
                                rowProgress.produced,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(
                            progress = { rowProgress.fraction },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
            }

            // RAW reading base — always visible.
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                RawTabContent(rawText = session.rawText, isRecording = false)
            }

            HorizontalDivider()

            TabChipRow(
                prefs = tabPrefs,
                activeAxis = activeAxis,
                onSelectAxis = { viewModel.selectAxis(it) },
                onEditAxis = { editingAxis = it },
                editingEnabled = true,
            )

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (activeAxis) {
                    SupportAxis.RECEPTIVE -> CleanTabContent(
                        axis = SupportAxis.RECEPTIVE,
                        state = receptiveState,
                        onGenerate = { viewModel.generate(SupportAxis.RECEPTIVE) },
                    )
                    SupportAxis.EXPRESSIVE -> CleanTabContent(
                        axis = SupportAxis.EXPRESSIVE,
                        state = expressiveState,
                        onGenerate = { viewModel.generate(SupportAxis.EXPRESSIVE) },
                    )
                }
            }
        }
    }

    editingAxis?.let { axis ->
        val pref = tabPrefs.firstOrNull { it.axis == axis }
            ?: TabPref(axis, TabPref.defaultLabel(axis), "")
        TabPrefEditorSheet(
            initialLabel = pref.label,
            initialPrompt = pref.promptTemplate,
            onSave = { newLabel, newPrompt ->
                viewModel.saveTabPref(TabPref(axis, newLabel, newPrompt))
                editingAxis = null
            },
            onResetToDefault = {
                viewModel.resetTabPref(axis)
                editingAxis = null
            },
            onDismiss = { editingAxis = null },
        )
    }
}

/** State of the currently-loaded session. */
data class SessionDetailUiState(
    val session: BidetSession? = null,
    val notFound: Boolean = false,
)

/**
 * Hilt view-model for [SessionDetailScreen].
 *
 * Lifecycle: caller invokes [bind] from a [LaunchedEffect] keyed on `sessionId`. After bind,
 * the view-model observes the row via the DAO and seeds the tab states from the persisted
 * `cleanCached` / `foraiCached` columns so re-opens are instant. v0.1 rows that have
 * `analysisCached` populated are also surfaced via Receptive's column.
 */
@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionDao: BidetSessionDao,
    private val gemma: BidetGemmaClient,
) : ViewModel() {

    private val tabPrefRepo: TabPrefRepository = DataStoreTabPrefRepository(context)

    private val sessionIdFlow = MutableStateFlow("")

    fun bind(sessionId: String) {
        if (sessionIdFlow.value == sessionId) return
        sessionIdFlow.value = sessionId
        _receptiveState.value = TabState.Idle
        _expressiveState.value = TabState.Idle
        viewModelScope.launch {
            val row = sessionDao.getById(sessionId) ?: return@launch
            val receptive = row.cleanCached ?: row.analysisCached
            receptive?.let {
                _receptiveState.value = TabState.Cached(it, row.endedAtMs ?: row.startedAtMs)
            }
            row.foraiCached?.let {
                _expressiveState.value = TabState.Cached(it, row.endedAtMs ?: row.startedAtMs)
            }
        }
        viewModelScope.launch { refreshTabPrefs() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<SessionDetailUiState> = sessionIdFlow
        .flatMapLatest { id ->
            if (id.isEmpty()) {
                flowOf(SessionDetailUiState())
            } else {
                sessionDao.observeById(id).map { row ->
                    SessionDetailUiState(
                        session = row,
                        notFound = row == null,
                    )
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = SessionDetailUiState(),
        )

    private val _receptiveState = MutableStateFlow<TabState>(TabState.Idle)
    val receptiveState: StateFlow<TabState> = _receptiveState.asStateFlow()

    private val _expressiveState = MutableStateFlow<TabState>(TabState.Idle)
    val expressiveState: StateFlow<TabState> = _expressiveState.asStateFlow()

    private val _tabPrefs = MutableStateFlow<List<TabPref>>(
        SupportAxis.ALL.map { axis -> TabPref(axis, TabPref.defaultLabel(axis), "") }
    )
    val tabPrefs: StateFlow<List<TabPref>> = _tabPrefs.asStateFlow()

    private val _activeAxis = MutableStateFlow(SupportAxis.RECEPTIVE)
    val activeAxis: StateFlow<SupportAxis> = _activeAxis.asStateFlow()

    suspend fun refreshTabPrefs() {
        val refreshed = SupportAxis.ALL.map { axis ->
            val defaultPrompt = withContext(Dispatchers.IO) {
                context.assets.open(TabPref.defaultPromptAssetPath(axis)).bufferedReader().use { it.readText() }
            }
            tabPrefRepo.read(axis, defaultPrompt)
        }
        _tabPrefs.value = refreshed
    }

    fun saveTabPref(pref: TabPref) {
        viewModelScope.launch {
            tabPrefRepo.write(pref)
            refreshTabPrefs()
            // Edit changed the prompt for this axis → cached output is stale; reset to Idle so
            // the user sees Generate rather than yesterday's text under today's prompt.
            stateFlowFor(pref.axis).value = TabState.Idle
        }
    }

    fun resetTabPref(axis: SupportAxis) {
        viewModelScope.launch {
            tabPrefRepo.resetToDefault(axis)
            refreshTabPrefs()
            stateFlowFor(axis).value = TabState.Idle
        }
    }

    fun selectAxis(axis: SupportAxis) {
        _activeAxis.value = axis
    }

    /**
     * Run a Gemma generation against the persisted RAW for [axis]. Resolves the system prompt
     * via the same TabPrefRepository the live recorder uses (debug override → user-edited
     * prompt → bundled default), so editing a tab in history view affects subsequent
     * recordings consistently.
     */
    fun generate(axis: SupportAxis) {
        val target = stateFlowFor(axis)

        viewModelScope.launch {
            val sessionId = sessionIdFlow.value
            if (sessionId.isEmpty()) return@launch
            val row = sessionDao.getById(sessionId)
            val raw = row?.rawText.orEmpty()
            if (raw.isBlank()) {
                target.value = TabState.Failed("RAW transcript is empty.")
                return@launch
            }
            target.value = TabState.Streaming(partialText = "", tokenCount = 0, tokenCap = BidetTabsViewModel.CLEAN_TAB_OUTPUT_TOKEN_CAP)
            try {
                val systemPrompt = resolveSystemPrompt(axis)
                val result = withContext(Dispatchers.Default) {
                    val windows = RawChunker.chunk(raw)
                    if (windows.size <= 1) {
                        // Short dump — single streaming call, behaviour unchanged.
                        gemma.runInferenceStreaming(
                            systemPrompt = systemPrompt,
                            userPrompt = raw,
                            maxOutputTokens = BidetTabsViewModel.CLEAN_TAB_OUTPUT_TOKEN_CAP,
                            temperature = BidetTabsViewModel.DEFAULT_TEMPERATURE,
                            onChunk = { cumulative, chunkIndex ->
                                target.value = TabState.Streaming(
                                    partialText = cumulative,
                                    tokenCount = chunkIndex,
                                    tokenCap = BidetTabsViewModel.CLEAN_TAB_OUTPUT_TOKEN_CAP,
                                )
                            },
                        )
                    } else {
                        // Long dump — chunked streaming. Per-window output cap drops to
                        // CLEAN_TAB_CHUNKED_OUTPUT_TOKEN_CAP so wall-clock stays bearable;
                        // partial text shows "Cleaning part N of M…" header above
                        // already-completed parts and the currently-streaming part so the
                        // user sees text growing instead of an indefinite spinner.
                        val parts = mutableListOf<String>()
                        val totalCap = windows.size * BidetTabsViewModel.CLEAN_TAB_CHUNKED_OUTPUT_TOKEN_CAP
                        var streamCounter = 0
                        windows.forEachIndexed { index, window ->
                            val partSystemPrompt = buildString {
                                append(systemPrompt)
                                append("\n\n(You are cleaning part ")
                                append(index + 1)
                                append(" of ")
                                append(windows.size)
                                append(" of a long brain dump. Stay faithful to THIS segment only — ")
                                append("do not re-introduce content from earlier parts and do not preface ")
                                append("your output with meta-commentary about parts.)")
                            }
                            val priorBlock = if (parts.isEmpty()) "" else parts.joinToString("\n\n") + "\n\n"
                            val header = "Cleaning part ${index + 1} of ${windows.size}…\n\n"
                            val partText = gemma.runInferenceStreaming(
                                systemPrompt = partSystemPrompt,
                                userPrompt = window,
                                maxOutputTokens = BidetTabsViewModel.CLEAN_TAB_CHUNKED_OUTPUT_TOKEN_CAP,
                                temperature = BidetTabsViewModel.DEFAULT_TEMPERATURE,
                                onChunk = { cumulative, _ ->
                                    streamCounter += 1
                                    target.value = TabState.Streaming(
                                        partialText = header + priorBlock + cumulative,
                                        tokenCount = streamCounter,
                                        tokenCap = totalCap,
                                    )
                                },
                            )
                            parts.add(partText.trim())
                        }
                        parts.joinToString("\n\n")
                    }
                }
                val now = System.currentTimeMillis()
                target.value = TabState.Cached(result, now)
                // Phase 4A.1: column-targeted UPDATE. The two slot indices map onto the
                // existing two columns to avoid a Room migration.
                when (axis) {
                    SupportAxis.RECEPTIVE -> sessionDao.updateCleanCached(sessionId, result)
                    SupportAxis.EXPRESSIVE -> sessionDao.updateForaiCached(sessionId, result)
                }
            } catch (t: Throwable) {
                target.value = TabState.Failed(t.message ?: "Generation failed")
            }
        }
    }

    /**
     * Resolve the system prompt for [axis]. Mirrors [BidetTabsViewModel.resolveSystemPrompt]:
     * debug override > user-edited prompt > bundled default.
     */
    private suspend fun resolveSystemPrompt(axis: SupportAxis): String {
        val prefs = context.bidetDataStore.data.first()
        val override = prefs[BidetTabsViewModel.promptOverrideKey(axis)]
        if (!override.isNullOrBlank()) return override
        val defaultPrompt = withContext(Dispatchers.IO) {
            context.assets.open(TabPref.defaultPromptAssetPath(axis)).bufferedReader().use { it.readText() }
        }
        val pref = tabPrefRepo.read(axis, defaultPrompt)
        val trimmed = pref.promptTemplate.trim()
        return if (trimmed.isBlank()) defaultPrompt else pref.promptTemplate
    }

    private fun stateFlowFor(axis: SupportAxis): MutableStateFlow<TabState> = when (axis) {
        SupportAxis.RECEPTIVE -> _receptiveState
        SupportAxis.EXPRESSIVE -> _expressiveState
    }
}
