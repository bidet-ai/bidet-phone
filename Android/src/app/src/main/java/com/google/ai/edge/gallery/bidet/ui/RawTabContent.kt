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

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
 *  - While recording, the list autoscrolls to the bottom on every transcript update so the
 *    user sees the latest words appear.
 *  - When stopped, the user can scroll freely.
 */
@Composable
fun RawTabContent(rawText: String, isRecording: Boolean) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(16.dp),
    ) {
        item {
            Text(
                text = rawText.ifEmpty {
                    if (isRecording) "Listening..." else "Tap the microphone to begin recording."
                },
            )
        }
    }
    LaunchedEffect(rawText, isRecording) {
        if (isRecording && rawText.isNotEmpty()) {
            listState.animateScrollToItem(0) // single-item list — keep at top so latest text is visible
        }
    }
}
