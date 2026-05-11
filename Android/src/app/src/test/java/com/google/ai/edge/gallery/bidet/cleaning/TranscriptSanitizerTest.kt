package com.google.ai.edge.gallery.bidet.cleaning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptSanitizerTest {

    @Test fun emptyAndBlank() {
        assertEquals("", TranscriptSanitizer.clean(""))
        assertEquals("", TranscriptSanitizer.clean("   "))
    }

    @Test fun realSpeechIsPreserved() {
        val s = "I'm a middle-school teacher, twenty-five years, and I have ADD."
        assertEquals(s, TranscriptSanitizer.clean(s))
    }

    @Test fun musicNotesAreStripped() {
        val s = "the brain dump ♪♪♪♪♪♪♪♪♪♪♪♪♪ has changed my life"
        val out = TranscriptSanitizer.clean(s)
        assertFalse("music notes survived", out.contains("♪"))
        assertTrue(out.contains("brain dump"))
        assertTrue(out.contains("changed my life"))
    }

    @Test fun thaiTrailerIsStripped() {
        val s = "Take a brain dump. 2-3ตวบนลาง 01/02/2565"
        val out = TranscriptSanitizer.clean(s)
        assertFalse("Thai survived: $out", out.contains("ตวบนลาง"))
    }

    @Test fun cjkTrailerIsStripped() {
        val s = "End of session. 你好世界 漢字"
        val out = TranscriptSanitizer.clean(s)
        assertFalse(out.contains("你"))
        assertFalse(out.contains("漢"))
    }

    @Test fun repeatTokenRunIsCappedToThree() {
        val s = "I really really really really really really really really really really really care"
        val out = TranscriptSanitizer.clean(s)
        val matches = Regex("\\breally\\b").findAll(out).count()
        assertEquals(3, matches)
    }

    @Test fun cardRunIsCappedToThree() {
        val s = "And so report card card card card card card card card card card card card card and"
        val out = TranscriptSanitizer.clean(s)
        val matches = Regex("\\bcard\\b").findAll(out).count()
        assertEquals(3, matches)
    }

    @Test fun fillerUhRunIsCollapsed() {
        val s = "All right uh uh uh uh uh uh uh uh uh uh uh uh uh so the thing"
        val out = TranscriptSanitizer.clean(s)
        val matches = Regex("\\buh\\b").findAll(out).count()
        assertEquals(1, matches)
    }

    @Test fun fillerUmRunIsCollapsed() {
        val s = "I was thinking um, um, um, um, um about the thing"
        val out = TranscriptSanitizer.clean(s)
        val matches = Regex("\\bum\\b").findAll(out).count()
        assertEquals(1, matches)
    }

    @Test fun fakeNumberTrailerIsStripped() {
        val s = "the latitude was 8, 10, 10, 10, 10, 1, 8 millions or something"
        val out = TranscriptSanitizer.clean(s)
        assertFalse("fake number sequence survived: $out", Regex("\\b10[,\\s]+10[,\\s]+10\\b").containsMatchIn(out))
    }

    @Test fun shortNumberListIsPreserved() {
        // Real "1, 2, 3" should NOT be stripped (only ≥4 numbers triggers).
        val s = "the count was 1, 2, 3"
        val out = TranscriptSanitizer.clean(s)
        assertTrue(out.contains("1, 2, 3"))
    }

    @Test fun bathroomGhostIsStripped() {
        val s = "Taking a break I'm just going to go to the bathroom. I'm just going to go to the bathroom. So anyway"
        val out = TranscriptSanitizer.clean(s)
        assertFalse("bathroom ghost survived: $out", out.contains("just going to go to the bathroom"))
        assertTrue(out.contains("Taking a break"))
        assertTrue(out.contains("So anyway"))
    }

    @Test fun singleBathroomMentionIsPreserved() {
        // One instance might be real speech.
        val s = "I'm just going to go to the bathroom and then we'll continue"
        val out = TranscriptSanitizer.clean(s)
        assertTrue(out.contains("bathroom"))
    }

    @Test fun multiSpaceCollapsed() {
        val s = "word     other   word"
        assertEquals("word other word", TranscriptSanitizer.clean(s))
    }

    @Test fun idempotent() {
        val s = "the report card card card card card was anxiety-filling. uh uh uh uh and then ♪♪♪♪"
        val once = TranscriptSanitizer.clean(s)
        val twice = TranscriptSanitizer.clean(once)
        assertEquals(once, twice)
    }
}
