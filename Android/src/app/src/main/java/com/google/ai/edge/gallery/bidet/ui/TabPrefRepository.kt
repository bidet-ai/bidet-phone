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

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Pure-Kotlin contract for reading + writing user-edited [TabPref] values. The Android-side
 * implementation lives next to the ViewModel and is backed by the bidet DataStore; tests
 * implement this with an in-memory map so the persistence behaviour can be verified without
 * Robolectric or a real Android Context.
 *
 * Persistence shape (DataStore preference keys):
 *   tab_pref_<receptive|expressive>_label   → user-edited label string (chip text)
 *   tab_pref_<receptive|expressive>_prompt  → user-edited prompt template
 *
 * If a key is absent for a given axis, the consumer should fall back to the defaults
 * defined in [TabPref.Companion]. "Reset to default" is implemented by REMOVING the key —
 * the absence is what makes the defaults reappear.
 *
 * Why DataStore over a Room table: the dataset is a handful of short key/value pairs (one
 * label + one prompt per axis — three axes as of v20), the existing `Context.bidetDataStore`
 * already exists in this codebase, and a Room migration just to hold this much data is
 * overkill. Tests run against the in-memory implementation below so persistence is verifiable
 * without Android.
 */
interface TabPrefRepository {

    /** Read the current [TabPref] for [axis], or the bundled defaults if no edits exist. */
    suspend fun read(axis: SupportAxis, defaultPrompt: String): TabPref

    /** Persist [pref]. Subsequent [read] calls for the same axis must reflect the new values. */
    suspend fun write(pref: TabPref)

    /**
     * Restore axis defaults. Implementations remove the persisted keys so the next [read]
     * call falls through to whatever the [TabPref.Companion] defaults are at that time.
     */
    suspend fun resetToDefault(axis: SupportAxis)

    companion object {
        fun labelKey(axis: SupportAxis): Preferences.Key<String> = when (axis) {
            SupportAxis.RECEPTIVE -> stringPreferencesKey("tab_pref_receptive_label")
            SupportAxis.EXPRESSIVE -> stringPreferencesKey("tab_pref_expressive_label")
            // v20 (2026-05-11): JUDGES keys for the Clean-for-judges contest-pitch tab.
            SupportAxis.JUDGES -> stringPreferencesKey("tab_pref_judges_label")
        }

        fun promptKey(axis: SupportAxis): Preferences.Key<String> = when (axis) {
            SupportAxis.RECEPTIVE -> stringPreferencesKey("tab_pref_receptive_prompt")
            SupportAxis.EXPRESSIVE -> stringPreferencesKey("tab_pref_expressive_prompt")
            SupportAxis.JUDGES -> stringPreferencesKey("tab_pref_judges_prompt")
        }
    }
}

/**
 * Pure-Kotlin in-memory implementation of [TabPrefRepository]. Used by tests so persistence
 * behaviour (write → read returns the written value; reset → read falls through to defaults)
 * is verifiable without Robolectric.
 *
 * Production code uses [DataStoreTabPrefRepository] instead — same contract, persisted to
 * disk via the bidet DataStore.
 */
class InMemoryTabPrefRepository : TabPrefRepository {
    private val labels = mutableMapOf<SupportAxis, String>()
    private val prompts = mutableMapOf<SupportAxis, String>()

    override suspend fun read(axis: SupportAxis, defaultPrompt: String): TabPref = TabPref(
        axis = axis,
        label = labels[axis] ?: TabPref.defaultLabel(axis),
        promptTemplate = prompts[axis] ?: defaultPrompt,
    )

    override suspend fun write(pref: TabPref) {
        labels[pref.axis] = pref.label
        prompts[pref.axis] = pref.promptTemplate
    }

    override suspend fun resetToDefault(axis: SupportAxis) {
        labels.remove(axis)
        prompts.remove(axis)
    }
}
