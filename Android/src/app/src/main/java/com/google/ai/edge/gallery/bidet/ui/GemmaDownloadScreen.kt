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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.bidet.download.BidetModelProvider
import com.google.ai.edge.gallery.bidet.download.DownloadProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * First-run model download screen. Brief §8.
 *
 * Lifecycle:
 *  - Renders [DownloadProgress.Idle] with a Start button (kicks off the work).
 *  - Renders [DownloadProgress.InProgress] with percent + bytes + speed + Cancel.
 *  - Renders [DownloadProgress.Success] briefly, then invokes [onComplete] (the caller
 *    advances to [BidetMainScreen]).
 *  - Renders [DownloadProgress.Failed] with error text + Retry. Retry replays the work.
 *
 * The download is delegated to upstream Gallery's [com.google.ai.edge.gallery.worker.DownloadWorker]
 * via [BidetModelProvider]. The worker handles HTTP-Range resume on flaky networks; if the
 * user backgrounds the app mid-download the foreground notification keeps the work alive.
 */
@Composable
fun GemmaDownloadScreen(
    onComplete: () -> Unit,
    viewModel: GemmaDownloadViewModel = hiltViewModel(),
) {
    val state by viewModel.progress.collectAsStateWithLifecycle()
    val totalBytes by viewModel.totalBytes.collectAsStateWithLifecycle()

    // Auto-kick the download on first composition if we're sitting at Idle and the model is not
    // already present. (The caller [BidetMainScreen] only routes here when isModelReady() is
    // false, but a state restore could land us here mid-Success — defend against that.)
    var kicked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Phase 4A: resolve the dynamic Content-Length while the user is still on the
        // intro screen so it's ready before the download bar starts updating.
        viewModel.resolveTotalBytes()
        if (!kicked && viewModel.modelReady()) {
            onComplete()
        } else if (!kicked) {
            kicked = true
            viewModel.start()
        }
    }
    LaunchedEffect(state) {
        if (state is DownloadProgress.Success) onComplete()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "Downloading Gemma 4 model",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            // Phase 4A: prefer the live HEAD-resolved size when available, else show the
            // approximate from the fallback constant.
            text = "This is a one-time download (${
                totalBytes?.let { formatBytes(it) } ?: "~3.7 GB"
            }). Subsequent launches will be instant.",
        )

        Spacer(Modifier.height(8.dp))

        when (val s = state) {
            is DownloadProgress.Idle -> {
                Text("Preparing…")
                LinearProgressIndicator(
                    progress = { 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.start() }) { Text("Start download") }
            }

            is DownloadProgress.InProgress -> {
                LinearProgressIndicator(
                    progress = { s.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${s.percent}%")
                Text(
                    "${formatBytes(s.bytesDownloaded)} / ${formatBytes(s.totalBytes)}" +
                        if (s.bytesPerSec > 0) "  •  ${formatBytes(s.bytesPerSec)}/s" else ""
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { viewModel.cancel() }) { Text("Cancel") }
                }
            }

            is DownloadProgress.Success -> {
                Text("Download complete. Loading model…")
                LinearProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is DownloadProgress.Failed -> {
                Text(
                    text = "Download failed",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(s.error)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { viewModel.start() }) { Text("Retry") }
                }
            }
        }
    }
}

/** Format raw bytes as a human-readable size (KB / MB / GB). */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = bytes.toDouble()
    var idx = 0
    while (size >= 1024.0 && idx < units.lastIndex) {
        size /= 1024.0
        idx++
    }
    return if (idx <= 1) "%.0f %s".format(size, units[idx])
    else "%.2f %s".format(size, units[idx])
}

/** Hilt view-model that wraps [BidetModelProvider]'s flow + lifecycle. */
@HiltViewModel
class GemmaDownloadViewModel @Inject constructor(
    private val provider: BidetModelProvider,
) : ViewModel() {

    val progress: StateFlow<DownloadProgress> = provider.progress

    /**
     * Phase 4A: dynamic Content-Length. Initialized to null; resolved by [resolveTotalBytes]
     * via HEAD-fetch or DataStore cache. The screen displays this once known.
     */
    private val _totalBytes = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    val totalBytes: StateFlow<Long?> = _totalBytes

    fun modelReady(): Boolean = provider.isModelReady()

    /** Run on first composition so the screen has the number to render. */
    fun resolveTotalBytes() {
        viewModelScope.launch {
            _totalBytes.value = provider.fetchExpectedTotalBytes()
        }
    }

    fun start() {
        viewModelScope.launch {
            // Drive the cold flow so the underlying StateFlow gets populated. We collect to
            // keep the work observed for the lifetime of this view-model; on first
            // SUCCESS / FAILED the flow auto-closes.
            provider.startDownload().collectLatest { /* state already mirrored on provider.progress */ }
        }
    }

    fun cancel() {
        provider.cancelDownload()
    }
}
