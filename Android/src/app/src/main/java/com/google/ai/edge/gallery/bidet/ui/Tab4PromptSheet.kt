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
 * BottomSheet for entering a free-form Tab 4 prompt. Brief §"Tab 4 customizable prompt".
 *
 * Behaviour:
 *  - Initialized with the currently-saved custom prompt (so editing existing text is
 *    possible, not just starting fresh).
 *  - "Save and use" persists the prompt via [onSave] and dismisses the sheet. The host
 *    composable is expected to flip the active preset to [Tab4Preset.CUSTOM] in the same
 *    callback.
 *  - "Cancel" / dismissal discards in-progress edits — the persisted prompt is unchanged.
 *  - Empty input is allowed but Save is disabled until at least one non-whitespace character
 *    is entered, to prevent the inference falling through to the FORAI default silently.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Tab4PromptSheet(
    initialPrompt: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by rememberSaveable(initialPrompt) { mutableStateOf(initialPrompt) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.bidet_tab4_sheet_title))
            Text(stringResource(R.string.bidet_tab4_sheet_hint))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(stringResource(R.string.bidet_tab4_sheet_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 6,
                maxLines = 16,
            )
            Spacer(Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSave(draft) },
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.bidet_tab4_sheet_save)) }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.bidet_tab4_sheet_cancel)) }
            }
        }
    }
}
