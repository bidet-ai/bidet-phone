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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Snapshot-style tests for the default fall-through, per the task brief: "no edits → defaults
 * shown". This pins the literal default labels and the asset-path mapping for both tabs so
 * future churn can't silently change what the chip row reads on first launch.
 */
class TabPrefDefaultsTest {

    /**
     * On first launch (no edits persisted), the chip row paints exactly:
     *   slot 0: "Clean for me"
     *   slot 1: "Clean for others"
     */
    @Test
    fun defaultLabels_pinnedToContestCopy() {
        assertEquals("Clean for me", TabPref.defaultLabel(SupportAxis.RECEPTIVE))
        assertEquals("Clean for others", TabPref.defaultLabel(SupportAxis.EXPRESSIVE))
    }

    /**
     * Default prompt asset paths are the PR #28 fidelity-first prompts. The asset bodies must
     * not be edited in this PR; this test pins the path mapping so a refactor can't redirect
     * to a different file by accident.
     */
    @Test
    fun defaultPromptAssetPaths_pointAtPR28Files() {
        assertEquals(
            "prompts/receptive_default.txt",
            TabPref.defaultPromptAssetPath(SupportAxis.RECEPTIVE),
        )
        assertEquals(
            "prompts/expressive_default.txt",
            TabPref.defaultPromptAssetPath(SupportAxis.EXPRESSIVE),
        )
    }

    /**
     * The default-snapshot the chip row reads when the repository has zero edits. Renders
     * identically to a "no DataStore" fresh install. If this fails, the user-visible chip
     * row has changed.
     */
    @Test
    fun freshRepository_yieldsExactlyTwoDefaultPrefs() = runBlocking {
        val repo = InMemoryTabPrefRepository()
        val receptiveDefault = "RECEPTIVE_BUNDLED"
        val expressiveDefault = "EXPRESSIVE_BUNDLED"

        val snapshot = SupportAxis.ALL.map { axis ->
            val defaultPrompt = when (axis) {
                SupportAxis.RECEPTIVE -> receptiveDefault
                SupportAxis.EXPRESSIVE -> expressiveDefault
            }
            repo.read(axis, defaultPrompt)
        }

        assertEquals(2, snapshot.size)
        assertEquals(SupportAxis.RECEPTIVE, snapshot[0].axis)
        assertEquals("Clean for me", snapshot[0].label)
        assertEquals(receptiveDefault, snapshot[0].promptTemplate)

        assertEquals(SupportAxis.EXPRESSIVE, snapshot[1].axis)
        assertEquals("Clean for others", snapshot[1].label)
        assertEquals(expressiveDefault, snapshot[1].promptTemplate)
    }

    /**
     * After editing then resetting an axis, the snapshot returns to the exact defaults — this
     * is what the editor's "Reset to default" button promises the user.
     */
    @Test
    fun resetSnapshot_returnsToDefaults() = runBlocking {
        val repo = InMemoryTabPrefRepository()
        val receptiveDefault = "RECEPTIVE_BUNDLED"

        repo.write(TabPref(SupportAxis.RECEPTIVE, "Custom Label", "Custom Prompt"))
        val edited = repo.read(SupportAxis.RECEPTIVE, receptiveDefault)
        assertEquals("Custom Label", edited.label)
        assertEquals("Custom Prompt", edited.promptTemplate)

        repo.resetToDefault(SupportAxis.RECEPTIVE)
        val after = repo.read(SupportAxis.RECEPTIVE, receptiveDefault)
        assertEquals("Clean for me", after.label)
        assertEquals(receptiveDefault, after.promptTemplate)
    }

    /**
     * Slot index ordering is locked. Slot 0 is left, slot 1 is right; renames don't change
     * position. This is what makes "Tab 1 / Tab 2" muscle memory survive label edits.
     */
    @Test
    fun supportAxis_slotOrderingIsLocked() {
        assertEquals(0, SupportAxis.RECEPTIVE.slotIndex)
        assertEquals(1, SupportAxis.EXPRESSIVE.slotIndex)
        assertEquals(SupportAxis.RECEPTIVE, SupportAxis.fromSlotIndex(0))
        assertEquals(SupportAxis.EXPRESSIVE, SupportAxis.fromSlotIndex(1))
        assertEquals(2, SupportAxis.ALL.size)
        assertNotNull(SupportAxis.fromSlotIndex(0))
        assertNotNull(SupportAxis.fromSlotIndex(1))
        assertTrue(SupportAxis.fromSlotIndex(2) == null)
    }
}
