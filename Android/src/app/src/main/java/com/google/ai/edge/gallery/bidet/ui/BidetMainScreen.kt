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

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.bidet.consent.GemmaTermsConsentScreen
import com.google.ai.edge.gallery.bidet.consent.hasGemmaConsent
import com.google.ai.edge.gallery.bidet.consent.recordGemmaConsent
import com.google.ai.edge.gallery.bidet.data.BidetSessionDao
import com.google.ai.edge.gallery.bidet.download.BidetModelProvider
import com.google.ai.edge.gallery.bidet.service.RecordingService
import com.google.ai.edge.gallery.bidet.transcript.TranscriptAggregator
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Top-level Bidet screen. Owns:
 *  - The first-run navigation gate: GemmaTermsConsentScreen → GemmaDownloadScreen → tabs.
 *    Subsequent launches skip both screens once consent is recorded and the model file is
 *    present (Brief §8 + §9).
 *  - The RECORD_AUDIO runtime permission request.
 *  - The bound-service connection to [RecordingService].
 *  - Delegating UI to [BidetTabsScreen] once the service Pipeline is live.
 *
 * Phase 3 wiring: gates the four-tab UI on consent + model-ready. If [BidetModelProvider]
 * reports the file missing, the tab UI is replaced by [GemmaDownloadScreen]. If the user
 * has not yet consented to the Gemma Terms, [GemmaTermsConsentScreen] is shown first.
 */
@Composable
fun BidetMainScreen(modelProvider: BidetModelProvider) {
    val context = LocalContext.current

    // Three-state nav: NEEDS_CONSENT → NEEDS_DOWNLOAD → READY. Determined on first composition
    // and updated by the consent / download screens' callbacks.
    var phase by remember { mutableStateOf<Phase>(Phase.Loading) }

    LaunchedEffect(Unit) {
        // Phase 4A.1: orphan recovery sweep. Backfills endedAtMs on rows whose recording
        // service died before finalize ran (process kill, mid-record OOM, mic permission
        // revoked while running). Cheap one-shot UPDATE; only touches rows where
        // endedAtMs IS NULL.
        runCatching {
            withContext(Dispatchers.IO) {
                BidetMainScreenHiltEntryPoint.dao(context).recoverOrphans()
            }
        }
        val consented = hasGemmaConsent(context)
        phase = when {
            !consented -> Phase.NeedsConsent
            !modelProvider.isModelReady() -> Phase.NeedsDownload
            else -> Phase.Ready
        }
    }

    // Phase 4A: history navigation lives at this layer so SessionsList / SessionDetail can
    // stack on top of the live recorder Ready screen without rebuilding consent + download
    // gates each time.
    var route by remember { mutableStateOf<BidetRoute>(BidetRoute.Main) }

    when (phase) {
        Phase.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading…")
        }

        Phase.NeedsConsent -> {
            val scope = rememberCoroutineScope()
            GemmaTermsConsentScreen(
                onAccept = {
                    scope.launch {
                        recordGemmaConsent(context)
                        phase = if (modelProvider.isModelReady()) Phase.Ready else Phase.NeedsDownload
                    }
                },
                onDecline = {
                    // Per brief §9: Decline closes the app.
                    (context as? Activity)?.finishAffinity()
                },
            )
        }

        Phase.NeedsDownload -> GemmaDownloadScreen(
            onComplete = { phase = Phase.Ready },
        )

        Phase.Ready -> when (val r = route) {
            BidetRoute.Main -> ReadyScreen(
                onRequestDownload = { phase = Phase.NeedsDownload },
                onOpenHistory = { route = BidetRoute.SessionsList },
            )
            BidetRoute.SessionsList -> SessionsListScreen(
                onBack = { route = BidetRoute.Main },
                onOpenSession = { id -> route = BidetRoute.SessionDetail(id) },
            )
            is BidetRoute.SessionDetail -> SessionDetailScreen(
                sessionId = r.sessionId,
                onBack = { route = BidetRoute.SessionsList },
            )
        }
    }
}

/**
 * Phase 4A in-memory routes. Lightweight alternative to a NavController — the bidet flow
 * has 3 destinations + 2 gates and doesn't justify the navigation-compose overhead for v0.1.
 */
private sealed class BidetRoute {
    object Main : BidetRoute()
    object SessionsList : BidetRoute()
    data class SessionDetail(val sessionId: String) : BidetRoute()
}

private sealed class Phase {
    object Loading : Phase()
    object NeedsConsent : Phase()
    object NeedsDownload : Phase()
    object Ready : Phase()
}

/**
 * Phase 4A.1: app-launch orphan-recovery sweep needs the [BidetSessionDao]. Top-level
 * `BidetMainScreen` doesn't have an injected ViewModel that exposes the DAO, so we reach
 * for it via a Hilt EntryPoint rather than introducing a new VM just for a one-shot.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface BidetMainScreenDaoEntryPoint {
    fun bidetSessionDao(): BidetSessionDao
}

internal object BidetMainScreenHiltEntryPoint {
    fun dao(context: Context): BidetSessionDao =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            BidetMainScreenDaoEntryPoint::class.java,
        ).bidetSessionDao()
}

/**
 * Post-consent post-download UI. Owns the bound-service + tab UI from the original
 * BidetMainScreen body. [onRequestDownload] is invoked if a tab-side runInference call
 * surfaces [BidetModelNotReadyException] — e.g. user manually deleted the model file. The
 * caller flips back to [Phase.NeedsDownload].
 */
@Composable
private fun ReadyScreen(
    onRequestDownload: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: BidetTabsViewModel = hiltViewModel()

    var serviceRef by remember { mutableStateOf<RecordingService?>(null) }
    var aggregator by remember { mutableStateOf<TranscriptAggregator?>(null) }
    var isRecording by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = (binder as? RecordingService.LocalBinder)?.service()
                serviceRef = svc
                aggregator = svc?.pipeline()?.aggregator
                isRecording = svc?.pipeline() != null
                svc?.pipeline()?.aggregator?.let { viewModel.attachAggregator(it) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceRef = null
                aggregator = null
                isRecording = false
            }
        }
        val intent = Intent(context, RecordingService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        onDispose { context.unbindService(connection) }
    }

    val recordAudioLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                ContextCompat.startForegroundService(context, RecordingService.startIntent(context))
                isRecording = true
                serviceRef?.pipeline()?.aggregator?.let { viewModel.attachAggregator(it) }
            }
        }

    val onToggleRecording: () -> Unit = {
        if (isRecording) {
            ContextCompat.startForegroundService(context, RecordingService.stopIntent(context))
            isRecording = false
        } else {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                ContextCompat.startForegroundService(context, RecordingService.startIntent(context))
                isRecording = true
                serviceRef?.pipeline()?.aggregator?.let { viewModel.attachAggregator(it) }
            } else {
                recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // If a tab generation surfaces BidetModelNotReadyException (e.g. the user manually deleted
    // the model file out from under us), kick the user back to the download screen. The
    // BidetTabsViewModel exposes a one-shot signal we can observe.
    LaunchedEffect(Unit) {
        viewModel.modelMissingSignal.collect {
            onRequestDownload()
        }
    }

    val agg = aggregator
    if (agg != null && viewModel.hasAggregator) {
        BidetTabsScreen(
            viewModel = viewModel,
            isRecording = isRecording,
            onToggleRecording = onToggleRecording,
            onOpenHistory = onOpenHistory,
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Text("Bidet AI")
                Text("Tap Record to begin a brain-dump.")
                Button(onClick = onToggleRecording) { Text("Record") }
                Button(onClick = onOpenHistory) { Text("History") }
            }
        }
    }
}
