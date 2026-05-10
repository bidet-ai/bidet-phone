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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.R

/**
 * Two-tab restructure (2026-05-10): RAW at the top of the screen as a reading base, then a
 * chip row of two GENERATED tabs below it ("Clean for me" and "Clean for others" by
 * default; both editable), and the active tab's generated content under that.
 *
 * Vertical layout:
 *   ┌─────────────────────────────────────┐
 *   │  TopAppBar (record / stop / hist.)  │
 *   ├─────────────────────────────────────┤
 *   │  RecordingHeader (timer)            │  ← only while recording
 *   ├─────────────────────────────────────┤
 *   │  RAW transcript (scrollable, 1f)    │  ← ALWAYS visible
 *   ├─────────────────────────────────────┤
 *   │  Tab chip row: [Clean for me ✎]     │
 *   │                [Clean for others ✎] │
 *   ├─────────────────────────────────────┤
 *   │  Active tab's generated content     │  ← Idle / Streaming / Cached
 *   └─────────────────────────────────────┘
 *
 * The RAW area takes the upper half of the screen via a `weight(1f)`, so it grows on tall
 * devices and shrinks gracefully when the keyboard appears for the editor sheet. The
 * generated content uses its own `weight(1f)`, giving roughly 50/50 vertical split. Mark
 * spends most of his attention on RAW; the generated views are reference, not the headline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BidetTabsScreen(
    viewModel: BidetTabsViewModel,
    isRecording: Boolean,
    onToggleRecording: () -> Unit,
    onOpenHistory: () -> Unit = {},
    recordingStartedAtMs: Long = 0L,
) {
    val raw by viewModel.aggregator.rawFlow.collectAsStateWithLifecycle()
    val tabPrefs by viewModel.tabPrefs.collectAsStateWithLifecycle()
    val activeAxis by viewModel.activeAxis.collectAsStateWithLifecycle()
    val receptiveState by viewModel.receptiveState.collectAsStateWithLifecycle()
    val expressiveState by viewModel.expressiveState.collectAsStateWithLifecycle()

    var editingAxis by remember { mutableStateOf<SupportAxis?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bidet_app_name)) },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Filled.History, contentDescription = "History")
                    }
                    IconButton(onClick = onToggleRecording) {
                        if (isRecording) {
                            Icon(
                                Icons.Filled.Stop,
                                contentDescription = stringResource(R.string.bidet_stop_button),
                            )
                        } else {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = stringResource(R.string.bidet_record_button),
                            )
                        }
                    }
                },
            )
        }
    ) { contentPadding ->
        Column(modifier = Modifier.padding(contentPadding).fillMaxSize()) {
            if (isRecording && recordingStartedAtMs > 0L) {
                RecordingHeader(
                    startedAtMs = recordingStartedAtMs,
                    onStop = onToggleRecording,
                )
            }

            // RAW reading base — always visible. Takes ~half the screen via weight.
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                RawTabContent(rawText = raw, isRecording = isRecording)
            }

            HorizontalDivider()

            TabChipRow(
                prefs = tabPrefs,
                activeAxis = activeAxis,
                onSelectAxis = { viewModel.selectAxis(it) },
                onEditAxis = { editingAxis = it },
                editingEnabled = true,
            )

            // Active-axis generated body. Takes the other half of the screen.
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (activeAxis) {
                    SupportAxis.RECEPTIVE -> CleanTabContent(
                        axis = SupportAxis.RECEPTIVE,
                        state = receptiveState,
                        onGenerate = { viewModel.generateReceptive() },
                    )
                    SupportAxis.EXPRESSIVE -> CleanTabContent(
                        axis = SupportAxis.EXPRESSIVE,
                        state = expressiveState,
                        onGenerate = { viewModel.generateExpressive() },
                    )
                }
            }
        }
    }

    // Bottom-sheet editor. Hosting the sheet at the screen level (rather than inside the
    // chip row) keeps the row composable stateless and lets us pull the latest TabPref
    // each time the user reopens — so a Reset followed by a re-open shows defaults.
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
                // Refresh-then-close so the next sheet open shows the new defaults — without
                // closing here, the same draft stays in the OutlinedTextFields because they
                // remember-saveable on initialLabel/initialPrompt.
                editingAxis = null
            },
            onDismiss = { editingAxis = null },
        )
    }

    LaunchedEffect(isRecording) {
        if (isRecording) viewModel.resetTabs()
    }
}
