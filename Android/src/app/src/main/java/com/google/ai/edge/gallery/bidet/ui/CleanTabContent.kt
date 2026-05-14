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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.bidet.a11y.A11yPreferences
import com.google.ai.edge.gallery.ui.theme.cleanTabBodyStyle

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
    /**
     * v22 (2026-05-13): optional inline prompt editor block. Currently rendered only on
     * the EXPRESSIVE (Clean for others) tab — Mark's quote: "clean for others where we
     * get to put the prompt in that should be updated just like it is on the web bidet".
     * The web Bidet has an editable prompt field above the output; this mirrors it.
     *
     * When non-null, [inlinePrompt] is drawn above [CleanTabBody]. The pencil/edit chip
     * route via the bottom sheet still works — this is the discoverable in-tab affordance.
     */
    inlinePrompt: InlinePromptState? = null,
) {
    val idleHintRes = when (axis) {
        SupportAxis.RECEPTIVE -> R.string.bidet_clean_for_me_idle_hint
        SupportAxis.EXPRESSIVE -> R.string.bidet_clean_for_others_idle_hint
        // v20 (2026-05-11): Clean-for-judges idle hint calls out the longer wall-clock
        // so users don't bail mid-decode thinking the tab is stuck.
        SupportAxis.JUDGES -> R.string.bidet_clean_for_judges_idle_hint
    }
    Column(modifier = Modifier.fillMaxSize()) {
        if (inlinePrompt != null) {
            InlinePromptEditor(state = inlinePrompt)
        }
        CleanTabBody(
            state = state,
            generateLabel = stringResource(R.string.bidet_generate_button),
            idleHint = stringResource(idleHintRes),
            onGenerate = onGenerate,
        )
    }
}

/**
 * v22 (2026-05-13): state container for the inline prompt editor on the EXPRESSIVE tab.
 *
 * @param currentPrompt the prompt text currently active for this axis (the bundled
 *   default if the user hasn't edited it, otherwise their saved override).
 * @param defaultPrompt the bundled default. Powers the "Reset to default" affordance —
 *   tapping it overwrites the draft with this value without going through persistence.
 * @param onSavePrompt invoked when the user taps Save. Persists via TabPrefRepository
 *   the same way the bottom-sheet editor does, so changes here also affect future
 *   recordings (consistent with existing tab-pref semantics).
 */
data class InlinePromptState(
    val currentPrompt: String,
    val defaultPrompt: String,
    val onSavePrompt: (String) -> Unit,
)

@Composable
private fun InlinePromptEditor(state: InlinePromptState) {
    val context = LocalContext.current
    // rememberSaveable keys on currentPrompt so a save-from-elsewhere updates the field;
    // but the user's in-progress draft survives recomposition.
    var draft by rememberSaveable(state.currentPrompt) { mutableStateOf(state.currentPrompt) }
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Prompt",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Edit")
            }
        }
        if (!expanded) {
            // Collapsed: show a short preview so the user knows the prompt without
            // bumping into the output area.
            Text(
                text = draft.take(120).let { if (draft.length > 120) "$it…" else it },
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("System prompt") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 10,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        state.onSavePrompt(draft)
                        expanded = false
                        android.widget.Toast
                            .makeText(context, "Prompt saved", android.widget.Toast.LENGTH_SHORT)
                            .show()
                    },
                    enabled = draft.isNotBlank() && draft != state.currentPrompt,
                ) { Text("Save") }
                OutlinedButton(
                    onClick = { draft = state.defaultPrompt },
                    enabled = draft != state.defaultPrompt,
                ) { Text("Reset to default") }
            }
        }
    }
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

    // bidet-ai a11y (2026-05-10, v0.3): observe the Clean-tab font picker so the cleaned-
    // output Text nodes restyle the moment the user picks a different option in Settings.
    // Default = Atkinson Hyperlegible. RAW transcript rendering is NOT wired to this flow
    // on purpose — verbatim text is the source of truth, not a piece of UX to skin. See
    // [A11yPreferences] + [CleanFontChoice] for the contract.
    val cleanFontChoice by remember(context) { A11yPreferences.observeCleanFontChoice(context) }
        .collectAsState(initial = A11yPreferences.DEFAULT_CLEAN_FONT_CHOICE)
    val cleanOutputStyle: TextStyle = cleanTabBodyStyle(cleanFontChoice)

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
                        style = cleanOutputStyle,
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
                    style = cleanOutputStyle,
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
