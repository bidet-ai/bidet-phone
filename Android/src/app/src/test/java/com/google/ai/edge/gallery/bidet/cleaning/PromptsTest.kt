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

package com.google.ai.edge.gallery.bidet.cleaning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the v20 Clean-for-judges contest-pitch prompt so a future edit can't silently drop
 * the tagline, the on-device claim, the Pixel 8 Pro / Tensor G3 hardware mention, or the
 * Gemma 4 E4B model name — each of those is load-bearing for the Kaggle Gemma 4 Good
 * Hackathon writeup (deadline 2026-05-18).
 *
 * Three layers covered:
 *  1. The Kotlin string constant [Prompts.CLEAN_FOR_JUDGES_PROMPT] is non-empty and contains
 *     every must-have phrase the contest-writeup brief specifies.
 *  2. The bundled asset `assets/prompts/judges_default.txt` (used as the fall-through default
 *     when the user hasn't edited the tab's prompt) exists and is a verbatim mirror of the
 *     Kotlin constant. The duplication is intentional — the asset path is what
 *     [com.google.ai.edge.gallery.bidet.ui.TabPref.defaultPromptAssetPath] returns for JUDGES,
 *     and the Kotlin constant is the off-repo source-of-truth ([Prompts] Kdoc explains why).
 *  3. The {transcript} placeholder is present so the LiteRT-LM substitution path injects the
 *     RAW dump at the same site as the other two axes.
 *
 * Pure-string assertions only — no Android framework dependency.
 */
class PromptsTest {

    @Test
    fun cleanForJudgesPrompt_isNonEmpty() {
        val p = Prompts.CLEAN_FOR_JUDGES_PROMPT
        assertTrue(
            "CLEAN_FOR_JUDGES_PROMPT must be non-empty — it's the system prompt shipped for " +
                "the v20 Clean-for-judges contest-pitch tab.",
            p.isNotBlank(),
        )
    }

    /**
     * The product tagline is the ONE phrase Mark wants every judge to hear, see, and repeat.
     * The Glossary.kt tagline-pin enforces "Bidet AI" (not bare "AI") at the per-cleaning-call
     * substitution layer; the prompt body asks the model to drop the tagline twice (after the
     * opening hook and at the close). Either drift — removing the literal tagline or stripping
     * the "Bidet" word — undoes the contest pitch.
     */
    @Test
    fun cleanForJudgesPrompt_pinsTheTaglineVerbatim() {
        val p = Prompts.CLEAN_FOR_JUDGES_PROMPT
        assertTrue(
            "CLEAN_FOR_JUDGES_PROMPT must direct Gemma to drop the exact tagline 'Take a " +
                "brain dump. Bidet AI cleans up your mess.' — body was:\n$p",
            p.contains("Take a brain dump. Bidet AI cleans up your mess."),
        )
    }

    /**
     * The "100% on-device" claim is the hardware achievement judges score on. Vague "runs
     * locally" doesn't convey that there's no cloud round-trip at any point.
     */
    @Test
    fun cleanForJudgesPrompt_pinsTheOnDeviceClaim() {
        val p = Prompts.CLEAN_FOR_JUDGES_PROMPT
        assertTrue(
            "CLEAN_FOR_JUDGES_PROMPT must require the literal phrase '100% on-device' — judges " +
                "score on the on-device claim and vague paraphrases ('runs locally') don't " +
                "communicate the no-cloud guarantee. Body was:\n$p",
            p.contains("100% on-device"),
        )
    }

    /**
     * The specific hardware target — "Pixel 8 Pro" + "Tensor G3" — is what makes the
     * on-device claim verifiable. Judges who care about the achievement want to know which
     * SoC was on the bench, not "a phone".
     */
    @Test
    fun cleanForJudgesPrompt_pinsPixel8ProAndTensorG3() {
        val p = Prompts.CLEAN_FOR_JUDGES_PROMPT
        assertTrue(
            "CLEAN_FOR_JUDGES_PROMPT must name 'Pixel 8 Pro' — the specific hardware target " +
                "that makes the on-device claim verifiable. Body was:\n$p",
            p.contains("Pixel 8 Pro"),
        )
        assertTrue(
            "CLEAN_FOR_JUDGES_PROMPT must name the 'Tensor G3' SoC — judges score on the " +
                "hardware specificity. Body was:\n$p",
            p.contains("Tensor G3"),
        )
    }

