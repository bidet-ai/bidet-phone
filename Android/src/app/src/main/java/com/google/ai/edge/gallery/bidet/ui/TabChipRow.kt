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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R

/**
 * Two-tab chip row. Each chip is `[label] [edit-icon]`:
 *  - Tap the chip → select the axis (the parent re-renders with this axis's [TabState]).
 *  - Tap the edit pencil → open [TabPrefEditorSheet] for that axis (the parent owns the
 *    sheet visibility flag and the persistence wiring).
 *
 * Architecture note: this composable does NOT host the editor sheet. We pass two callbacks
 * out — [onSelectAxis] and [onEditAxis] — and let the parent screen ([BidetTabsScreen] /
 * [SessionDetailScreen]) decide whether tapping the pencil is meaningful. The history view
 * disables editing via [editingEnabled] so users don't accidentally rename a tab while
 * looking at a cached recording from yesterday — edits belong to the live capture flow.
 *
 * Future-proofing for the optional `+` button: the row layout is a [Row] with [Arrangement]
 * supporting trailing children, so a future flag-gated `+` button can be appended without
 * relayout. We deliberately do NOT paint that button today — the spec said "don't actually
 * add the +".
 */
@Composable
fun TabChipRow(
    prefs: List<TabPref>,
    activeAxis: SupportAxis,
    onSelectAxis: (SupportAxis) -> Unit,
    onEditAxis: (SupportAxis) -> Unit,
    editingEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Iterate by SupportAxis.ALL so slot order is locked left-to-right regardless of
        // the order [prefs] arrives in.
        SupportAxis.ALL.forEach { axis ->
            val pref = prefs.firstOrNull { it.axis == axis }
                ?: TabPref(axis, TabPref.defaultLabel(axis), "")
            val selected = activeAxis == axis
            FilterChip(
                selected = selected,
                onClick = { onSelectAxis(axis) },
                label = { Text(pref.label) },
            )
            if (editingEnabled) {
                IconButton(
                    onClick = { onEditAxis(axis) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.bidet_edit_tab_label),
                    )
                }
            } else {
                Spacer(Modifier.size(4.dp))
            }
        }
    }
}
