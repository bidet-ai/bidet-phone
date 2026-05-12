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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the v18.8 project-noun glossary + tagline pin so future prompt edits can't silently
 * drop the canonicalization context that fixes Mark's mishears.
 *
 * Mishear evidence (2026-05-11 brain-dump on Pixel 8 Pro, Tensor G3 CPU, Gemma 4 E4B):
 *   "Bidet AI"         → "the day AI"
 *   "Gemma"            → "GINA"
 *   "Unsloth"          → "ensloth"
 *   "Kaggle hackathon" → "Caleb Jimiford"
 *
 * Pure-string assertions — no Android framework dependency.
 */
class GlossaryTest {

    @Test
    fun glossary_containsProjectNouns() {
        val g = Glossary.BIDET_GLOSSARY
        assertTrue("glossary missing 'Bidet AI':\n$g", g.contains("Bidet AI"))
        assertTrue("glossary missing 'Gemma 4':\n$g", g.contains("Gemma 4"))
        assertTrue("glossary missing 'Unsloth':\n$g", g.contains("Unsloth"))
        assertTrue("glossary missing 'Kaggle hackathon':\n$g", g.contains("Kaggle hackathon"))
    }

    @Test
    fun glossary_containsMishearsForCanonicalization() {
        // The mishears column is load-bearing — without the literal surface form Moonshine
        // produces, Gemma can't reliably map back to the canonical spelling.
        val g = Glossary.BIDET_GLOSSARY
        assertTrue("glossary missing mishear 'the day AI':\n$g", g.contains("the day AI"))
        assertTrue("glossary missing mishear 'GINA':\n$g", g.contains("GINA"))
        assertTrue("glossary missing mishear 'ensloth':\n$g", g.contains("ensloth"))
        assertTrue("glossary missing mishear 'Caleb Jimiford':\n$g", g.contains("Caleb Jimiford"))
    }

    @Test
    fun glossary_pinsExactTagline() {
        val g = Glossary.BIDET_GLOSSARY
        // Mark spelled this out verbatim 2026-05-11. The tagline MUST include the words
        // "Bidet AI" — never just "AI cleans up your mess".
        assertTrue(
            "glossary must pin the exact tagline 'Take a brain dump. Bidet AI cleans up " +
                "your mess.' — body was:\n$g",
            g.contains("Take a brain dump. Bidet AI cleans"),
        )
        assertTrue(
            "glossary must spell out 'cleans up your mess' explicitly:\n$g",
            g.contains("cleans up your mess"),
        )
        assertTrue(
            "glossary must forbid stripping 'Bidet' from the tagline:\n$g",
            g.contains("Never strip the word \"Bidet\" from \"Bidet AI"),
        )
    }

    @Test
    fun withGlossary_prependsToBasePrompt() {
        val base = "You are reorganizing a verbatim spoken transcript. Rules: ..."
        val wrapped = Glossary.withGlossary(base)

        // v18.9 (2026-05-11): the preamble now leads with imperative substitution
        // rules so Gemma 4 E4B int4 treats the glossary as a table to APPLY rather
        // than context to consider. The PROJECT VOCABULARY block follows.
        assertTrue(
            "wrapped prompt should start with the mandatory-rules header:\n$wrapped",
            wrapped.startsWith("MANDATORY SUBSTITUTION RULES"),
        )
        assertTrue(
            "wrapped prompt should still contain the PROJECT VOCABULARY block:\n$wrapped",
            wrapped.contains("PROJECT VOCABULARY"),
        )
        assertTrue(
            "wrapped prompt should still contain the base body:\n$wrapped",
            wrapped.contains(base),
        )
        assertTrue("wrapped prompt should be non-empty", wrapped.isNotBlank())
        assertNotEquals("wrapped prompt must differ from base", base, wrapped)
    }

    @Test
    fun withGlossary_isIdempotent() {
        // Calling twice must not double the preamble — a future caller could compose calls
        // without realizing both already wrap; idempotency guards against that.
        val base = "Do something useful."
        val once = Glossary.withGlossary(base)
        val twice = Glossary.withGlossary(once)
        assertEquals("withGlossary must be idempotent", once, twice)
    }
}
