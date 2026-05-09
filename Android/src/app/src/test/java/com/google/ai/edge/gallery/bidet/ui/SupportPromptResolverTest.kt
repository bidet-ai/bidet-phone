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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2 unit tests for [SupportPromptResolver]. Pure-Kotlin (no Android Context, DataStore, or
 * Hilt) so they run under `./gradlew :app:test`. Brief: "preset → custom → preset persists
 * across recomposition".
 *
 * Replaces the v0.1 Tab4PromptResolverTest. The resolver moved from "Tab 4 only, returns
 * null to mean fallthrough" to "either Receptive or Expressive axis, always returns a non-null
 * prompt body" — the test surface follows that contract change.
 */
class SupportPromptResolverTest {

    /** Stub asset loader: returns "ASSET<path>" so the test can verify which path was hit. */
    private val stubLoader: (String) -> String = { path -> "ASSET<$path>" }

    @Test
    fun receptive_default_loadsSummaryAsset() {
        val result = SupportPromptResolver.resolve(
            axis = SupportAxis.RECEPTIVE,
            activePresetId = SupportPreset.RECEPTIVE_SUMMARY.id,
            customPrompt = "ignored",
            loadAsset = stubLoader,
        )
        assertEquals(
            "RECEPTIVE_SUMMARY must load prompts/receptive_default.txt",
            "ASSET<prompts/receptive_default.txt>",
            result,
        )
    }

    @Test
    fun receptive_dyslexic_loadsDyslexiaAsset() {
        val result = SupportPromptResolver.resolve(
            axis = SupportAxis.RECEPTIVE,
            activePresetId = SupportPreset.RECEPTIVE_DYSLEXIC.id,
            customPrompt = "",
            loadAsset = stubLoader,
        )
        assertEquals("ASSET<prompts/dyslexic.txt>", result)
    }

    @Test
    fun receptive_dysgraphic_loadsDysgraphiaAsset() {
        val result = SupportPromptResolver.resolve(
            axis = SupportAxis.RECEPTIVE,
            activePresetId = SupportPreset.RECEPTIVE_DYSGRAPHIC.id,
            customPrompt = "",
            loadAsset = stubLoader,
        )
        assertEquals("ASSET<prompts/dysgraphic.txt>", result)
    }

    @Test
    fun receptive_tangentOrganizer_loadsTangentAsset() {
        // Asset filename uses the clinical token (banWordCheck exempts assets/prompts/);
        // user-visible label is "Tangent organizer". The resolver only deals with asset
        // paths so this is a fair fixture.
        val result = SupportPromptResolver.resolve(
            axis = SupportAxis.RECEPTIVE,
            activePresetId = SupportPreset.RECEPTIVE_TANGENT_ORGANIZER.id,
            customPrompt = "",
            loadAsset = stubLoader,
        )
        assertEquals("ASSET<prompts/adhd_organizer.txt>", result)
    }

    @Test
    fun receptive_plainLanguage_loadsPlainLanguageAsset() {
        val result = SupportPromptResolver.resolve(
            axis = SupportAxis.RECEPTIVE,
            activePresetId = SupportPreset.RECEPTIVE_PLAIN_LANGUAGE.id,
            customPrompt = "",
            loadAsset = stubLoader,
        )
        assertEquals("ASSET<prompts/plain_language.txt>", result)
    }

    @Test
    fun receptive_custom_returnsUserPrompt() {
        val custom = "Reformat this for a 7th-grade reader. Use only short sentences."
        val result = SupportPromptResolver.resolve(
            axis = SupportAxis.RECEPTIVE,
            activePresetId = SupportPreset.RECEPTIVE_CUSTOM.id,
            customPrompt = custom,
            loadAsset = stubLoader,
        )
        assertEquals(custom, result)
    }

    @Test
    fun receptive_custom_blankPrompt_fallsBackToDefault() {
        // Defensive: never fire inference with an empty system prompt. Falls back to the
        // axis default rather than failing or producing garbage.
        val result = SupportPromptResolver.resolve(
            axis = SupportAxis.RECEPTIVE,
            activePresetId = SupportPreset.RECEPTIVE_CUSTOM.id,
            customPrompt = "   \n\t",
            loadAsset = stubLoader,
        )
        assertEquals("ASSET<prompts/receptive_default.txt>", result)
    }

    @Test
    fun expressive_default_loadsAiIngestAsset() {
        val result = SupportPromptResolver.resolve(
            axis = SupportAxis.EXPRESSIVE,
            activePresetId = SupportPreset.EXPRESSIVE_AI_INGEST.id,
            customPrompt = "",
            loadAsset = stubLoader,
        )
        assertEquals("ASSET<prompts/expressive_default.txt>", result)
    }

