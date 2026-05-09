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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R

/**
 * Shared body for the v0.2 Clean-for-me (Receptive) and Clean-for-others (Expressive) tabs.
 *
 * What replaced:
 *  - v0.1 [CleanTabContent] (old simple body) — folded in here.
 *  - v0.1 [AnalysisTabContent] — Tangent-Organizer is now a Receptive preset chip.
 *  - v0.1 [ForaiTabContent] — its preset-chip + custom-prompt machinery is generalized to
 *    both Clean tabs via the [SupportAxis] parameter.
 *
 * Layout per tab:
 *  - Section header ("Output style")
 *  - Horizontally-scrollable [FilterChip] row, one chip per [SupportPreset] for the active
 *    [axis]. The "Custom…" chip opens a [Tab4PromptSheet] for free-form prompt entry.
 *  - The cached output, with a "Show me what changed" toggle that swaps the plain output
 *    for a word-level diff against the RAW transcript ([DiffHighlighter]).
 *  - Long-press the cached output → copies to clipboard (existing affordance, kept).
 */
@Composable
fun CleanTabContent(
    axis: SupportAxis,
    state: TabState,
    activePresetId: String,
    customPrompt: String,
    rawTextProvider: () -> String,
    onSelectPreset: (String) -> Unit,
    onSaveCustomPrompt: (String) -> Unit,
    onGenerate: () -> Unit,
) {
    var sheetVisible by rememberSaveable { mutableStateOf(false) }
    var showDiff by rememberSaveable(axis) { mutableStateOf(false) }

    val presets = when (axis) {
        SupportAxis.RECEPTIVE -> SupportPreset.RECEPTIVE
        SupportAxis.EXPRESSIVE -> SupportPreset.EXPRESSIVE
    }
    val customPresetId = when (axis) {
        SupportAxis.RECEPTIVE -> SupportPreset.RECEPTIVE_CUSTOM.id
        SupportAxis.EXPRESSIVE -> SupportPreset.EXPRESSIVE_CUSTOM.id
    }
    val idleHintRes = when (axis) {
        SupportAxis.RECEPTIVE -> R.string.bidet_clean_for_me_idle_hint
        SupportAxis.EXPRESSIVE -> R.string.bidet_clean_for_others_idle_hint
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.bidet_preset_section_label))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presets.forEach { preset ->
                    val selected = preset.id == activePresetId
                    FilterChip(
                        selected = selected,
                        onClick = {
                            if (preset.isCustom) {
                                sheetVisible = true
                            } else {
                                onSelectPreset(preset.id)
                            }
                        },
                        label = { Text(stringResource(preset.labelResId)) },
                    )
                }
            }
        }

        // Diff toggle. Only meaningful once we have a cached output to compare against the
        // RAW transcript. Hidden in Idle/Generating/Failed so it doesn't distract.
        if (state is TabState.Cached) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Switch(checked = showDiff, onCheckedChange = { showDiff = it })
                Text(stringResource(R.string.bidet_show_changes_label))
            }
            if (showDiff) {
                Text(
                    text = stringResource(R.string.bidet_show_changes_legend),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        CleanTabBody(
            state = state,
            generateLabel = stringResource(R.string.bidet_generate_button),
            idleHint = stringResource(idleHintRes),
            showDiff = showDiff,
            rawTextProvider = rawTextProvider,
            onGenerate = onGenerate,
        )
    }

    if (sheetVisible) {
        Tab4PromptSheet(
            initialPrompt = customPrompt,
            onSave = { newPrompt ->
                onSaveCustomPrompt(newPrompt)
                onSelectPreset(customPresetId)
                sheetVisible = false
            },
            onDismiss = { sheetVisible = false },
        )
    }
}

/**
 * Body of the Clean tab. Forked from [GeneratableTabBody] so we can render a diffed
 * [AnnotatedString] in the Cached branch when [showDiff] is on. The Idle/Generating/Failed
 * branches are unchanged.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CleanTabBody(
    state: TabState,
    generateLabel: String,
    idleHint: String,
    showDiff: Boolean,
    rawTextProvider: () -> String,
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
                // as each chunk arrives — this is what replaces the v0.1 forever-spinner that
                // bit Mark on his 31-min brain dump.
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
                // Compute the diff lazily and only when the toggle is on. RAW is fetched
                // through a lambda so we read the latest aggregator snapshot rather than a
                // stale closure value.
                val annotated = if (showDiff) {
                    remember(state.text, state.generatedAt) {
                        DiffHighlighter.annotate(rawTextProvider(), state.text)
                    }
                } else {
                    AnnotatedString(state.text)
                }
                Text(
                    text = annotated,
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

/**
 * Modifier extension: long-press the modified element → fires [onCopy]. Wrapper exists so
 * [CleanTabBody]'s when-branch reads cleanly. The `text` param is unused at the modifier
 * layer but kept for call-site clarity (the caller can see what it's wiring up).
 */
@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableForCopy(text: String, onCopy: () -> Unit): Modifier =
    this.combinedClickable(
        onClick = { /* tap is no-op; long-press copies. RAW tab has an explicit Copy button. */ },
        onLongClick = onCopy,
    )

