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

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

/**
 * Production [TabPrefRepository] backed by the existing bidet DataStore (see
 * `Context.bidetDataStore` in [BidetTabsViewModel]). Stores label + prompt under the keys
 * defined by [TabPrefRepository.Companion]. Reset is implemented by REMOVING the keys, so
 * the next read falls through to the values returned by [TabPref.Companion.defaultLabel] /
 * the asset path supplied by the caller.
 */
class DataStoreTabPrefRepository(
    private val context: Context,
) : TabPrefRepository {

    override suspend fun read(axis: SupportAxis, defaultPrompt: String): TabPref {
        val prefs = context.bidetDataStore.data.first()
        return TabPref(
            axis = axis,
            label = prefs[TabPrefRepository.labelKey(axis)] ?: TabPref.defaultLabel(axis),
            promptTemplate = prefs[TabPrefRepository.promptKey(axis)] ?: defaultPrompt,
        )
    }

    override suspend fun write(pref: TabPref) {
        context.bidetDataStore.edit { prefs ->
            prefs[TabPrefRepository.labelKey(pref.axis)] = pref.label
            prefs[TabPrefRepository.promptKey(pref.axis)] = pref.promptTemplate
        }
    }

    override suspend fun resetToDefault(axis: SupportAxis) {
        context.bidetDataStore.edit { prefs ->
            prefs.remove(TabPrefRepository.labelKey(axis))
            prefs.remove(TabPrefRepository.promptKey(axis))
        }
    }
}