    @Test
    fun expressive_emailDraft_loadsEmailDraftAsset() {
        val result = SupportPromptResolver.resolve(
            axis = SupportAxis.EXPRESSIVE,
            activePresetId = SupportPreset.EXPRESSIVE_EMAIL_DRAFT.id,
            customPrompt = "",
            loadAsset = stubLoader,
        )
        assertEquals("ASSET<prompts/email_draft.txt>", result)
    }

    @Test
    fun expressive_aiAgent_loadsForaiAsset() {
        val result = SupportPromptResolver.resolve(
            axis = SupportAxis.EXPRESSIVE,
            activePresetId = SupportPreset.EXPRESSIVE_AI_AGENT.id,
            customPrompt = "",
            loadAsset = stubLoader,
        )
        assertEquals("ASSET<prompts/forai.txt>", result)
    }

    @Test
    fun expressive_custom_returnsUserPrompt() {
        val custom = "Rewrite as a 1-paragraph status update suitable for a Slack channel."
        val result = SupportPromptResolver.resolve(
            axis = SupportAxis.EXPRESSIVE,
            activePresetId = SupportPreset.EXPRESSIVE_CUSTOM.id,
            customPrompt = custom,
            loadAsset = stubLoader,
        )
        assertEquals(custom, result)
    }

    @Test
    fun unknownPresetId_fallsBackToAxisDefault() {
        // If a future build removes a preset that was previously persisted, byId returns null
        // and we fall through to the axis default rather than NPE-ing.
        val result = SupportPromptResolver.resolve(
            axis = SupportAxis.RECEPTIVE,
            activePresetId = "future_unknown_preset",
            customPrompt = "ignored",
            loadAsset = stubLoader,
        )
        assertEquals("ASSET<prompts/receptive_default.txt>", result)
    }

    @Test
    fun crossAxisPresetId_fallsBackToAxisDefault() {
        // Defensive: a Receptive preset id used on the Expressive tab (or vice versa) falls
        // back to the requested axis's default, not the wrong-axis preset.
        val result = SupportPromptResolver.resolve(
            axis = SupportAxis.EXPRESSIVE,
            activePresetId = SupportPreset.RECEPTIVE_DYSLEXIC.id, // wrong axis
            customPrompt = "",
            loadAsset = stubLoader,
        )
        assertEquals(
            "Cross-axis preset id must NOT load the wrong-axis asset",
            "ASSET<prompts/expressive_default.txt>",
            result,
        )
    }

    @Test
    fun resolveIsDeterministic_simulatesRecomposition() {
        // Compose recompositions re-read state from the ViewModel; the resolver is the layer
        // that turns those reads into a system-prompt body. Resolver is stateless, so the
        // same input must produce the same output every time. This is the structural form of
        // "preset → custom → preset persists across recomposition".
        val customPrompt = "Tailor for a med-student preceptor — output as SOAP."

        val a1 = SupportPromptResolver.resolve(
            SupportAxis.RECEPTIVE, SupportPreset.RECEPTIVE_DYSLEXIC.id, customPrompt, stubLoader
        )
        val b1 = SupportPromptResolver.resolve(
            SupportAxis.RECEPTIVE, SupportPreset.RECEPTIVE_CUSTOM.id, customPrompt, stubLoader
        )
        val a2 = SupportPromptResolver.resolve(
            SupportAxis.RECEPTIVE, SupportPreset.RECEPTIVE_DYSLEXIC.id, customPrompt, stubLoader
        )
        val b2 = SupportPromptResolver.resolve(
            SupportAxis.RECEPTIVE, SupportPreset.RECEPTIVE_CUSTOM.id, customPrompt, stubLoader
        )

        assertEquals("DYSLEXIC must resolve identically each time", a1, a2)
        assertEquals("CUSTOM must resolve identically each time", b1, b2)
        assertTrue(
            "DYSLEXIC and CUSTOM must produce DIFFERENT prompt bodies — otherwise the cache " +
                "key would collide and switching presets would show stale output",
            a1 != b1,
        )
    }

    @Test
    fun byId_isReflexiveForEveryPreset() {
        // Every enum value must round-trip through byId. Catches regressions where a new
        // preset is added but the persistence path forgets to handle it.
        for (preset in SupportPreset.values()) {
            assertEquals(
                "SupportPreset.byId must be reflexive for ${preset.id}",
                preset,
                SupportPreset.byId(preset.id),
            )
        }
    }

    @Test
    fun receptive_and_expressive_listsArePartitioned() {
        // Sanity: every preset belongs to exactly one axis, and the two axis lists together
        // cover every value. This guarantees the chip rows don't double-show or drop a chip.
        val all = SupportPreset.values().toList()
        val combined = SupportPreset.RECEPTIVE + SupportPreset.EXPRESSIVE
        assertEquals(all.size, combined.size)
        assertEquals(all.toSet(), combined.toSet())
        assertTrue(
            SupportPreset.RECEPTIVE.intersect(SupportPreset.EXPRESSIVE.toSet()).isEmpty()
        )
    }
}
