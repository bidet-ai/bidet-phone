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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
            // v25 (2026-05-14): when the inline prompt editor is shown (EXPRESSIVE +
            // JUDGES axes), pass the live [TabState] and [onGenerate] callback so the
            // editor can render an explicit, prominent "Generate" button DIRECTLY
            // below the prompt block whenever the tab is in Idle / Failed state.
            // Fix 3 — Mark via UI dump: previously the Idle-state Generate button
            // lived only inside the CleanTabBody scroller below the editor; on a
            // Pixel 8 Pro at the new RAW 2f / Clean 1f vertical split the editor
            // pushed it off-screen. User saw "Prompt + Edit" and nothing else.
            // Mark's preference: explicit user control on Others / Judges since
            // those have the custom prompt — keep auto-trigger off, give the
            // tappable button a permanent home next to the prompt the user just read.
            InlinePromptEditor(
                state = inlinePrompt,
                tabState = state,
                generateLabel = stringResource(R.string.bidet_generate_button),
                onGenerate = onGenerate,
            )
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
private fun InlinePromptEditor(
    state: InlinePromptState,
    // v25 Fix 3 (2026-05-14): live tab state + generate trigger so the editor block
    // can render an explicit Generate button below the prompt body. These default to
    // null/no-op for any future caller that only wants the prompt editor without the
    // generate affordance, but the live recorder + history screens now pass them.
    tabState: TabState? = null,
    generateLabel: String = "Generate",
    onGenerate: () -> Unit = {},
) {
    val context = LocalContext.current
    // rememberSaveable keys on currentPrompt so a save-from-elsewhere updates the field;
    // but the user's in-progress draft survives recomposition.
    var draft by rememberSaveable(state.currentPrompt) { mutableStateOf(state.currentPrompt) }
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // v24 (2026-05-14): bumped horizontal padding 16 → 20 dp + vertical 8 → 12 dp.
            // Mark feedback: "boxes were really, really tiny... spacing on the page
            // was way off." Tighter 8 dp vertical packed the prompt block against the
            // chip row above + the output below; 12 dp gives a discernible gap.
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Prompt",
                // v24 (2026-05-14): explicit titleMedium (~16sp) for the "Prompt" header.
                // Default Text inherits bodyMedium which was indistinguishable from the
                // preview line below it.
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
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
                // v24 (2026-05-14): bumped from bodySmall (~12sp) to bodyMedium (~14sp)
                // — the collapsed preview was readable only by squinting.
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("System prompt") },
                modifier = Modifier.fillMaxWidth(),
                // v24 (2026-05-14): bumped minLines 4 → 6 so the text-input area is
                // comfortable to read/edit on a Pixel 8 Pro. The maxLines = 10 keeps
                // the chip row + clean output area visible when the editor is
                // expanded.
                minLines = 6,
                maxLines = 10,
                // v24 (2026-05-14): explicit bodyLarge so the editor text matches the
                // RAW tab's reading style. Default text-field typography defers to
                // theme's bodyLarge already, but making it explicit defends against a
                // future theme tweak that would shrink it.
                textStyle = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
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

        // v25 Fix 3 (2026-05-14): explicit Generate / Regenerate affordance pinned
        // directly under the prompt block. Mark's UI-dump report: on Others / Judges
        // the editor was visible but the Idle-state Generate button buried inside the
        // CleanTabBody scroller was either clipped at the new RAW 2f / Clean 1f
        // vertical split, or invisible after the user expanded the editor. Pinning
        // it here means user reads the prompt → taps Generate → inference fires. No
        // auto-trigger (matches Mark's "explicit control" preference for the
        // custom-prompt tabs). Button is hidden during in-flight inference to
        // prevent double-tap restarts.
        val isBusy = tabState is TabState.Streaming || tabState is TabState.Generating
        if (tabState != null && !isBusy) {
            val (label, tonal) = when (tabState) {
                is TabState.Cached -> "Regenerate" to true
                is TabState.Failed -> "Retry" to false
                else -> generateLabel to false
            }
            if (tonal) {
                FilledTonalButton(
                    onClick = onGenerate,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(label) }
            } else {
                Button(
                    onClick = onGenerate,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(label) }
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
        modifier = Modifier
            .fillMaxSize()
            // v24 (2026-05-14): bumped padding 16 → 20 dp horizontal + 16 dp vertical
            // so the cleaned-output body has breathing room from the screen edges +
            // the chip row above. Matches the RawTabContent padding update.
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        // v24 (2026-05-14): bumped inter-element spacing 12 → 16 dp so the progress
        // bar / partial-text / button stack reads as discrete blocks instead of a
        // crammed strip.
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        when (state) {
            is TabState.Idle -> {
                // v24 (2026-05-14): explicit bodyLarge so the idle hint is readable.
                Text(
                    text = idleHint,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(4.dp))
                Button(onClick = onGenerate) { Text(generateLabel) }
            }
            is TabState.Generating -> {
                Text(
                    stringResource(R.string.bidet_clean_gen_progress_label),
                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                )
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
                // v25 (2026-05-14): sticky "Cleaning part N of M…" banner for the
                // chunked long-dump path. Pinned ABOVE the scrolling partial Text so
                // it stays visible the entire chunk decode — v24 prefix-injected the
                // label into the stream and it scrolled off-screen as decode grew.
                // Null when not chunked / single-window generation.
                if (state.chunkLabel != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = state.chunkLabel,
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
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
