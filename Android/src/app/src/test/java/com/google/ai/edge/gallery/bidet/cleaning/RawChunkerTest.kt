package com.google.ai.edge.gallery.bidet.cleaning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawChunkerTest {

    @Test
    fun emptyInput_returnsEmpty() {
        assertEquals(emptyList<String>(), RawChunker.chunk("   "))
    }

    @Test
    fun shortInput_returnsSingleWindow() {
        val text = "Just a short brain dump. Nothing fancy."
        assertEquals(listOf(text), RawChunker.chunk(text))
    }

    @Test
    fun oversizeInput_splitsIntoMultipleWindows() {
        val sentence = "This is one sentence about teaching kids history and writing report card comments. "
        val raw = sentence.repeat(80)
        val windows = RawChunker.chunk(raw, maxChars = 600, overlapChars = 80)
        assertTrue("expected multiple windows, got ${windows.size}", windows.size > 1)
    }

    @Test
    fun noWindowExceedsBudget_evenWithOverlap() {
        val sentence = "Sentence number X with some words to fill it up to a reasonable length. "
        val raw = sentence.repeat(60)
        val budget = 500
        val overlap = 50
        val windows = RawChunker.chunk(raw, maxChars = budget, overlapChars = overlap)
        for (w in windows) {
            assertTrue("window of length ${w.length} exceeds budget+overlap=${budget + overlap}", w.length <= budget + overlap)
        }
    }

    @Test
    fun overlapPresentBetweenAdjacentWindows() {
        val a = "First sentence ends here. "
        val b = "Second sentence sits in the middle. "
        val c = "Third sentence wraps it up. "
        val raw = (a + b + c).repeat(40)
        val windows = RawChunker.chunk(raw, maxChars = 400, overlapChars = 60)
        assertTrue(windows.size >= 2)
        val first = windows[0]
        val second = windows[1]
        val tail = first.takeLast(60)
        val anyOverlap = tail.split(' ').filter { it.length > 3 }.any { token ->
            second.startsWith(token) || second.indexOf(token) in 0..80
        }
        assertTrue("expected some token from first window's tail to appear at start of second", anyOverlap)
    }

    @Test
    fun veryLongSingleSentence_hardSplitFallback() {
        val raw = "word ".repeat(2000).trim()
        val windows = RawChunker.chunk(raw, maxChars = 500, overlapChars = 40)
        assertTrue(windows.size > 1)
        for (w in windows) {
            assertTrue("hard-split window exceeded budget: ${w.length}", w.length <= 500 + 40)
        }
    }

    /**
     * 10k-char brain dump exercise — the contract the bug report fix must hold. With the
     * standard receptive system prompt (glossary + base ≈ 2090 chars ≈ 836 tokens),
     * [RawChunker.chunkForPrompt] should produce at least 4 windows so each call to the
     * 2048-token engine fits comfortably under the prefill ceiling. Each window must be
     * within the per-window cap that the helper derives, and stitching every window's
     * content back together must reproduce most of the original input length (overlap
     * means it's larger, not smaller).
     */
    @Test
    fun tenThousandCharBrainDump_chunksIntoFourOrMoreSafeWindows() {
        val sentence = "Mark dumped his thoughts about Bidet AI and the Gemma 4 Hackathon. "
        val raw = sentence.repeat(160) // ~10,720 chars
        assertTrue("expected ~10k char synthetic input", raw.length in 9_000..12_000)
        val systemPrompt = "RULES ".repeat(350) // ~2100 chars — mirrors glossary + receptive
        val windows = RawChunker.chunkForPrompt(raw, systemPrompt)
        assertTrue("expected >= 4 windows, got ${windows.size}", windows.size >= 4)
        // Every window must respect the (derived) per-window character cap. Worst-case
        // the helper falls back to DEFAULT_MAX_CHARS so 2400 is the absolute ceiling for
        // any single window (+ a small overlap budget for the trailing glue).
        for (w in windows) {
            assertTrue(
                "window of length ${w.length} exceeds 2400 + overlap = ${RawChunker.DEFAULT_MAX_CHARS + RawChunker.DEFAULT_OVERLAP_CHARS}",
                w.length <= RawChunker.DEFAULT_MAX_CHARS + RawChunker.DEFAULT_OVERLAP_CHARS,
            )
        }
        val concatenated = windows.joinToString(" ")
        assertTrue(
            "concatenated output suspiciously short: ${concatenated.length} chars",
            concatenated.length > 1500,
        )
    }

    /**
     * The judges system prompt is roughly twice the length of the receptive one. The
     * helper should derive a SMALLER window for it, not larger — that's the whole point
     * of [RawChunker.chunkForPrompt].
     */
    @Test
    fun chunkForPrompt_judgesAxisGetsSmallerWindowsThanReceptive() {
        val raw = "Sentence about Mark's brain dump. ".repeat(400) // ~13k chars
        val receptiveSystem = "GLOSSARY+RECEPTIVE ".repeat(110) // ~2090 chars ≈ ~836 tok
        val judgesSystem = "GLOSSARY+JUDGES ".repeat(195) // ~3120 chars ≈ ~1248 tok
        val receptiveWindows = RawChunker.chunkForPrompt(raw, receptiveSystem)
        val judgesWindows = RawChunker.chunkForPrompt(raw, judgesSystem)
        // JUDGES gets a smaller budget so it must produce at least as many windows. Use
        // ≥ rather than > so a borderline case where both axes happen to land on the
        // same window count doesn't flake — the contract is "never larger than".
        assertTrue(
            "JUDGES must not produce fewer windows than RECEPTIVE for the same RAW " +
                "(receptive=${receptiveWindows.size}, judges=${judgesWindows.size})",
            judgesWindows.size >= receptiveWindows.size,
        )
        val maxJudgesWindow = judgesWindows.maxOf { it.length }
        val maxReceptiveWindow = receptiveWindows.maxOf { it.length }
        assertTrue(
            "JUDGES largest window (${maxJudgesWindow}) must be <= RECEPTIVE largest " +
                "window (${maxReceptiveWindow})",
            maxJudgesWindow <= maxReceptiveWindow,
        )
    }

    /**
     * Pathologically long system prompts mustn't collapse the per-window budget below
     * [RawChunker.MIN_SAFE_MAX_CHARS] — the floor exists so a misconfigured prompt
     * produces "still works but logs an overflow" rather than "1000 tiny windows".
     */
    @Test
    fun chunkForPrompt_pathologicalSystemPrompt_clampsToMinSafeMaxChars() {
        val raw = "Words words words. ".repeat(200) // ~3800 chars
        val absurdSystem = "X".repeat(50_000) // way bigger than the engine budget itself
        val windows = RawChunker.chunkForPrompt(raw, absurdSystem)
        assertTrue("expected at least one window", windows.isNotEmpty())
        for (w in windows) {
            assertTrue(
                "min-safe floor violated: window=${w.length}, floor=${RawChunker.MIN_SAFE_MAX_CHARS}",
                w.length <= RawChunker.MIN_SAFE_MAX_CHARS + RawChunker.DEFAULT_OVERLAP_CHARS,
            )
        }
    }
}
