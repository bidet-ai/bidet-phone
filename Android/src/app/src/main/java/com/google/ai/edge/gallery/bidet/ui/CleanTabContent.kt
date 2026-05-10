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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R

/**
 * Body for one of the two GENERATED tabs in the two-tab restructure (2026-05-10). Renders
 * the tab's current [TabState] — Idle / Generating / Streaming / Cached / Failed — under
 * the chip row owned by the parent screen.
 *
 * What changed from v0.2:
 *  - No preset chips inside this composable. The chip row + pencil/edit icon live in the
 *    parent screen ([BidetTabsScreen] / [SessionDetailScreen]). This file focuses on
 *    rendering the generation result for ONE selected axis.
 *  - The "Show me what changed" diff toggle was dropped per Mark's redirect — DiffHighlighter
 *    is gone and the Compose state for the toggle along with it.
 *  - Long-press copy on the Cached body is preserved (the explicit Copy button still lives on
 *    the RAW reading-base above).
 */
@Composable
fun CleanTabContent(
    axis: SupportAxis,
    state: TabState,
    onGenerate: () -> Unit,
) {
    val idleHintRes = when (axis) {
        SupportAxis.RECEPTIVE -> R.string.bidet_clean_for_me_idle_hint
        SupportAxis.EXPRESSIVE -> R.string.bidet_clean_for_others_idle_hint
    }
    CleanTabBody(
        state = state,
        generateLabel = stringResource(R.string.bidet_generate_button),
        idleHint = stringResource(idleHintRes),
        onGenerate = onGenerate,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CleanTabBody(
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
                Text(stringResource(R.string.bidet_clean_gen_progress_label))
                CircularProgressIndicator()
            }
            is TabState.Streaming -> {
                // Progress affordance: linear bar + "N / cap tokens" so the user can see the
                // generation is alive while Gemma decodes. The partial text below re-renders
                // as each chunk arrives.
                val cap = state.tokenCap.coerceAtLeast(1)
                val progress = (state.tokenCount.toFloat() / cap.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(
                        R.string.bidet_clean_gen_progress_format,
                        state.tokenCount,
                        state.tokenCap,
                    ),
                )
                if (state.partialText.isNotEmpty()) {
                    Text(
                        text = AnnotatedString(state.partialText),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            is TabState.Cached -> {
                Text(
                    text = state.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickableForCopy(
                            text = state.text,
                            onCopy = {
                                clipboard.setText(AnnotatedString(state.text))
                                android.widget.Toast
                                    .makeText(context, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT)
                                    .show()
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

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableForCopy(text: String, onCopy: () -> Unit): Modifier =
    this.combinedClickable(
        onClick = { /* tap is no-op; long-press copies. RAW base has an explicit Copy button. */ },
        onLongClick = onCopy,
    )
