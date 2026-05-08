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

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/**
 * Shared body for the three on-demand tabs (CLEAN / ANALYSIS / FORAI). Renders a Generate
 * button when [TabState.Idle], a spinner when [TabState.Generating], the cached text +
 * Regenerate button when [TabState.Cached], and an error + retry when [TabState.Failed].
 *
 * Phase 4A: long-press the cached text → copies to clipboard + shows a brief Toast.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GeneratableTabBody(
    state: TabState,
    generateLabel: String,
    idleHint: String,
    onGenerate: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        when (state) {
            is TabState.Idle -> {
                Text(idleHint)
                Spacer(Modifier.height(4.dp))
                Button(onClick = onGenerate) { Text(generateLabel) }
            }
            is TabState.Generating -> {
                Text("Generating...")
                CircularProgressIndicator()
            }
            is TabState.Cached -> {
                Text(
                    text = state.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { /* tap is no-op; long-press copies */ },
                            onLongClick = {
                                clipboard.setText(AnnotatedString(state.text))
                                Toast.makeText(
                                    context,
                                    "Copied to clipboard",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        ),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onGenerate) { Text("Regenerate") }
            }
            is TabState.Failed -> {
                Text("Error: ${state.message}")
                Spacer(Modifier.height(8.dp))
                Button(onClick = onGenerate) { Text("Retry") }
            }
        }
    }
}
