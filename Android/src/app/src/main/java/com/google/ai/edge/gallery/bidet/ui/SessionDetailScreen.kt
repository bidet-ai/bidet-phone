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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.R
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SessionDetailScreen — Phase 4A + v0.2 three-tab restructure.
 *
 * Renders the v0.2 three-tab UX (RAW / Clean for me / Clean for others) but the source of
 * truth is the persisted [BidetSession] row, not a live aggregator. CLEAN-FOR-ME generation
 * runs against the loaded `rawText`; outputs are persisted back to the row's `cleanCached`
 * (Receptive) / `foraiCached` (Expressive) columns. The legacy `analysisCached` column is
 * retained for v0.1 history rows but not written to in v0.2.
 *
 * Persistence mapping (Phase 4A schema, retained for v0.2 to avoid a migration):
 *   bidet_sessions.cleanCached      ← Receptive  (Clean for me)  output
 *   bidet_sessions.foraiCached      ← Expressive (Clean for others) output
 *   bidet_sessions.analysisCached   ← unused in v0.2; populated only on pre-v0.2 rows
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
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = TAB_INDEX_RAW, pageCount = { TAB_TITLES.size })
    val coroutineScope = rememberCoroutineScope()

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

            TabRow(selectedTabIndex = pagerState.currentPage) {
                TAB_TITLES.forEachIndexed { index, titleResId ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            coroutineScope.launch { pagerState.animateScrollToPage(index) }
                        },
                        text = { Text(stringResource(titleResId)) },
                    )
                }
            }

            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                Box(modifier = Modifier.fillMaxSize()) {
                    when (page) {
                        TAB_INDEX_RAW -> RawTabContent(
                            rawText = session.rawText,
                            isRecording = false,
                        )
                        TAB_INDEX_CLEAN_FOR_ME -> {
                            val tabState by viewModel.receptiveState.collectAsStateWithLifecycle()
                            // History view: chip selection + custom prompt are inert because
                            // the cached output was produced under whichever preset was
                            // active at recording time. Generating again belongs to the live
                            // recording flow, not the history viewer.
                            CleanTabContent(
                                axis = SupportAxis.RECEPTIVE,
                                state = tabState,
                                activePresetId = SupportPreset.RECEPTIVE_SUMMARY.id,
                                customPrompt = "",
                                rawTextProvider = { session.rawText },
                                onSelectPreset = { /* no-op in history view */ },
                                onSaveCustomPrompt = { /* no-op in history view */ },
                                onGenerate = { viewModel.generate(SupportAxis.RECEPTIVE) },
                            )
                        }
                        TAB_INDEX_CLEAN_FOR_OTHERS -> {
                            val tabState by viewModel.expressiveState.collectAsStateWithLifecycle()
                            CleanTabContent(
                                axis = SupportAxis.EXPRESSIVE,
                                state = tabState,
                                activePresetId = SupportPreset.EXPRESSIVE_AI_INGEST.id,
                                customPrompt = "",
                                rawTextProvider = { session.rawText },
                                onSelectPreset = { /* no-op in history view */ },
                                onSaveCustomPrompt = { /* no-op in history view */ },
                                onGenerate = { viewModel.generate(SupportAxis.EXPRESSIVE) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private val TAB_TITLES: List<Int> = listOf(
    R.string.bidet_tab_raw,
    R.string.bidet_tab_clean_for_me,
    R.string.bidet_tab_clean_for_others,
)

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
 * `analysisCached` populated are also surfaced — we prefer Receptive's cached output, but
 * fall back to the analysis column if Receptive is null and analysis is non-null.
 */
@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionDao: BidetSessionDao,
    private val gemma: BidetGemmaClient,
) : ViewModel() {

    private val sessionIdFlow = MutableStateFlow("")

    /** Wire the active session id. Idempotent — re-binding the same id is a no-op. */
    fun bind(sessionId: String) {
        if (sessionIdFlow.value == sessionId) return
        sessionIdFlow.value = sessionId
        // Re-seed tab states from the persisted row.
        _receptiveState.value = TabState.Idle
        _expressiveState.value = TabState.Idle
        viewModelScope.launch {
            val row = sessionDao.getById(sessionId) ?: return@launch
            // Receptive prefers cleanCached (the v0.1 CLEAN tab's column); falls back to
            // analysisCached for old rows that were generated under the v0.1 ANALYSIS tab.
            val receptive = row.cleanCached ?: row.analysisCached
            receptive?.let {
                _receptiveState.value = TabState.Cached(it, row.endedAtMs ?: row.startedAtMs)
            }
            row.foraiCached?.let {
                _expressiveState.value = TabState.Cached(it, row.endedAtMs ?: row.startedAtMs)
            }
        }
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

    /**
     * Run a Gemma generation against the persisted RAW for [axis]. The history view always
     * uses the axis's default preset (Summary for Receptive, AI-ingestion markdown for
     * Expressive); preset switching belongs to the live recording flow. The output is
     * persisted to the column that backs that axis (cleanCached / foraiCached).
     */
    fun generate(axis: SupportAxis) {
        val target = when (axis) {
            SupportAxis.RECEPTIVE -> _receptiveState
            SupportAxis.EXPRESSIVE -> _expressiveState
        }
        val assetPath = when (axis) {
            SupportAxis.RECEPTIVE -> "prompts/receptive_default.txt"
            SupportAxis.EXPRESSIVE -> "prompts/expressive_default.txt"
        }

        viewModelScope.launch {
            val sessionId = sessionIdFlow.value
            if (sessionId.isEmpty()) return@launch
            val row = sessionDao.getById(sessionId)
            val raw = row?.rawText.orEmpty()
            if (raw.isBlank()) {
                target.value = TabState.Failed("RAW transcript is empty.")
                return@launch
            }
            target.value = TabState.Generating
            try {
                val systemPrompt = withContext(Dispatchers.IO) {
                    context.assets.open(assetPath).bufferedReader().use { it.readText() }
                }
                val result = withContext(Dispatchers.Default) {
                    gemma.runInference(
                        systemPrompt = systemPrompt,
                        userPrompt = raw,
                        maxOutputTokens = BidetTabsViewModel.MAX_OUTPUT_TOKENS,
                        temperature = BidetTabsViewModel.DEFAULT_TEMPERATURE,
                    )
                }
                val now = System.currentTimeMillis()
                target.value = TabState.Cached(result, now)
                // Phase 4A.1: column-targeted UPDATE. v0.2 maps the two live tabs onto the
                // existing two columns to avoid a Room migration:
                //   Receptive  (Clean for me)     → cleanCached
                //   Expressive (Clean for others) → foraiCached
                when (axis) {
                    SupportAxis.RECEPTIVE -> sessionDao.updateCleanCached(sessionId, result)
                    SupportAxis.EXPRESSIVE -> sessionDao.updateForaiCached(sessionId, result)
                }
            } catch (t: Throwable) {
                target.value = TabState.Failed(t.message ?: "Generation failed")
            }
        }
    }
}
