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
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.bidet.cleaning.ChunkCleaner
import com.google.ai.edge.gallery.bidet.service.CleanGenerationService
import java.io.File
import kotlinx.coroutines.Job
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
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
                        IconButton(onClick = { copyWavToDownloads(context, session) }) {
                            Icon(
                                imageVector = Icons.Filled.FileDownload,
                                contentDescription = "Save WAV to Downloads",
                            )
                        }
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
) : ViewModel() {

    private val tabPrefRepo: TabPrefRepository = DataStoreTabPrefRepository(context)

    private val sessionIdFlow = MutableStateFlow("")

    /**
     * v18.6 (2026-05-10): bound CleanGenerationService — same instance the live-recording
     * tab uses. Routes History-screen Clean through a foreground service so Android can't
     * kill the process mid-decode when the screen blanks. Bound in [init], unbound in
     * [onCleared]. State arrives via the service's StateFlow and is filtered by sessionId
     * + axis so multiple bound clients don't cross-talk.
     */
    @Volatile private var boundService: CleanGenerationService? = null
    private var serviceConnection: ServiceConnection? = null
    private var receptiveObserverJob: Job? = null
    private var expressiveObserverJob: Job? = null

    init {
        bindCleanGenerationService()
    }

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
            try {
                // Path B short-circuit: if RecordingService pre-cleaned every chunk during
                // the live recording, stitch from disk and finish in <100ms. Only RECEPTIVE
                // is pre-cleaned in v1. Files live under sessions/<sid>/cleanings/.
                if (axis == SupportAxis.RECEPTIVE) {
                    val expectedChunks = row?.chunkCount ?: 0
                    val externalRoot = context.getExternalFilesDir(null)
                    if (externalRoot != null && expectedChunks > 0) {
                        val sessionDir = File(externalRoot, "sessions/$sessionId")
                        val stitched = ChunkCleaner.loadStitched(sessionDir, expectedChunks)
                        if (stitched != null) {
                            val now = System.currentTimeMillis()
                            target.value = TabState.Cached(stitched, now)
                            sessionDao.updateCleanCached(sessionId, stitched)
                            return@launch
                        }
                    }
                }
                // v18.6 (2026-05-10): hand off to CleanGenerationService instead of running
                // gemma in viewModelScope. The previous in-VM path was killed by Android
                // mid-decode whenever the screen blanked or the user backgrounded the app
                // (2026-05-10 10-min session: "Process ai.bidet.phone.moonshine has died:
                // cch CRE"). The foreground service holds the process at fgs priority until
                // generation completes. State arrives via [handleServiceState] which is
                // wired in [bindCleanGenerationService] below. Caching to the row is also
                // handled there so the Cached state and the DB write stay in sync.
                val systemPrompt = resolveSystemPrompt(axis)
                target.value = TabState.Streaming(
                    partialText = "",
                    tokenCount = 0,
                    tokenCap = BidetTabsViewModel.CLEAN_TAB_OUTPUT_TOKEN_CAP,
                )
                ContextCompat.startForegroundService(
                    context,
                    CleanGenerationService.startIntent(
                        context = context,
                        sessionId = sessionId,
                        axis = axis,
                        systemPrompt = systemPrompt,
                        userPrompt = raw,
                        tokenCap = BidetTabsViewModel.CLEAN_TAB_OUTPUT_TOKEN_CAP,
                        temperature = BidetTabsViewModel.DEFAULT_TEMPERATURE,
                    ),
                )
            } catch (t: Throwable) {
                target.value = TabState.Failed(t.message ?: "Generation failed")
            }
        }
    }

    /**
     * v18.6 (2026-05-10): bind to the same [CleanGenerationService] singleton the live
     * recording tab uses. The service emits a [CleanGenerationService.GenerationState]
     * stream that we filter by `sessionId` (so a generation started for a different
     * session doesn't clobber this view-model's state) and `axis`. Two observer jobs run
     * so RECEPTIVE and EXPRESSIVE can be in flight independently if the user kicks off
     * both before the first completes.
     */
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
        val mySessionId = sessionIdFlow.value
        if (mySessionId.isEmpty()) return
        val target = stateFlowFor(observerAxis)
        when (state) {
            is CleanGenerationService.GenerationState.Idle -> { /* keep current */ }
            is CleanGenerationService.GenerationState.Streaming -> {
                if (state.sessionId != mySessionId || state.axis != observerAxis) return
                target.value = TabState.Streaming(
                    partialText = state.partialText,
                    tokenCount = state.tokenCount,
                    tokenCap = state.tokenCap,
                )
            }
            is CleanGenerationService.GenerationState.Done -> {
                if (state.sessionId != mySessionId || state.axis != observerAxis) return
                target.value = TabState.Cached(state.text, state.finishedAtMs)
                viewModelScope.launch {
                    try {
                        when (observerAxis) {
                            SupportAxis.RECEPTIVE -> sessionDao.updateCleanCached(mySessionId, state.text)
                            SupportAxis.EXPRESSIVE -> sessionDao.updateForaiCached(mySessionId, state.text)
                        }
                    } catch (_: Throwable) { /* row write failure is non-fatal — text is already visible */ }
                }
            }
            is CleanGenerationService.GenerationState.Failed -> {
                if (state.sessionId != mySessionId || state.axis != observerAxis) return
                target.value = TabState.Failed(state.message)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        receptiveObserverJob?.cancel()
        expressiveObserverJob?.cancel()
        serviceConnection?.let { context.unbindService(it) }
        serviceConnection = null
        boundService = null
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
