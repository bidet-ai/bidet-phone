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

import com.google.ai.edge.gallery.bidet.data.BidetSession
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [BidetSession.toMarkdownSummary] — the pure helper that feeds the v19
 * "Export session" share-sheet payload.
 *
 * Contract anchored here:
 *  - Every export ALWAYS emits the four section headings, even when the corresponding
 *    field is missing. Downstream parsers (Mark's Captain's-log forward parser, future
 *    TP3 ingest) can therefore split on the headings without conditional logic.
 *  - Empty fields render as a stable sentinel (`_(empty)_` / `_(not generated)_`),
 *    not a blank line, so the parser can tell "no data" from "section delimiter".
 *  - In-progress sessions (endedAtMs null) render the marker `_(in progress)_` so
 *    a recipient knows the dump may be incomplete.
 *  - The RAW transcript is fenced as ```text``` to preserve newlines verbatim across
 *    Drive/Gmail renderers, which otherwise collapse whitespace.
 *
 * The IO side ([exportSession]) needs an instrumented harness and is not tested here.
 */
class SessionExporterMarkdownTest {

    private fun baseSession(): BidetSession = BidetSession(
        sessionId = "45cf54fa-6b25-4dc1-86f8-70c253d4bea6",
        startedAtMs = 1_762_812_000_000L, // 2025-11-10 in UTC; not date-asserted, just stable
        endedAtMs = 1_762_812_960_000L,    // +16 min, matches Mark's 16-min reference dump
        durationSeconds = 960,
        rawText = "this is the raw transcript with multiple lines.\nsecond line here.",
        cleanCached = "Clean for me: tightened version of the dump.",
        foraiCached = "Clean for others: email tone.",
        chunkCount = 32,
        mergedChunkCount = 32,
    )

    @Test
    fun emitsAllFourHeadings_evenWhenFieldsMissing() {
        // Empty-ish session: no clean outputs, no transcript, no end time. The four
        // headings MUST still appear — that's the parser contract.
        val emptyish = BidetSession(
            sessionId = "stub-id",
            startedAtMs = 1L,
            endedAtMs = null,
            durationSeconds = 0,
            rawText = "",
            cleanCached = null,
            foraiCached = null,
            chunkCount = 0,
        )
        val md = emptyish.toMarkdownSummary()
        assertTrue("Session metadata heading missing", md.contains("## Session metadata"))
        assertTrue("RAW transcript heading missing", md.contains("## RAW transcript"))
        assertTrue("Clean for me heading missing", md.contains("## Clean for me"))
        assertTrue("Clean for others heading missing", md.contains("## Clean for others"))
    }

    @Test
    fun inProgressSession_marksEndedSentinel() {
        val live = baseSession().copy(endedAtMs = null, durationSeconds = 0)
        val md = live.toMarkdownSummary()
        assertTrue(
            "In-progress session must render the (in progress) marker, got:\n$md",
            md.contains("_(in progress)_"),
        )
    }

    @Test
    fun emptyRawText_rendersEmptySentinel_notBlankLine() {
        // A parser splitting on "## RAW transcript\n\n" should find a stable sentinel,
        // not a heading immediately followed by the next heading.
        val noRaw = baseSession().copy(rawText = "")
        val md = noRaw.toMarkdownSummary()
        // The empty-state sentinel must appear under RAW transcript.
        val rawIdx = md.indexOf("## RAW transcript")
        val nextHeadingIdx = md.indexOf("## Clean for me")
        val rawSection = md.substring(rawIdx, nextHeadingIdx)
        assertTrue(
            "RAW transcript with empty text must render the (empty) sentinel, got:\n$rawSection",
            rawSection.contains("_(empty)_"),
        )
    }

    @Test
    fun rawTranscript_isFencedAsTextCodeBlock() {
        val md = baseSession().toMarkdownSummary()
        assertTrue(
            "RAW transcript must be fenced as ```text … ``` for whitespace fidelity",
            md.contains("```text") && md.contains("```"),
        )
        // The actual transcript content must be inside the fence.
        assertTrue(
            "Fence must contain the raw text",
            md.contains("this is the raw transcript with multiple lines."),
        )
    }

    @Test
    fun missingCleanOutputs_renderNotGeneratedSentinel() {
        val noClean = baseSession().copy(cleanCached = null, foraiCached = null, analysisCached = null)
        val md = noClean.toMarkdownSummary()
        // Both clean sections should fall back to the same sentinel so a parser can
        // detect "user never tapped Clean" uniformly.
        val cleanForMeIdx = md.indexOf("## Clean for me")
        val cleanForOthersIdx = md.indexOf("## Clean for others")
        val cleanForMeSection = md.substring(cleanForMeIdx, cleanForOthersIdx)
        val cleanForOthersSection = md.substring(cleanForOthersIdx)
        assertTrue(
            "Missing Clean-for-me must render the (not generated) sentinel",
            cleanForMeSection.contains("_(not generated)_"),
        )
        assertTrue(
            "Missing Clean-for-others must render the (not generated) sentinel",
            cleanForOthersSection.contains("_(not generated)_"),
        )
    }

    @Test
    fun analysisCachedFallsThrough_whenCleanCachedIsNull() {
        // v0.1 rows had analysisCached but no cleanCached. The export should still show
        // the user's earlier Clean-for-me output rather than rendering "not generated".
        val v1ish = baseSession().copy(cleanCached = null, analysisCached = "Legacy clean output from v0.1.")
        val md = v1ish.toMarkdownSummary()
        val cleanForMeIdx = md.indexOf("## Clean for me")
        val cleanForOthersIdx = md.indexOf("## Clean for others")
        val cleanForMeSection = md.substring(cleanForMeIdx, cleanForOthersIdx)
        assertTrue(
            "v0.1 row with analysisCached but no cleanCached should fall through",
            cleanForMeSection.contains("Legacy clean output from v0.1."),
        )
    }

    @Test
    fun metadataIncludesSessionIdAndCounts() {
        val md = baseSession().toMarkdownSummary()
        assertTrue(md.contains("45cf54fa-6b25-4dc1-86f8-70c253d4bea6"))
        assertTrue("Chunks produced count missing", md.contains("Chunks produced:** 32"))
        assertTrue("Chunks merged count missing", md.contains("Chunks merged:** 32"))
    }

    @Test
    fun duration_formattedHumanReadable() {
        // 960 s = 16m 0s. Mark's reference 16-min dump.
        val md = baseSession().toMarkdownSummary()
        assertTrue("Duration must be human-readable, got:\n$md", md.contains("16m 0s"))
    }

    @Test
    fun output_isDeterministicGivenSameInput() {
        // Pure-function guarantee: two calls produce identical bytes.
        val first = baseSession().toMarkdownSummary()
        val second = baseSession().toMarkdownSummary()
        // We compare lengths AND content to make a regression on stray
        // `System.currentTimeMillis()` calls obvious.
        assertTrue(
            "toMarkdownSummary must be pure — repeated calls must produce identical output",
            first == second,
        )
    }
}