    /**
     * "Gemma 4 E4B" is the contest's hero model name. Saying "Gemma" or "the model" loses
     * the contest-relevance signal.
     */
    @Test
    fun cleanForJudgesPrompt_pinsGemma4E4bModelName() {
        val p = Prompts.CLEAN_FOR_JUDGES_PROMPT
        assertTrue(
            "CLEAN_FOR_JUDGES_PROMPT must spell out 'Gemma 4 E4B' verbatim — it's the " +
                "contest's hero model name and the writeup needs to demonstrate Mark used the " +
                "intended model. Body was:\n$p",
            p.contains("Gemma 4 E4B"),
        )
    }

    /**
     * The transcript injection contract: the prompt MUST contain the `{transcript}` placeholder
     * so the LiteRT-LM substitution path inserts the RAW dump the same way it does for the
     * other two axes ([com.google.ai.edge.gallery.bidet.cleaning.RawChunker] + the per-axis
     * default prompts both use this token). Without it the model has no transcript to clean.
     */
    @Test
    fun cleanForJudgesPrompt_containsTranscriptPlaceholder() {
        val p = Prompts.CLEAN_FOR_JUDGES_PROMPT
        assertTrue(
            "CLEAN_FOR_JUDGES_PROMPT must use the '{transcript}' placeholder so the runtime " +
                "substitution path lands the RAW dump in the prompt body. Body was:\n$p",
            p.contains("{transcript}"),
        )
    }

    /**
     * The bundled-asset fall-through. [com.google.ai.edge.gallery.bidet.ui.TabPref.defaultPromptAssetPath]
     * returns `prompts/judges_default.txt` for the JUDGES axis. That asset must exist and be a
     * verbatim mirror of [Prompts.CLEAN_FOR_JUDGES_PROMPT] — the Kotlin constant is the source
     * of truth and the asset is what the runtime asset-loader reads.
     */
    @Test
    fun judgesDefaultAsset_mirrorsKotlinConstantVerbatim() {
        val asset = readAsset("judges_default.txt")
        assertTrue(
            "judges_default.txt must be non-empty — it ships as the fall-through default for " +
                "the Clean-for-judges tab.",
            asset.isNotBlank(),
        )
        assertEquals(
            "Bundled asset 'prompts/judges_default.txt' must be byte-identical to " +
                "Prompts.CLEAN_FOR_JUDGES_PROMPT. The Kotlin constant is the source of " +
                "truth; if you edited one, edit the other.",
            Prompts.CLEAN_FOR_JUDGES_PROMPT,
            asset,
        )
    }

    /**
     * The contest brief itself — written in the prompt body — references the Kaggle Gemma 4
     * Good Hackathon. This pins that we're talking about the right contest, not a generic
     * Kaggle competition.
     */
    @Test
    fun cleanForJudgesPrompt_namesTheKaggleGemma4GoodHackathon() {
        val p = Prompts.CLEAN_FOR_JUDGES_PROMPT
        assertTrue(
            "CLEAN_FOR_JUDGES_PROMPT must name the 'Kaggle Gemma 4 Good Hackathon' — the " +
                "specific contest this tab targets. Body was:\n$p",
            p.contains("Kaggle Gemma 4 Good"),
        )
    }

    /**
     * Locate `Android/src/app/src/main/assets/prompts/<name>` from whichever working dir the
     * JVM test runner picks. Mirrors [com.google.ai.edge.gallery.bidet.ui.CleanPromptFidelityTest.readAsset].
     */
    private fun readAsset(name: String): String {
        val rel = "src/main/assets/prompts/$name"
        val candidates = listOf(
            File(rel),
            File("../$rel"),
            File("../../$rel"),
            File("../../../$rel"),
            File("Android/src/app/$rel"),
            File("../Android/src/app/$rel"),
            File("../../Android/src/app/$rel"),
        )
        for (c in candidates) if (c.isFile) return c.readText()
        assertNotNull(
            "Could not locate prompts/$name from working dir " + File(".").absolutePath,
            null,
        )
        error("unreachable")
    }
}
