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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import com.google.ai.edge.gallery.R

/**
 * RAW tab — the live verbatim transcript stream. Brief §6.
 *
 * Behaviour:
 *  - Streaming text from [com.google.ai.edge.gallery.bidet.transcript.TranscriptAggregator.rawFlow]
 *    is collected in [BidetTabsScreen] and passed in here as a String.
 *  - While recording, the view autoscrolls to the bottom on every transcript update so the
 *    user sees the latest words appear.
 *  - When stopped, the user can scroll freely.
 *  - v0.2 (2026-05-09): explicit "Copy" button below the transcript replaces the old
 *    long-press affordance (which Mark reported never worked on the Pixel — Compose's
 *    SelectionContainer requires a tap-and-hold-then-handle-drag flow that is unreliable on
 *    text that is also being autoscrolled). Tapping the button copies the full RAW text via
 *    [ClipboardManager.setPrimaryClip] and shows a Toast.
 *
 * Demo polish (2026-05-09): swapped `LazyColumn { item { ... } }` for a plain
 * `Column(verticalScroll(...))`. There was only ever one child and zero virtualization
 * benefit — the lazy-list machinery was a category error here.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RawTabContent(rawText: String, isRecording: Boolean) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState),
        ) {
            // v22 (2026-05-13): redundant long-press-to-copy affordance on the RAW
            // body. The explicit Copy button below stays (v0.2 was Mark's preferred
            // primary path because long-press on streaming autoscroll was unreliable);
            // adding combinedClickable here is the safety net for the post-stop case
            // when autoscroll has settled. Long-press → clipboard + Toast.
            Text(
                text = rawText.ifEmpty {
                    if (isRecording) "Listening..." else "Tap the microphone to begin recording."
                },
                modifier = if (rawText.isNotEmpty()) {
                    Modifier.combinedClickable(
                        onClick = { /* tap is no-op — long-press copies, Copy button is primary */ },
                        onLongClick = { copyRawToClipboard(context, rawText) },
                    )
                } else Modifier,
            )
        }

        // Copy button row. Hidden while there is no text yet (Idle / pre-recording state)
        // so the welcome copy doesn't fight for attention with an inert button.
        if (rawText.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { copyRawToClipboard(context, rawText) }) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.bidet_copy_button))
                }
            }
        }
    }
    LaunchedEffect(rawText, isRecording) {
        if (isRecording && rawText.isNotEmpty()) {
            // Keep the latest words visible as the transcript streams in.
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }
}

/**
 * Copy the full RAW transcript to the system clipboard. Uses [ClipboardManager] directly
 * (not Compose's LocalClipboardManager) so the label on the clip is the bidet app name —
 * paste targets that surface clip metadata (Gboard suggestions strip, ClipboardHistory) show
 * "Bidet AI · RAW transcript" instead of "AnnotatedString".
 */
private fun copyRawToClipboard(context: Context, rawText: String) {
    val cm: ClipboardManager? = context.getSystemService()
    if (cm == null) {
        Toast.makeText(context, "Clipboard unavailable on this device", Toast.LENGTH_SHORT).show()
        return
    }
    val clip = ClipData.newPlainText("Bidet AI · RAW transcript", rawText)
    cm.setPrimaryClip(clip)
    Toast.makeText(context, context.getString(R.string.bidet_copied_toast), Toast.LENGTH_SHORT).show()
}
