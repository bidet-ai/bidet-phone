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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.R
import kotlinx.coroutines.launch

/**
 * v0.2 three-tab Compose screen. Brief: "Collapse 4 tabs into 3 (RAW / Clean for me /
 * Clean for others)".
 *
 * Tab indices (locked):
 *   0 — RAW                — live, autoscroll while recording. RawTabContent.
 *   1 — Clean for me       — RECEPTIVE Support (output FOR the speaker).
 *   2 — Clean for others   — EXPRESSIVE Support (output FROM the speaker).
 *
 * The v0.1 four-tab layout (RAW / CLEAN / ANALYSIS / FORAI) collapsed to three because
 * CLEAN's "readable summary" use-case and ANALYSIS's "tangent-organized analysis" use-case
 * are both shapes of the same Receptive Support axis — different presets of one tab is the
 * right machinery, not separate tabs. Tab 4's customizable-prompt machinery now applies to
 * BOTH Clean tabs via [SupportPreset]'s axis field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BidetTabsScreen(
    viewModel: BidetTabsViewModel,
    isRecording: Boolean,
    onToggleRecording: () -> Unit,
    onOpenHistory: () -> Unit = {},
    // Bug B fix (2026-05-09): pass-through for the recording header timer. 0L when not
    // recording. Default keeps the kt-compatible signature for any test/preview call sites.
    recordingStartedAtMs: Long = 0L,
) {
    val pagerState = rememberPagerState(initialPage = TAB_INDEX_RAW, pageCount = { TAB_TITLES.size })
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bidet_app_name)) },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(
                            Icons.Filled.History,
                            contentDescription = "History",
                        )
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

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                Box(modifier = Modifier.fillMaxSize()) {
                    when (page) {
                        TAB_INDEX_RAW -> {
                            val raw by viewModel.aggregator.rawFlow.collectAsStateWithLifecycle()
                            RawTabContent(rawText = raw, isRecording = isRecording)
                        }
                        TAB_INDEX_CLEAN_FOR_ME -> {
                            val state by viewModel.receptiveState.collectAsStateWithLifecycle()
                            val activePreset by viewModel.receptivePreset.collectAsStateWithLifecycle()
                            val customPrompt by viewModel.receptiveCustomPrompt.collectAsStateWithLifecycle()
                            CleanTabContent(
                                axis = SupportAxis.RECEPTIVE,
                                state = state,
                                activePresetId = activePreset,
                                customPrompt = customPrompt,
                                rawTextProvider = { viewModel.currentRaw() },
                                onSelectPreset = { viewModel.setPreset(SupportAxis.RECEPTIVE, it) },
                                onSaveCustomPrompt = { viewModel.setCustomPrompt(SupportAxis.RECEPTIVE, it) },
                                onGenerate = { viewModel.generateReceptive() },
                            )
                        }
                        TAB_INDEX_CLEAN_FOR_OTHERS -> {
                            val state by viewModel.expressiveState.collectAsStateWithLifecycle()
                            val activePreset by viewModel.expressivePreset.collectAsStateWithLifecycle()
                            val customPrompt by viewModel.expressiveCustomPrompt.collectAsStateWithLifecycle()
                            CleanTabContent(
                                axis = SupportAxis.EXPRESSIVE,
                                state = state,
                                activePresetId = activePreset,
                                customPrompt = customPrompt,
                                rawTextProvider = { viewModel.currentRaw() },
                                onSelectPreset = { viewModel.setPreset(SupportAxis.EXPRESSIVE, it) },
                                onSaveCustomPrompt = { viewModel.setCustomPrompt(SupportAxis.EXPRESSIVE, it) },
                                onGenerate = { viewModel.generateExpressive() },
                            )
                        }
                    }
                }
            }
        }
    }

    // When a new recording starts, reset all non-RAW tab states so user is not staring at
    // a stale Clean for me / Clean for others from the previous session.
    LaunchedEffect(isRecording) {
        if (isRecording) viewModel.resetTabs()
    }
}

const val TAB_INDEX_RAW: Int = 0
const val TAB_INDEX_CLEAN_FOR_ME: Int = 1
const val TAB_INDEX_CLEAN_FOR_OTHERS: Int = 2

private val TAB_TITLES: List<Int> = listOf(
    R.string.bidet_tab_raw,
    R.string.bidet_tab_clean_for_me,
    R.string.bidet_tab_clean_for_others,
)
