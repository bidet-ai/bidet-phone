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
}
