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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
 * Bottom-sheet editor for a [TabPref]. Replaces the v0.2 Tab4PromptSheet (which only edited
 * the prompt) with a two-field editor: tab name + prompt template. Both fields persist
 * through [onSave].
 *
 * Layout (top → bottom):
 *  - Sheet title
 *  - Tab-name OutlinedTextField (single line)
 *  - Prompt-template OutlinedTextField (multi-line, 6-16 visible)
 *  - Save (primary) / Reset to default (text) / Cancel (outlined)
 *
 * [onResetToDefault] removes the user-edit and re-emits the bundled defaults; the host
 * composable should refresh the editor state from the repository so the user sees the
 * default values populate the fields immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabPrefEditorSheet(
    initialLabel: String,
    initialPrompt: String,
    onSave: (label: String, prompt: String) -> Unit,
    onResetToDefault: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // rememberSaveable keys on the initial values so re-opening the sheet on a different
    // axis (or after a reset) seeds the fields correctly. Without keying, the second open
    // would resurrect the previous axis's draft.
    var labelDraft by rememberSaveable(initialLabel) { mutableStateOf(initialLabel) }
    var promptDraft by rememberSaveable(initialPrompt) { mutableStateOf(initialPrompt) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.bidet_tab_editor_title))

            OutlinedTextField(
                value = labelDraft,
                onValueChange = { labelDraft = it },
                label = { Text(stringResource(R.string.bidet_tab_editor_label_field)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = promptDraft,
                onValueChange = { promptDraft = it },
                label = { Text(stringResource(R.string.bidet_tab_editor_prompt_field)) },
                placeholder = {
                    Text(stringResource(R.string.bidet_tab_editor_prompt_placeholder))
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 6,
                maxLines = 16,
            )
            Text(
                text = stringResource(R.string.bidet_tab_editor_prompt_hint),
            )

            Spacer(Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSave(labelDraft.trim(), promptDraft) },
                    enabled = labelDraft.isNotBlank() && promptDraft.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.bidet_tab_editor_save)) }
                TextButton(
                    onClick = onResetToDefault,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.bidet_tab_editor_reset)) }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.bidet_tab_editor_cancel)) }
            }
        }
    }
}
