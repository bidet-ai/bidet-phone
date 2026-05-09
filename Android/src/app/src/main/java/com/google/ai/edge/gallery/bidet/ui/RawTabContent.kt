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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * RAW tab — the live verbatim transcript stream. Brief §6.
 *
 * Behaviour:
 *  - Streaming text from [com.google.ai.edge.gallery.bidet.transcript.TranscriptAggregator.rawFlow]
 *    is collected in [BidetTabsScreen] and passed in here as a String.
 *  - While recording, the view autoscrolls to the bottom on every transcript update so the
 *    user sees the latest words appear.
 *  - When stopped, the user can scroll freely.
 *
 * Demo polish (2026-05-09): swapped `LazyColumn { item { ... } }` for a plain
 * `Column(verticalScroll(...))`. There was only ever one child and zero virtualization
 * benefit — the lazy-list machinery was a category error here.
 */
@Composable
fun RawTabContent(rawText: String, isRecording: Boolean) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
    ) {
        Text(
            text = rawText.ifEmpty {
                if (isRecording) "Listening..." else "Tap the microphone to begin recording."
            },
        )
    }
    LaunchedEffect(rawText, isRecording) {
        if (isRecording && rawText.isNotEmpty()) {
            // Keep the latest words visible as the transcript streams in.
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }
}
