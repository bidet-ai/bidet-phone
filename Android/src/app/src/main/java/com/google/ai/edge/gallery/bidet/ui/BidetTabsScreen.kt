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
 * Top-level four-tab Compose screen for the brain-dump UX. Brief §6.
 *
 * Layout: Scaffold(TopAppBar with record/stop button) -> Column(TabRow + HorizontalPager).
 * The TabRow and PagerState are bidirectionally synced — tap or swipe both work.
 *
 * Tab indices (locked):
 *   0 — RAW       (live, autoscroll while recording)
 *   1 — CLEAN     (on-demand)
 *   2 — ANALYSIS  (on-demand)
 *   3 — FORAI     (on-demand, the anchor)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BidetTabsScreen(
    viewModel: BidetTabsViewModel,
    isRecording: Boolean,
    onToggleRecording: () -> Unit,
    onOpenHistory: () -> Unit = {},
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
                        TAB_INDEX_CLEAN -> {
                            val state by viewModel.cleanState.collectAsStateWithLifecycle()
                            CleanTabContent(state = state, onGenerate = { viewModel.generateClean() })
                        }
                        TAB_INDEX_ANALYSIS -> {
                            val state by viewModel.analysisState.collectAsStateWithLifecycle()
                            AnalysisTabContent(state = state, onGenerate = { viewModel.generateAnalysis() })
                        }
                        TAB_INDEX_FORAI -> {
                            val state by viewModel.foraiState.collectAsStateWithLifecycle()
                            ForaiTabContent(state = state, onGenerate = { viewModel.generateForai() })
                        }
                    }
                }
            }
        }
    }

    // When a new recording starts, reset all non-RAW tab states so user is not staring at
    // a stale CLEAN/ANALYSIS/FORAI from the previous session.
    LaunchedEffect(isRecording) {
        if (isRecording) viewModel.resetTabs()
    }
}

const val TAB_INDEX_RAW: Int = 0
const val TAB_INDEX_CLEAN: Int = 1
const val TAB_INDEX_ANALYSIS: Int = 2
const val TAB_INDEX_FORAI: Int = 3

private val TAB_TITLES: List<Int> = listOf(
    R.string.bidet_tab_raw,
    R.string.bidet_tab_clean,
    R.string.bidet_tab_analysis,
    R.string.bidet_tab_forai,
)
