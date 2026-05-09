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

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R

/**
 * Tab 4 — the customizable-prompt tab. Brief v0.2 §"Tab 4 customizable prompt".
 *
 * Layout:
 *  - Section header ("Output style")
 *  - Horizontally-scrollable [FilterChip] row: one chip per [Tab4Preset]. The "Custom…" chip
 *    opens [Tab4PromptSheet] for free-form prompt entry.
 *  - The same generate / regenerate / copy / share affordances as the v0.1 FORAI tab,
 *    delegating to [GeneratableTabBody]. The system prompt the ViewModel uses is resolved
 *    at inference time from the active preset selection.
 *
 * The default preset ([Tab4Preset.FORAI]) preserves byte-for-byte v0.1 behaviour for users
 * who never touch the chip row.
 */
@Composable
fun ForaiTabContent(
    state: TabState,
    activePresetId: String,
    customPrompt: String,
    onSelectPreset: (String) -> Unit,
    onSaveCustomPrompt: (String) -> Unit,
    onGenerate: () -> Unit,
) {
    var sheetVisible by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.bidet_tab4_section_label))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Tab4Preset.ALL.forEach { preset ->
                    val selected = preset.id == activePresetId
                    FilterChip(
                        selected = selected,
                        onClick = {
                            if (preset == Tab4Preset.CUSTOM) {
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
        GeneratableTabBody(
            state = state,
            generateLabel = stringResource(R.string.bidet_generate_button),
            idleHint = stringResource(R.string.bidet_tab4_idle_hint),
            onGenerate = onGenerate,
        )
    }

    if (sheetVisible) {
        Tab4PromptSheet(
            initialPrompt = customPrompt,
            onSave = { newPrompt ->
                onSaveCustomPrompt(newPrompt)
                onSelectPreset(Tab4Preset.CUSTOM.id)
                sheetVisible = false
            },
            onDismiss = { sheetVisible = false },
        )
    }
}
