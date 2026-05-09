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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * v0.2 Tab 4 customizable-prompt feature — pure-Kotlin tests for the prompt-selection state
 * machine. Brief: "at least one unit test on the prompt-selection state machine (preset →
 * custom → preset persists across recomposition)".
 *
 * The full ViewModel touches Android Context + DataStore + Hilt, so it can't run in plain
 * JUnit. [Tab4PromptResolver] is the pure-Kotlin extract and that's what we cover here. The
 * "persists across recomposition" property is structural — the resolver is stateless and
 * computes the same answer for the same (presetId, customPrompt) inputs, so a recomposition
 * of the host that re-reads DataStore values will get the same prompt body. We assert that
 * determinism explicitly in [resolveIsDeterministic_simulatesRecomposition].
 */
class Tab4PromptResolverTest {

    /** Stub asset loader: returns "ASSET<path>" so the test can verify which path was hit. */
    private val stubLoader: (String) -> String = { path -> "ASSET<$path>" }

    /** Asserting loader: any call fails the test. Used to prove FORAI / CUSTOM don't load assets. */
    private val failOnLoad: (String) -> String = { path ->
        fail("loadAsset must NOT be called for this preset; got path=$path")
        throw AssertionError()
    }

    @Test
    fun forai_returnsNull_signalingFallthroughToV01Path() {
        // FORAI preset means "use whatever the v0.1 codepath would have used". The resolver
        // signals this by returning null so BidetTabsViewModel.loadPromptText proceeds to
        // its existing debug-override → bundled-asset chain.
        val result = Tab4PromptResolver.resolve(
            activePresetId = Tab4Preset.FORAI.id,
            customPrompt = "any text",
            loadAsset = failOnLoad,
        )
        assertNull("FORAI preset must signal fallthrough by returning null", result)
    }

    @Test
    fun custom_withPopulatedPrompt_returnsCustomBody() {
        val custom = "Reformat this for a 7th-grade reader. Use only short sentences."
        val result = Tab4PromptResolver.resolve(
            activePresetId = Tab4Preset.CUSTOM.id,
            customPrompt = custom,
            loadAsset = failOnLoad,
        )
        assertEquals(
            "CUSTOM preset must return the user's custom prompt body verbatim",
            custom,
            result,
        )
    }

    @Test
    fun custom_withBlankPrompt_fallsThroughToFORAI() {
        // Defensive: if the user somehow saves a blank custom prompt (the BottomSheet's
        // Save button is disabled in this case, but the persistence may have been written
        // by a previous build), we should not fire inference with an empty system prompt.
        // Falling through to FORAI default is safer than failing.
        val result = Tab4PromptResolver.resolve(
            activePresetId = Tab4Preset.CUSTOM.id,
            customPrompt = "   \n\t",
            loadAsset = failOnLoad,
        )
        assertNull("Blank CUSTOM prompt must fall through (return null)", result)
    }

    @Test
    fun dyslexicPreset_loadsDyslexiaAsset() {
        val result = Tab4PromptResolver.resolve(
            activePresetId = Tab4Preset.DYSLEXIC.id,
            customPrompt = "ignored",
            loadAsset = stubLoader,
        )
        assertEquals(
            "DYSLEXIC preset must load prompts/dyslexic.txt",
            "ASSET<prompts/dyslexic.txt>",
            result,
        )
    }

    @Test
    fun dysgraphicPreset_loadsDysgraphiaAsset() {
        val result = Tab4PromptResolver.resolve(
            activePresetId = Tab4Preset.DYSGRAPHIC.id,
            customPrompt = "ignored",
            loadAsset = stubLoader,
        )
        assertEquals("ASSET<prompts/dysgraphic.txt>", result)
    }

    @Test
    fun adhdOrganizerPreset_loadsTangentOrganizerAsset() {
        // The asset filename uses the clinical token (banWordCheck exempts assets/prompts/);
        // the user-visible label is "Tangent organizer". The resolver only deals with asset
        // paths so this is a fair fixture.
        val result = Tab4PromptResolver.resolve(
            activePresetId = Tab4Preset.ADHD_ORGANIZER.id,
            customPrompt = "ignored",
            loadAsset = stubLoader,
        )
        assertEquals("ASSET<prompts/adhd_organizer.txt>", result)
    }

    @Test
    fun plainLanguagePreset_loadsPlainLanguageAsset() {
        val result = Tab4PromptResolver.resolve(
            activePresetId = Tab4Preset.PLAIN_LANGUAGE.id,
            customPrompt = "ignored",
            loadAsset = stubLoader,
        )
        assertEquals("ASSET<prompts/plain_language.txt>", result)
    }

    @Test
    fun unknownPresetId_fallsThroughToFORAI() {
        // If a future build removes a preset that was previously persisted, byId returns
        // null and we must fall through cleanly rather than NPE-ing or firing inference
        // with an empty system prompt.
        val result = Tab4PromptResolver.resolve(
            activePresetId = "future_unknown_preset",
            customPrompt = "doesn't matter",
            loadAsset = failOnLoad,
        )
        assertNull("Unknown preset id must fall through to FORAI default", result)
    }

    @Test
    fun resolveIsDeterministic_simulatesRecomposition() {
        // Compose recompositions re-read state from the ViewModel; the resolver is the layer
        // that turns those reads into a system-prompt body. To prove "preset → custom →
        // preset persists across recomposition", we run the same input through the resolver
        // multiple times and assert the answer is byte-identical each time. (This is the
        // structural form of the behaviour: a stateless function is recomposition-safe.)
        val customPrompt = "Tailor for a med-student preceptor — output as SOAP."

        // Walk the user's flow: pick DYSLEXIC, switch to CUSTOM, switch back to DYSLEXIC.
        val a1 = Tab4PromptResolver.resolve(Tab4Preset.DYSLEXIC.id, customPrompt, stubLoader)
        val b1 = Tab4PromptResolver.resolve(Tab4Preset.CUSTOM.id, customPrompt, stubLoader)
        val a2 = Tab4PromptResolver.resolve(Tab4Preset.DYSLEXIC.id, customPrompt, stubLoader)
        val b2 = Tab4PromptResolver.resolve(Tab4Preset.CUSTOM.id, customPrompt, stubLoader)

        assertNotNull(a1); assertNotNull(b1); assertNotNull(a2); assertNotNull(b2)
        assertEquals("DYSLEXIC must resolve identically each time", a1, a2)
        assertEquals("CUSTOM must resolve identically each time", b1, b2)
        assertTrue(
            "DYSLEXIC and CUSTOM must produce DIFFERENT prompt bodies — otherwise the cache " +
                "key would collide and switching presets would show stale output",
            a1 != b1,
        )
    }

    @Test
    fun allPresetsAreCoveredInById() {
        // Defensive: byId must accept every enum value. Catches regressions where a new
        // preset is added but the persistence path forgets to handle it.
        for (preset in Tab4Preset.values()) {
            assertEquals(
                "Tab4Preset.byId must be reflexive for ${preset.id}",
                preset,
                Tab4Preset.byId(preset.id),
            )
        }
    }
}
