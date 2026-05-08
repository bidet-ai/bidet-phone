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
import android.content.Intent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sessions list screen. Phase 4A — Bidet history.
 *
 * Layout:
 *  - TopAppBar with a back arrow (caller wires [onBack] to navigate back to the live
 *    recorder).
 *  - LazyColumn of saved [BidetSession] rows, newest first.
 *  - Tap row → [onOpenSession] (caller navigates to [SessionDetailScreen]).
 *  - Long-press row → DropdownMenu with Delete + Export WAV.
 *  - Empty state — primary button routes back to the live recorder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsListScreen(
    onBack: () -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
    viewModel: SessionsListViewModel = hiltViewModel(),
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bidet — Sessions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        }
    ) { contentPadding ->
        if (sessions.isEmpty()) {
            EmptyState(modifier = Modifier.padding(contentPadding).fillMaxSize(), onRecord = onBack)
        } else {
            LazyColumn(modifier = Modifier.padding(contentPadding).fillMaxSize()) {
                items(items = sessions, key = { it.sessionId }) { session ->
                    SessionRow(
                        session = session,
                        onOpen = { onOpenSession(session.sessionId) },
                        onDelete = { viewModel.deleteSession(session.sessionId) },
                        onExportWav = { exportWav(context, session) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onRecord: () -> Unit) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
            )
            Text(
                text = "No recordings yet.",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Open the recorder to start a brain-dump.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRecord) { Text("Open recorder") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: BidetSession,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onExportWav: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = { menuOpen = true },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = formatStartedAt(session.startedAtMs),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = formatDuration(session.durationSeconds),
            style = MaterialTheme.typography.bodySmall,
        )
        if (session.rawText.isNotBlank()) {
            Text(
                text = previewText(session.rawText),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (session.endedAtMs == null) {
            Text(
                text = "Recording…",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                text = "(no transcript captured)",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Export WAV") },
                onClick = {
                    menuOpen = false
                    onExportWav()
                },
                enabled = session.audioWavPath != null,
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

internal fun previewText(raw: String): String {
    val trimmed = raw.trim().replace(Regex("\\s+"), " ")
    return if (trimmed.length <= PREVIEW_CHARS) trimmed else trimmed.substring(0, PREVIEW_CHARS) + "…"
}

private val SESSION_START_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault())

internal fun formatStartedAt(startedAtMs: Long): String {
    return Instant.ofEpochMilli(startedAtMs)
        .atZone(ZoneId.systemDefault())
        .format(SESSION_START_FORMATTER)
}

internal fun formatDuration(durationSeconds: Int): String {
    if (durationSeconds <= 0) return "—"
    val mins = durationSeconds / 60
    val secs = durationSeconds % 60
    return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
}

internal fun exportWav(context: Context, session: BidetSession) {
    val path = session.audioWavPath ?: return
    val file = File(path)
    if (!file.exists()) return
    try {
        val authority = "${context.packageName}.provider"
        val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share recording")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (t: Throwable) {
        // Defensive: missing FileProvider config / authority mismatch should fail gracefully
        // rather than crash.
        android.util.Log.w("BidetSessionsList", "exportWav failed: ${t.message}", t)
    }
}

internal const val PREVIEW_CHARS = 80

/**
 * Hilt-bound view-model for the sessions list. Bridges the DAO's flow to a Compose-friendly
 * [StateFlow] and offers the delete action.
 */
@HiltViewModel
class SessionsListViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionDao: BidetSessionDao,
) : ViewModel() {

    val sessions: StateFlow<List<BidetSession>> =
        sessionDao.getAllOrderedByStartedDesc()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = emptyList(),
            )

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val session = sessionDao.getById(sessionId)
                    sessionDao.delete(sessionId)
                    // Best-effort filesystem cleanup. We tolerate failures (the DB row is the
                    // source of truth for the list; orphaned files only waste disk).
                    val baseDir = context.getExternalFilesDir(null) ?: return@runCatching
                    val dir = File(baseDir, "sessions/$sessionId")
                    if (dir.exists()) dir.deleteRecursively()
                    // Phase 4A.1: be defensive about a stale audioWavPath that points
                    // OUTSIDE the per-session dir (deleteRecursively above already covered
                    // the inside-dir case). Previous condition `f.absolutePath !in
                    // dir.absolutePath` was substring containment — backwards in two ways:
                    // (a) `String.contains(other)` semantics on `in` for strings → `f` was
                    // checked against being a substring of dir, the wrong direction; (b)
                    // even read the right way around, raw `contains` would have false-
                    // positived for sibling sessions whose paths share the parent prefix.
                    session?.audioWavPath?.let { path ->
                        val f = File(path)
                        val inDir = f.absolutePath.startsWith(dir.absolutePath + File.separator)
                        if (f.exists() && !inDir) f.delete()
                    }
                }
            }
        }
    }
}
