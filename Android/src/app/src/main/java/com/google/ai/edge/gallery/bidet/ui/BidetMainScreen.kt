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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.bidet.service.RecordingService
import com.google.ai.edge.gallery.bidet.transcript.TranscriptAggregator

/**
 * Top-level Bidet screen. Owns:
 *  - the RECORD_AUDIO runtime permission request,
 *  - the bound-service connection to [RecordingService],
 *  - delegating UI to [BidetTabsScreen] once the service Pipeline is live.
 *
 * Phase 2 wiring: this composable replaces the Gallery [GalleryNavHost] as the app's start
 * destination (see [com.google.ai.edge.gallery.GalleryApp]). The legacy Gallery nav graph (model
 * picker, custom-task screens, benchmark) is bypassed entirely — the brief locks bidet-phone to
 * a single brain-dump UX.
 */
@Composable
fun BidetMainScreen() {
    val context = LocalContext.current
    val viewModel: BidetTabsViewModel = hiltViewModel()

    // Bound-service connection. We rebind on every composition under the same Activity
    // (DisposableEffect) so the UI gets a fresh Pipeline reference whenever the service spawns
    // a new recording session.
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

    // RECORD_AUDIO permission request. Per brief §1, the permission grant must precede
    // startForegroundService — Android 15 denies a background-launched FGS the mic otherwise.
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
                // The aggregator becomes available once the service publishes a Pipeline.
                serviceRef?.pipeline()?.aggregator?.let { viewModel.attachAggregator(it) }
            } else {
                recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val agg = aggregator
    if (agg != null && viewModel.hasAggregator) {
        BidetTabsScreen(
            viewModel = viewModel,
            isRecording = isRecording,
            onToggleRecording = onToggleRecording,
        )
    } else {
        // Pre-recording placeholder. Service might be alive but no Pipeline yet (no session
        // started). Show a centered Record CTA.
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Text("Bidet AI")
                Text("Tap Record to begin a brain-dump.")
                Button(onClick = onToggleRecording) { Text("Record") }
            }
        }
    }
}
