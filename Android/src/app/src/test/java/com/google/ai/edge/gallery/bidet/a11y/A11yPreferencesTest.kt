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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Pure-state regression test for [A11yPreferences].
 *
 * The production code routes through `Context.bidetDataStore`, which would require Robolectric
 * (and an Application context) to construct in a JVM unit test. The contract we actually care
 * about — *the right key + the right default + the toggle round-trips through the preferences
 * file* — can be tested directly against a `PreferenceDataStoreFactory.create(file)` instance,
 * using the same `KEY_USE_OPEN_DYSLEXIC` and `DEFAULT_USE_OPEN_DYSLEXIC` constants that the
 * production code reads.
 *
 * If a future agent renames the key or flips the default, this test fails — which is the
 * load-bearing guarantee the brief asks for ("toggle persists and reloads").
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

    @Test
    fun default_isOff_whenKeyNeverWritten() = runTest {
        val prefs = dataStore.data.first()
        val value = prefs[A11yPreferences.KEY_USE_OPEN_DYSLEXIC]
            ?: A11yPreferences.DEFAULT_USE_OPEN_DYSLEXIC
        assertFalse("OpenDyslexic toggle MUST default OFF — opt-in only", value)
        assertEquals(false, A11yPreferences.DEFAULT_USE_OPEN_DYSLEXIC)
    }

    @Test
    fun toggleOn_persists_andReloads() = runTest {
        // Write ON.
        dataStore.edit { it[A11yPreferences.KEY_USE_OPEN_DYSLEXIC] = true }
        // Reload from a fresh handle on the SAME file path — proves it survived the write
        // boundary (this is the "persists and reloads" contract).
        val reloaded = PreferenceDataStoreFactory.create(produceFile = { tempFile })
        val value = reloaded.data.first()[A11yPreferences.KEY_USE_OPEN_DYSLEXIC]
        assertTrue("Toggle ON must persist", value == true)
    }

    @Test
    fun toggleOff_persists_andReloads() = runTest {
        // Write ON, then OFF — verify the OFF state persists too (not just the first write).
        dataStore.edit { it[A11yPreferences.KEY_USE_OPEN_DYSLEXIC] = true }
        dataStore.edit { it[A11yPreferences.KEY_USE_OPEN_DYSLEXIC] = false }
        val reloaded = PreferenceDataStoreFactory.create(produceFile = { tempFile })
        val value = reloaded.data.first()[A11yPreferences.KEY_USE_OPEN_DYSLEXIC]
        assertEquals(false, value)
    }

    @Test
    fun key_name_isStable() {
        // The DataStore key name is part of the on-disk schema. Renaming it would orphan
        // every user's saved preference. Pin the literal here so a future rename triggers a
        // visible test failure and forces a deliberate migration decision.
        assertEquals("a11y_use_open_dyslexic", A11yPreferences.KEY_USE_OPEN_DYSLEXIC.name)
    }
}
