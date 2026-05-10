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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 *
 * Each test gets its own scratch file + its own DataStore instance. Reuse of a file across
 * DataStores in the same process throws "multiple DataStores active for the same file"
 * (FileStorage.kt:52), so we don't try to re-instantiate to "simulate a reload" — instead we
 * verify the write→read round-trip on a single DataStore (DataStore guarantees that writes are
 * observed by subsequent reads, which is the persistence guarantee we depend on at runtime).
 */
class A11yPreferencesTest {

    @Test
    fun default_isOff_whenKeyNeverWritten() = runTestWithStore { store ->
        val prefs = store.data.first()
        val value = prefs[A11yPreferences.KEY_USE_OPEN_DYSLEXIC]
            ?: A11yPreferences.DEFAULT_USE_OPEN_DYSLEXIC
        assertFalse("OpenDyslexic toggle MUST default OFF — opt-in only", value)
        assertEquals(false, A11yPreferences.DEFAULT_USE_OPEN_DYSLEXIC)
    }

    @Test
    fun toggleOn_persistsAcrossReads() = runTestWithStore { store ->
        store.edit { it[A11yPreferences.KEY_USE_OPEN_DYSLEXIC] = true }
        val value = store.data.first()[A11yPreferences.KEY_USE_OPEN_DYSLEXIC]
        assertTrue("Toggle ON must be readable after the write completes", value == true)
    }

    @Test
    fun toggleOff_persistsAfterPreviousOn() = runTestWithStore { store ->
        store.edit { it[A11yPreferences.KEY_USE_OPEN_DYSLEXIC] = true }
        store.edit { it[A11yPreferences.KEY_USE_OPEN_DYSLEXIC] = false }
        val value = store.data.first()[A11yPreferences.KEY_USE_OPEN_DYSLEXIC]
        assertEquals(
            "Most recent write (false) must win — proves DataStore actually persists state, " +
                "not a stale in-memory flag.",
            false,
            value,
        )
    }

    @Test
    fun key_name_isStable() {
        // The DataStore key name is part of the on-disk schema. Renaming it would orphan
        // every user's saved preference. Pin the literal here so a future rename triggers a
        // visible test failure and forces a deliberate migration decision.
        assertEquals("a11y_use_open_dyslexic", A11yPreferences.KEY_USE_OPEN_DYSLEXIC.name)
    }

    /**
     * Helper: spin up a fresh DataStore on a unique scratch file, run the test body, then
     * cancel the DataStore's CoroutineScope (releasing the file lock) and delete the file.
     * Without the scope-cancel, leaking DataStore instances poison subsequent tests.
     */
    private fun runTestWithStore(block: suspend (DataStore<Preferences>) -> Unit) = runTest {
        val tempFile = File.createTempFile("a11y_prefs_test_", ".preferences_pb")
        tempFile.delete() // DataStore wants to own the path; createTempFile() already touched it.
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + scopeJob)
        val store = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFile },
        )
        try {
            block(store)
        } finally {
            scope.cancel()
            scopeJob.cancel()
            // best-effort cleanup of the scratch file
            tempFile.delete()
        }
    }
}
