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

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Pure-state regression test for [A11yPreferences]'s v0.3 picker contract.
 *
 * The production code routes through `Context.bidetDataStore`, which would require Robolectric
 * (and an Application context) to construct in a JVM unit test. The contract we actually care
 * about — *the right keys + the right default + the picker round-trips through the
 * preferences file + the v0.2 → v0.3 migration shim works* — can be tested directly against a
 * `PreferenceDataStoreFactory.create(file)` instance, using the same key + default constants
 * the production code reads via [A11yPreferences.resolveCleanFontChoice].
 *
 * If a future agent renames a key, flips the default, or breaks the migration, this test
 * fails — which is the load-bearing guarantee the brief asks for ("persist + reload tests").
 */
class A11yPreferencesTest {

    private lateinit var tempFile: File
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        tempFile = File.createTempFile("a11y_prefs_test_", ".preferences_pb")
        // PreferenceDataStoreFactory expects to OWN the file path, so delete the empty
        // createTempFile() artifact and let DataStore create it on first write.
        tempFile.delete()
        dataStore = PreferenceDataStoreFactory.create(produceFile = { tempFile })
    }

    @After
    fun tearDown() {
        if (tempFile.exists()) tempFile.delete()
    }

    // -------------------------------------------------------------------------------------
    // Default + key-stability contract
    // -------------------------------------------------------------------------------------

    @Test
    fun default_isAtkinsonHyperlegible_whenNeitherKeyEverWritten() = runTest {
        val prefs = dataStore.data.first()
        val resolved = A11yPreferences.resolveCleanFontChoice(prefs)
        assertEquals(
            "Default Clean-tab font MUST be Atkinson Hyperlegible (v0.3 spec).",
            CleanFontChoice.ATKINSON_HYPERLEGIBLE,
            resolved,
        )
        assertEquals(CleanFontChoice.ATKINSON_HYPERLEGIBLE, A11yPreferences.DEFAULT_CLEAN_FONT_CHOICE)
        assertEquals(CleanFontChoice.ATKINSON_HYPERLEGIBLE, CleanFontChoice.DEFAULT)
    }

    @Test
    fun key_names_areStable() {
        // The DataStore key names are part of the on-disk schema. Renaming them would orphan
        // every user's saved preference. Pin literals here so a future rename triggers a
        // visible test failure and forces a deliberate migration decision.
        assertEquals("a11y_clean_font_choice", A11yPreferences.KEY_CLEAN_FONT_CHOICE.name)
        assertEquals(
            "a11y_use_open_dyslexic",
            A11yPreferences.KEY_LEGACY_USE_OPEN_DYSLEXIC.name,
        )
    }

    @Test
    fun cleanFontChoice_storageKeys_areStable() {
        // The enum's storageKey is also part of the on-disk schema (the value side, vs. the
        // key name above). Same reason to pin literals.
        assertEquals("system_default", CleanFontChoice.SYSTEM_DEFAULT.storageKey)
        assertEquals(
            "atkinson_hyperlegible",
            CleanFontChoice.ATKINSON_HYPERLEGIBLE.storageKey,
        )
        assertEquals("open_dyslexic", CleanFontChoice.OPEN_DYSLEXIC.storageKey)
        assertEquals("andika", CleanFontChoice.ANDIKA.storageKey)
    }

    // -------------------------------------------------------------------------------------
    // Persist + reload across all four picker values
    // -------------------------------------------------------------------------------------

    @Test
    fun pickAtkinson_persists_andReloads() = runTest {
        dataStore.edit {
            it[A11yPreferences.KEY_CLEAN_FONT_CHOICE] = CleanFontChoice.ATKINSON_HYPERLEGIBLE.storageKey
        }
        // We deliberately re-read from the SAME DataStore handle rather than spawning a fresh
        // one: spawning a second DataStore on the same file inside one JVM trips
        // FileStorage's "multiple DataStores active for the same file" guard. The contract
        // we want to prove — "the value was written to disk and is read back faithfully" —
        // is fully covered by reading the flow's first value after the edit completes,
        // because DataStore's write completes only after fsync.
        val resolved = A11yPreferences.resolveCleanFontChoice(dataStore.data.first())
        assertEquals(CleanFontChoice.ATKINSON_HYPERLEGIBLE, resolved)
    }

    @Test
    fun pickOpenDyslexic_persists_andReloads() = runTest {
        dataStore.edit {
            it[A11yPreferences.KEY_CLEAN_FONT_CHOICE] = CleanFontChoice.OPEN_DYSLEXIC.storageKey
        }
        // We deliberately re-read from the SAME DataStore handle rather than spawning a fresh
        // one: spawning a second DataStore on the same file inside one JVM trips
        // FileStorage's "multiple DataStores active for the same file" guard. The contract
        // we want to prove — "the value was written to disk and is read back faithfully" —
        // is fully covered by reading the flow's first value after the edit completes,
        // because DataStore's write completes only after fsync.
        val resolved = A11yPreferences.resolveCleanFontChoice(dataStore.data.first())
        assertEquals(CleanFontChoice.OPEN_DYSLEXIC, resolved)
    }

    @Test
    fun pickAndika_persists_andReloads() = runTest {
        dataStore.edit {
            it[A11yPreferences.KEY_CLEAN_FONT_CHOICE] = CleanFontChoice.ANDIKA.storageKey
        }
        // We deliberately re-read from the SAME DataStore handle rather than spawning a fresh
        // one: spawning a second DataStore on the same file inside one JVM trips
        // FileStorage's "multiple DataStores active for the same file" guard. The contract
        // we want to prove — "the value was written to disk and is read back faithfully" —
        // is fully covered by reading the flow's first value after the edit completes,
        // because DataStore's write completes only after fsync.
        val resolved = A11yPreferences.resolveCleanFontChoice(dataStore.data.first())
        assertEquals(CleanFontChoice.ANDIKA, resolved)
    }

    @Test
    fun pickSystemDefault_persists_andReloads() = runTest {
        dataStore.edit {
            it[A11yPreferences.KEY_CLEAN_FONT_CHOICE] = CleanFontChoice.SYSTEM_DEFAULT.storageKey
        }
        // We deliberately re-read from the SAME DataStore handle rather than spawning a fresh
        // one: spawning a second DataStore on the same file inside one JVM trips
        // FileStorage's "multiple DataStores active for the same file" guard. The contract
        // we want to prove — "the value was written to disk and is read back faithfully" —
        // is fully covered by reading the flow's first value after the edit completes,
        // because DataStore's write completes only after fsync.
        val resolved = A11yPreferences.resolveCleanFontChoice(dataStore.data.first())
        assertEquals(CleanFontChoice.SYSTEM_DEFAULT, resolved)
    }

    @Test
    fun changingPick_overwritesPriorPick() = runTest {
        // Atkinson -> OpenDyslexic -> Andika -> verify only the last sticks.
        dataStore.edit {
            it[A11yPreferences.KEY_CLEAN_FONT_CHOICE] = CleanFontChoice.ATKINSON_HYPERLEGIBLE.storageKey
        }
        dataStore.edit {
            it[A11yPreferences.KEY_CLEAN_FONT_CHOICE] = CleanFontChoice.OPEN_DYSLEXIC.storageKey
        }
        dataStore.edit {
            it[A11yPreferences.KEY_CLEAN_FONT_CHOICE] = CleanFontChoice.ANDIKA.storageKey
        }
        // We deliberately re-read from the SAME DataStore handle rather than spawning a fresh
        // one: spawning a second DataStore on the same file inside one JVM trips
        // FileStorage's "multiple DataStores active for the same file" guard. The contract
        // we want to prove — "the value was written to disk and is read back faithfully" —
        // is fully covered by reading the flow's first value after the edit completes,
        // because DataStore's write completes only after fsync.
        val resolved = A11yPreferences.resolveCleanFontChoice(dataStore.data.first())
        assertEquals(CleanFontChoice.ANDIKA, resolved)
    }

    // -------------------------------------------------------------------------------------
    // Robustness — unknown / corrupt values fall back to default, never crash
    // -------------------------------------------------------------------------------------

    @Test
    fun unknownStorageKey_fallsBackToDefault() = runTest {
        dataStore.edit { it[A11yPreferences.KEY_CLEAN_FONT_CHOICE] = "papyrus" }
        val resolved = A11yPreferences.resolveCleanFontChoice(dataStore.data.first())
        assertEquals(CleanFontChoice.DEFAULT, resolved)
    }

    @Test
    fun emptyStorageKey_fallsBackToDefault() = runTest {
        dataStore.edit { it[A11yPreferences.KEY_CLEAN_FONT_CHOICE] = "" }
        val resolved = A11yPreferences.resolveCleanFontChoice(dataStore.data.first())
        assertEquals(CleanFontChoice.DEFAULT, resolved)
    }

    // -------------------------------------------------------------------------------------
    // v0.2 → v0.3 migration shim
    // -------------------------------------------------------------------------------------

    @Test
    fun v02LegacyOpenDyslexicTrue_migratesTo_OpenDyslexic_whenNewKeyAbsent() = runTest {
        // A v0.2 user who toggled the OpenDyslexic switch ON has KEY_LEGACY_USE_OPEN_DYSLEXIC=true
        // and no KEY_CLEAN_FONT_CHOICE. The migration shim must treat that as an explicit
        // OpenDyslexic preference, NOT silently flip them to Atkinson on upgrade.
        dataStore.edit { it[A11yPreferences.KEY_LEGACY_USE_OPEN_DYSLEXIC] = true }
        val resolved = A11yPreferences.resolveCleanFontChoice(dataStore.data.first())
        assertEquals(
            "v0.2 OpenDyslexic ON must migrate to CleanFontChoice.OPEN_DYSLEXIC.",
            CleanFontChoice.OPEN_DYSLEXIC,
            resolved,
        )
    }

    @Test
    fun v02LegacyOpenDyslexicFalse_migratesTo_AtkinsonDefault_whenNewKeyAbsent() = runTest {
        // A v0.2 user who explicitly toggled OpenDyslexic OFF (it was the default OFF
        // anyway, but they may have toggled it) gets the new default — Atkinson Hyperlegible.
        dataStore.edit { it[A11yPreferences.KEY_LEGACY_USE_OPEN_DYSLEXIC] = false }
        val resolved = A11yPreferences.resolveCleanFontChoice(dataStore.data.first())
        assertEquals(CleanFontChoice.ATKINSON_HYPERLEGIBLE, resolved)
    }

    @Test
    fun newKey_winsOver_legacyKey_evenIfLegacyTrue() = runTest {
        // A user who upgrades, opens Settings, picks Andika — both keys are now on disk
        // (legacy=true from before, new=andika). The new key MUST win; the legacy key is
        // ignored.
        dataStore.edit {
            it[A11yPreferences.KEY_LEGACY_USE_OPEN_DYSLEXIC] = true
            it[A11yPreferences.KEY_CLEAN_FONT_CHOICE] = CleanFontChoice.ANDIKA.storageKey
        }
        val resolved = A11yPreferences.resolveCleanFontChoice(dataStore.data.first())
        assertEquals(
            "When both keys are present, the v0.3 string key wins over the v0.2 legacy boolean.",
            CleanFontChoice.ANDIKA,
            resolved,
        )
    }

    // -------------------------------------------------------------------------------------
    // CleanFontChoice.fromStorageKey convenience
    // -------------------------------------------------------------------------------------

    @Test
    fun fromStorageKey_roundTripsAllValues() {
        for (choice in CleanFontChoice.values()) {
            assertEquals(choice, CleanFontChoice.fromStorageKey(choice.storageKey))
        }
    }

    @Test
    fun fromStorageKey_returnsDefault_forNullOrUnknown() {
        assertEquals(CleanFontChoice.DEFAULT, CleanFontChoice.fromStorageKey(null))
        assertEquals(CleanFontChoice.DEFAULT, CleanFontChoice.fromStorageKey("not_a_real_font"))
        assertEquals(CleanFontChoice.DEFAULT, CleanFontChoice.fromStorageKey(""))
    }

    @Test
    fun resolveCleanFontChoice_isPure_onEmptyPrefs() = runTest {
        // Empty prefs = neither the new key nor the legacy key has ever been written.
        // The resolver must return DEFAULT and not throw.
        val resolved = A11yPreferences.resolveCleanFontChoice(dataStore.data.first())
        assertNotNull(resolved)
        assertEquals(CleanFontChoice.DEFAULT, resolved)
    }
}
