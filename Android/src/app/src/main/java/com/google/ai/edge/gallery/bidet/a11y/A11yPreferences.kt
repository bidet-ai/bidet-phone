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

package com.google.ai.edge.gallery.bidet.a11y

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.google.ai.edge.gallery.bidet.ui.bidetDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Bidet AI accessibility preferences.
 *
 * v0.2 (2026-05-10) initial entry: an opt-in toggle for rendering Clean-tab cleaned-output
 * text in the **OpenDyslexic** typeface. Defaults OFF — some readers find OpenDyslexic itself
 * harder to read than a conventional sans, and the choice belongs to the user.
 *
 * The toggle is observed by the Clean-tab rendering nodes via [observeUseOpenDyslexic]. It does
 * NOT affect:
 *  - The RAW transcript tab (verbatim text stays in the default app typography on purpose —
 *    the raw text is the source of truth, not a piece of UX to be re-skinned).
 *  - Settings, navigation, headers, chips, or any chrome.
 *
 * Backed by the existing `bidet` DataStore. No migration is needed — the preference is a
 * boolean and an absent key reads as false (the default).
 */
object A11yPreferences {

    /** DataStore key. Public-readable so tests in the same module can assert on it. */
    val KEY_USE_OPEN_DYSLEXIC = booleanPreferencesKey("a11y_use_open_dyslexic")

    /** Default value when the key has never been written. The toggle is opt-in. */
    const val DEFAULT_USE_OPEN_DYSLEXIC: Boolean = false

    /**
     * Reactive view of the current toggle value. Use this from Compose with `collectAsState()`
     * so the rendering node re-composes the moment the user flips the switch.
     */
    fun observeUseOpenDyslexic(context: Context): Flow<Boolean> =
        context.bidetDataStore.data.map { prefs ->
            prefs[KEY_USE_OPEN_DYSLEXIC] ?: DEFAULT_USE_OPEN_DYSLEXIC
        }

    /** One-shot read for non-Compose call sites (e.g. unit tests, settings screen init). */
    suspend fun isUseOpenDyslexicEnabled(context: Context): Boolean {
        val prefs = context.bidetDataStore.data.first()
        return prefs[KEY_USE_OPEN_DYSLEXIC] ?: DEFAULT_USE_OPEN_DYSLEXIC
    }

    /** Persist the toggle. Called from the settings screen. */
    suspend fun setUseOpenDyslexic(context: Context, enabled: Boolean) {
        context.bidetDataStore.edit { prefs ->
            prefs[KEY_USE_OPEN_DYSLEXIC] = enabled
        }
    }
}
