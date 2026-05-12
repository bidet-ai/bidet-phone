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

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pure-state persistence tests for the two-tab restructure (2026-05-10). Uses
 * [InMemoryTabPrefRepository] so the test runs under `./gradlew :app:test` without
 * Robolectric or a real Android Context. The DataStore-backed implementation has the same
 * contract.
 *
 * Spec — required by the task brief:
 *  1. Pure-state test for tab-prefs persistence: save name + prompt → reload → values match.
 *  2. Snapshot-style test for the default fall-through (no edits → defaults shown).
 */
class TabPrefRepositoryTest {

    /** Synthetic asset bodies handed in as the `defaultPrompt` argument. */
    private val receptiveDefault = "ASSET<receptive_default>"
    private val expressiveDefault = "ASSET<expressive_default>"

    /**
     * (1) Persistence round-trip for both label AND prompt. After a write, a fresh read
     * returns the values that were written — not the bundled defaults.
     */
    @Test
    fun saveAndReadBack_returnsWrittenLabelAndPrompt() = runBlocking {
        val repo = InMemoryTabPrefRepository()

        val edited = TabPref(
            axis = SupportAxis.EXPRESSIVE,
            label = "Email tone",
            promptTemplate = "Rewrite as a one-paragraph status update for a Slack channel.",
        )
        repo.write(edited)

        val readBack = repo.read(SupportAxis.EXPRESSIVE, expressiveDefault)
        assertEquals(SupportAxis.EXPRESSIVE, readBack.axis)
        assertEquals("Email tone", readBack.label)
        assertEquals(
            "Rewrite as a one-paragraph status update for a Slack channel.",
            readBack.promptTemplate,
        )
    }

    /**
     * (2) Default fall-through. With NO edits persisted, `read` returns the axis-default
     * label and the supplied default prompt — this is what the chip row paints on first
     * launch and the contract the live recorder relies on for "ship sensible defaults".
     */
    @Test
    fun read_withoutEdits_returnsDefaults() = runBlocking {
        val repo = InMemoryTabPrefRepository()

        val receptive = repo.read(SupportAxis.RECEPTIVE, receptiveDefault)
        assertEquals(SupportAxis.RECEPTIVE, receptive.axis)
        assertEquals("Clean for me", receptive.label)
        assertEquals(receptiveDefault, receptive.promptTemplate)

        val expressive = repo.read(SupportAxis.EXPRESSIVE, expressiveDefault)
        assertEquals(SupportAxis.EXPRESSIVE, expressive.axis)
        assertEquals("Clean for others", expressive.label)
        assertEquals(expressiveDefault, expressive.promptTemplate)
    }

    /**
     * Reset removes the user edit. After reset, a `read` falls back through to the bundled
     * defaults (including a NEW default prompt body, in case the next build ships an updated
     * prompt — the user benefits from the new default rather than being pinned to an
     * out-of-date asset).
     */
    @Test
    fun resetToDefault_clearsBothLabelAndPrompt() = runBlocking {
        val repo = InMemoryTabPrefRepository()

        repo.write(
            TabPref(
                axis = SupportAxis.RECEPTIVE,
                label = "My personal style",
                promptTemplate = "Reformat for a 7th-grade reader. Short sentences only.",
            )
        )
        val beforeReset = repo.read(SupportAxis.RECEPTIVE, receptiveDefault)
        assertEquals("My personal style", beforeReset.label)

        repo.resetToDefault(SupportAxis.RECEPTIVE)

        val afterReset = repo.read(SupportAxis.RECEPTIVE, receptiveDefault)
        assertEquals("Clean for me", afterReset.label)
        assertEquals(receptiveDefault, afterReset.promptTemplate)

        // Reset of one axis MUST NOT clobber the other.
        repo.write(
            TabPref(
                axis = SupportAxis.EXPRESSIVE,
                label = "Slack tone",
                promptTemplate = "Rewrite for a casual Slack channel.",
            )
        )
        repo.resetToDefault(SupportAxis.RECEPTIVE)
        val expressive = repo.read(SupportAxis.EXPRESSIVE, expressiveDefault)
        assertEquals("Slack tone", expressive.label)
    }

    /** Axis isolation — writing one axis must not mutate the other. */
    @Test
    fun perAxisIsolation_writingOneAxisDoesNotAffectOther() = runBlocking {
        val repo = InMemoryTabPrefRepository()
        repo.write(
            TabPref(
                axis = SupportAxis.RECEPTIVE,
                label = "Reading mode",
                promptTemplate = "Bullet list please.",
            )
        )
        val expressive = repo.read(SupportAxis.EXPRESSIVE, expressiveDefault)
        assertEquals("Clean for others", expressive.label)
        assertEquals(expressiveDefault, expressive.promptTemplate)
        assertNotEquals(
            "Receptive write must not leak into Expressive's label",
            "Reading mode",
            expressive.label,
        )
    }

    /**
     * Subsequent writes overwrite. Mark edits "Clean for others" twice: first to "Email
     * tone", then to "Slack tone". The latter must win.
     */
    @Test
    fun subsequentWrites_overwriteEarlier() = runBlocking {
        val repo = InMemoryTabPrefRepository()
        repo.write(TabPref(SupportAxis.EXPRESSIVE, "Email tone", "P1"))
        repo.write(TabPref(SupportAxis.EXPRESSIVE, "Slack tone", "P2"))

        val final = repo.read(SupportAxis.EXPRESSIVE, expressiveDefault)
        assertEquals("Slack tone", final.label)
        assertEquals("P2", final.promptTemplate)
    }

    /**
     * DataStore key uniqueness — axes must not collide on the same key. Lightweight assertion
     * that the keys are distinct so a future refactor can't silently merge them.
     *
     * v20 (2026-05-11): JUDGES axis added — verify its keys don't collide with the other two.
     */
    @Test
    fun perAxisKeysAreDistinct() {
        assertNotEquals(
            TabPrefRepository.labelKey(SupportAxis.RECEPTIVE),
            TabPrefRepository.labelKey(SupportAxis.EXPRESSIVE),
        )
        assertNotEquals(
            TabPrefRepository.labelKey(SupportAxis.RECEPTIVE),
            TabPrefRepository.labelKey(SupportAxis.JUDGES),
        )
        assertNotEquals(
            TabPrefRepository.labelKey(SupportAxis.EXPRESSIVE),
            TabPrefRepository.labelKey(SupportAxis.JUDGES),
        )
        assertNotEquals(
            TabPrefRepository.promptKey(SupportAxis.RECEPTIVE),
            TabPrefRepository.promptKey(SupportAxis.EXPRESSIVE),
        )
        assertNotEquals(
            TabPrefRepository.promptKey(SupportAxis.RECEPTIVE),
            TabPrefRepository.promptKey(SupportAxis.JUDGES),
        )
        assertNotEquals(
            TabPrefRepository.promptKey(SupportAxis.EXPRESSIVE),
            TabPrefRepository.promptKey(SupportAxis.JUDGES),
        )
        // label and prompt within an axis also distinct.
        assertNotEquals(
            TabPrefRepository.labelKey(SupportAxis.RECEPTIVE),
            TabPrefRepository.promptKey(SupportAxis.RECEPTIVE),
        )
        assertNotEquals(
            TabPrefRepository.labelKey(SupportAxis.JUDGES),
            TabPrefRepository.promptKey(SupportAxis.JUDGES),
        )
    }
}
