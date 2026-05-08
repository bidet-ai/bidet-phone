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

package com.google.ai.edge.gallery.bidet.transcript

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Drives [DedupAlgorithm.mergeWithDedup] against the 5 paired fixtures in tests/dedup_fixtures/.
 *
 * Fixture format:
 * ```
 * { "name": "...", "description": "...", "prev": "...", "next": "...", "expected": "..." }
 * ```
 *
 * The runner walks the repo's `tests/dedup_fixtures/` directory and asserts each fixture's
 * `expected` against the algorithm's actual output. We deliberately load from disk so that
 * the same JSON files are referenced by the algorithm's documentation, the brief, and the
 * test — single source of truth.
 */
class DedupAlgorithmTest {

    @Test
    fun fixture_01_clean_overlap_dropsRepeatedSentence() = runFixture("01_clean_overlap.json")

    @Test
    fun fixture_02_fuzzy_asr_variance_acceptsLowEditDistance() = runFixture("02_fuzzy_asr_variance.json")

    @Test
    fun fixture_03_no_overlap_concatenatesWithSpace() = runFixture("03_no_overlap.json")

    @Test
    fun fixture_04_all_overlap_collapsesToPrev() = runFixture("04_all_overlap.json")

    @Test
    fun fixture_05_partial_mid_sentence_cutsAtTokenBoundary() = runFixture("05_partial_mid_sentence.json")

    @Test
    fun emptyPrev_returnsNext() {
        assertEquals("hello", DedupAlgorithm.mergeWithDedup("", "hello"))
    }

    @Test
    fun emptyNext_returnsPrev() {
        assertEquals("hello", DedupAlgorithm.mergeWithDedup("hello", ""))
    }

    @Test
    fun shortMatchBelowThreshold_isTreatedAsNoOverlap() {
        // "the" alone is well under MIN_MATCH_LEN (10) — must not be treated as overlap.
        val merged = DedupAlgorithm.mergeWithDedup("apple the", "the orange")
        assertEquals("apple the the orange", merged)
    }

    @Test
    fun levenshtein_basics() {
        assertEquals(0, DedupAlgorithm.levenshtein("abc", "abc"))
        assertEquals(3, DedupAlgorithm.levenshtein("abc", "xyz"))
        assertEquals(1, DedupAlgorithm.levenshtein("abc", "abcd"))
        assertEquals(1, DedupAlgorithm.levenshtein("kitten", "kittn"))
    }

    @Test
    fun mapStrippedOffsetToOriginal_preservesWhitespace() {
        // "ab cd" → stripped is "abcd"; offset 3 (after 'c') maps to index 4 in original.
        assertEquals(4, DedupAlgorithm.mapStrippedOffsetToOriginal("ab cd", 3))
        assertEquals(0, DedupAlgorithm.mapStrippedOffsetToOriginal("anything", 0))
    }

    // ---------- helpers ----------

    /**
     * Locates the repo's `tests/dedup_fixtures/` directory at test runtime. Gradle runs unit
     * tests with the working directory at the module root (`Android/src/app/`), so we walk up
     * three levels (`../../..`) to reach the repo root, then descend into `tests/`.
     *
     * If the directory cannot be found, the test fails loudly rather than silently passing.
     */
    private fun fixtureDir(): File {
        val candidates = listOf(
            File("../../../tests/dedup_fixtures"),
            File("../../tests/dedup_fixtures"),
            File("../tests/dedup_fixtures"),
            File("tests/dedup_fixtures"),
        )
        for (c in candidates) if (c.isDirectory) return c.absoluteFile
        throw IllegalStateException(
            "Could not locate tests/dedup_fixtures from working dir " +
                File(".").absolutePath
        )
    }

    private fun runFixture(filename: String) {
        val file = File(fixtureDir(), filename)
        require(file.isFile) { "Fixture not found: $file" }
        val json = JSONObject(file.readText())
        val prev = json.getString("prev")
        val next = json.getString("next")
        val expected = json.getString("expected")
        val actual = DedupAlgorithm.mergeWithDedup(prev, next)
        assertEquals(
            "Fixture ${json.optString("name", filename)} mismatched. " +
                "description=${json.optString("description", "")}",
            expected,
            actual,
        )
    }
}
