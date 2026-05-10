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

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the fidelity-first rules into the default Clean-tab prompts.
 *
 * Background: 2026-05-09 — Mark tested Bidet on a 31-min brain-dump and Gemma 4 E4B
 * (4.5B params, on-device) hallucinated proper nouns ("Hasspin", "Zenabria") that were
 * never in the RAW transcript. Root cause: the v1 prompts rewarded fluency too much.
 * The v2 rewrite leans the model toward "preserve every word, especially names" and
 * "don't invent." These tests prevent silent regression of those rules.
 *
 * Pure-string assertions only — no Compose, no Android framework, no flaky timing.
 * Asset files are read directly off the filesystem the same way [DedupAlgorithmTest]
 * locates its fixture dir.
 */
class CleanPromptFidelityTest {

    @Test
    fun expressiveDefault_containsFidelityRules() {
        val body = readAsset("expressive_default.txt")

        assertTrue(
            "Expressive (Clean for others) prompt MUST require verbatim preservation of " +
                "proper nouns. Removing this rule reopens the 'Hasspin/Zenabria' " +
                "hallucination class. Body was:\n$body",
            body.contains("Preserve every proper noun"),
        )
        assertTrue(
            "Expressive prompt MUST forbid invention. Body was:\n$body",
            body.contains("Do not invent words"),
        )
        assertTrue(
            "Expressive prompt MUST tell the model to keep ambiguous phrasing intact " +
                "instead of 'fixing' it. Body was:\n$body",
            body.contains(
                "If part of the transcript is unclear, ambiguous, or sounds like a typo, " +
                    "KEEP THE ORIGINAL PHRASING"
            ),
        )
    }

    @Test
    fun receptiveDefault_containsFidelityRules() {
        val body = readAsset("receptive_default.txt")

        assertTrue(
            "Receptive (Clean for me) prompt MUST require verbatim preservation of " +
                "proper nouns. Body was:\n$body",
            body.contains("Preserve every proper noun"),
        )
        assertTrue(
            "Receptive prompt MUST forbid invention. Body was:\n$body",
            body.contains("Do not invent words"),
        )
    }

    @Test
    fun defaultTemperature_isLowEnoughToSuppressInvention() {
        // Lower temperature is the second half of the fidelity fix: it reduces the
        // model's tendency to sample plausible-sounding-but-wrong tokens. Pinning a
        // ceiling here prevents future tuning from quietly raising it back to where
        // the hallucinations started.
        assertTrue(
            "DEFAULT_TEMPERATURE must stay <= 0.3f to keep Clean-tab output deterministic " +
                "enough to suppress invented proper nouns. Was: " +
                "${BidetTabsViewModel.DEFAULT_TEMPERATURE}",
            BidetTabsViewModel.DEFAULT_TEMPERATURE <= 0.3f,
        )
    }

    /**
     * Locate `Android/src/app/src/main/assets/prompts/<name>` from whichever working dir
     * the JVM test runner picks. Mirrors [DedupAlgorithmTest.fixtureDir].
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
        throw IllegalStateException(
            "Could not locate prompts/$name from working dir " + File(".").absolutePath
        )
    }
}
