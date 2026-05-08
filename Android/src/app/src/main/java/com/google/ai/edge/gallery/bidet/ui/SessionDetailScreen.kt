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
 * SessionDetailScreen — Phase 4A.
 *
 * Renders the same four-tab UX as [BidetTabsScreen] but the source of truth is the
 * persisted [BidetSession] row, not a live aggregator. CLEAN/ANALYSIS/FORAI generation runs
 * against the loaded `rawText`; outputs are persisted back to the row's `cleanCached` /
 * `analysisCached` / `foraiCached` columns so re-opening the session is instant.
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
    val pagerState = rememberPagerState(initialPage = TAB_INDEX_RAW, pageCount = { 4 })
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
                        TAB_INDEX_CLEAN -> {
                            val tabState by viewModel.cleanState.collectAsStateWithLifecycle()
                            CleanTabContent(
                                state = tabState,
                                onGenerate = { viewModel.generate(TabKind.Clean) },
                            )
                        }
                        TAB_INDEX_ANALYSIS -> {
                            val tabState by viewModel.analysisState.collectAsStateWithLifecycle()
                            AnalysisTabContent(
                                state = tabState,
                                onGenerate = { viewModel.generate(TabKind.Analysis) },
                            )
                        }
                        TAB_INDEX_FORAI -> {
                            val tabState by viewModel.foraiState.collectAsStateWithLifecycle()
                            ForaiTabContent(
                                state = tabState,
                                onGenerate = { viewModel.generate(TabKind.Forai) },
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
    R.string.bidet_tab_clean,
    R.string.bidet_tab_analysis,
    R.string.bidet_tab_forai,
)

/** State of the currently-loaded session. */
data class SessionDetailUiState(
    val session: BidetSession? = null,
    val notFound: Boolean = false,
)

enum class TabKind { Clean, Analysis, Forai }

/**
 * Hilt view-model for [SessionDetailScreen].
 *
 * Lifecycle: caller invokes [bind] from a [LaunchedEffect] keyed on `sessionId`. After bind,
 * the view-model observes the row via the DAO and seeds the tab states from the persisted
 * `cleanCached` / `analysisCached` / `foraiCached` columns so re-opens are instant.
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
        _cleanState.value = TabState.Idle
        _analysisState.value = TabState.Idle
        _foraiState.value = TabState.Idle
        viewModelScope.launch {
            val row = sessionDao.getById(sessionId) ?: return@launch
            row.cleanCached?.let { _cleanState.value = TabState.Cached(it, row.endedAtMs ?: row.startedAtMs) }
            row.analysisCached?.let { _analysisState.value = TabState.Cached(it, row.endedAtMs ?: row.startedAtMs) }
            row.foraiCached?.let { _foraiState.value = TabState.Cached(it, row.endedAtMs ?: row.startedAtMs) }
        }
    }

    /**
     * Phase 4A.1: was a nested-collect (`sessionIdFlow.collect { id ->
     * sessionDao.observeById(id).collect { ... } }`). Room flows are infinite, so the inner
     * `.collect` never completed and a subsequent `sessionId` emission could not advance the
     * outer collector. Refactored to `flatMapLatest`, which cancels the prior inner flow on
     * each new id and resubscribes — exactly what we want when the user navigates between
     * sessions while the screen is alive.
     */
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

    private val _cleanState = MutableStateFlow<TabState>(TabState.Idle)
    val cleanState: StateFlow<TabState> = _cleanState.asStateFlow()

    private val _analysisState = MutableStateFlow<TabState>(TabState.Idle)
    val analysisState: StateFlow<TabState> = _analysisState.asStateFlow()

    private val _foraiState = MutableStateFlow<TabState>(TabState.Idle)
    val foraiState: StateFlow<TabState> = _foraiState.asStateFlow()

    fun generate(kind: TabKind) {
        val target = when (kind) {
            TabKind.Clean -> _cleanState
            TabKind.Analysis -> _analysisState
            TabKind.Forai -> _foraiState
        }
        val assetPath = when (kind) {
            TabKind.Clean -> "prompts/clean.txt"
            TabKind.Analysis -> "prompts/analysis.txt"
            TabKind.Forai -> "prompts/forai.txt"
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
                // Persist back to the BidetSession row so re-opens are instant.
                val current = sessionDao.getById(sessionId) ?: return@launch
                val updated = when (kind) {
                    TabKind.Clean -> current.copy(cleanCached = result)
                    TabKind.Analysis -> current.copy(analysisCached = result)
                    TabKind.Forai -> current.copy(foraiCached = result)
                }
                sessionDao.update(updated)
            } catch (t: Throwable) {
                target.value = TabState.Failed(t.message ?: "Generation failed")
            }
        }
    }
}
