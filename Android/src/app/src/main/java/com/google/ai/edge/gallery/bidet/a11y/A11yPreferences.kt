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
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.ai.edge.gallery.bidet.ui.bidetDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Bidet AI accessibility preferences.
 *
 * **v0.3 (2026-05-10) — font picker replaces the v0.2 boolean toggle.**
 *
 * Until v0.2 this object stored a single boolean ("use OpenDyslexic, default OFF"). v0.3
 * replaces that with a 4-option [CleanFontChoice] picker (System default / Atkinson
 * Hyperlegible / OpenDyslexic / Andika), defaulting to [CleanFontChoice.ATKINSON_HYPERLEGIBLE]
 * because the evidence base for Atkinson is stronger than OpenDyslexic's. See
 * [CleanFontChoice] for the full rationale and license notes.
 *
 * The selection is observed by the Clean-tab rendering nodes via [observeCleanFontChoice]. It
 * does NOT affect:
 *  - The RAW transcript tab (verbatim text stays in the default app typography on purpose —
 *    the raw text is the source of truth, not a piece of UX to be re-skinned).
 *  - Settings, navigation, headers, chips, or any chrome.
 *
 * Migration from v0.2:
 *  Existing users who toggled the v0.2 OpenDyslexic switch ON have the boolean key
 *  `a11y_use_open_dyslexic = true` on disk. On first read after upgrade, [observeCleanFontChoice]
 *  notices the new string key is absent but the old boolean key is `true` and treats that as
 *  [CleanFontChoice.OPEN_DYSLEXIC] — preserving the user's explicit opt-in. Users who never
 *  toggled the v0.2 switch get the new default, [CleanFontChoice.ATKINSON_HYPERLEGIBLE].
 *
 *  The migration is read-side only — we don't rewrite the legacy boolean key on read because
 *  DataStore reads happen on a coroutine flow and writing inside a flow read can deadlock the
 *  preference store. The first explicit save via [setCleanFontChoice] will write the new
 *  string key; on the next read after that, the legacy boolean is ignored. This means a user
 *  who upgrades, never opens Settings, will have BOTH keys on disk — that's harmless because
 *  the read code prefers the new key when present.
 *
 * Backed by the existing `bidet` DataStore. No schema migration is needed; the new key lives
 * alongside the legacy key.
 */
object A11yPreferences {

    /**
     * Legacy v0.2 key. Kept here for the read-side migration shim only — new writes go to
     * [KEY_CLEAN_FONT_CHOICE]. Pinned literal name in the test suite to prevent accidental
     * rename.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    val KEY_LEGACY_USE_OPEN_DYSLEXIC = booleanPreferencesKey("a11y_use_open_dyslexic")

    /**
     * v0.3 key — stores the selected [CleanFontChoice] by its [CleanFontChoice.storageKey].
     * Public-readable so tests in the same module can assert on it.
     */
    val KEY_CLEAN_FONT_CHOICE = stringPreferencesKey("a11y_clean_font_choice")

    /** Default picker selection when the user has not chosen a font yet. */
    val DEFAULT_CLEAN_FONT_CHOICE: CleanFontChoice = CleanFontChoice.DEFAULT

    /**
     * Resolve the current font choice from a raw DataStore [androidx.datastore.preferences.core.Preferences]
     * snapshot. Pure function; the migration policy lives here so [observeCleanFontChoice] and
     * [getCleanFontChoice] share one source of truth, and so unit tests can exercise it
     * directly without an Android context.
     */
    internal fun resolveCleanFontChoice(
        prefs: androidx.datastore.preferences.core.Preferences
    ): CleanFontChoice {
        // New string key wins.
        val newKey = prefs[KEY_CLEAN_FONT_CHOICE]
        if (newKey != null) {
            return CleanFontChoice.fromStorageKey(newKey)
        }
        // v0.2 migration: if the legacy boolean was ON, the user explicitly opted into
        // OpenDyslexic — honor that.
        val legacy = prefs[KEY_LEGACY_USE_OPEN_DYSLEXIC]
        if (legacy == true) return CleanFontChoice.OPEN_DYSLEXIC
        // Otherwise, the new default.
        return DEFAULT_CLEAN_FONT_CHOICE
    }

    /**
     * Reactive view of the user's font choice. Use this from Compose with `collectAsState()` so
     * the rendering node re-composes the moment the user picks a different radio option in
     * Settings.
     */
    fun observeCleanFontChoice(context: Context): Flow<CleanFontChoice> =
        context.bidetDataStore.data.map { prefs -> resolveCleanFontChoice(prefs) }

    /** One-shot read for non-Compose call sites (e.g. unit tests, settings screen init). */
    suspend fun getCleanFontChoice(context: Context): CleanFontChoice {
        val prefs = context.bidetDataStore.data.first()
        return resolveCleanFontChoice(prefs)
    }

    /** Persist the user's choice. Called from the settings radio group. */
    suspend fun setCleanFontChoice(context: Context, choice: CleanFontChoice) {
        context.bidetDataStore.edit { prefs ->
            prefs[KEY_CLEAN_FONT_CHOICE] = choice.storageKey
        }
    }
}
