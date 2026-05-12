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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins v18.9 regex canonicalization of Mark's project nouns. Tests are anchored against
 * surface forms observed in the 2026-05-11 production brain-dump on Pixel 8 Pro
 * (Tensor G3 CPU, Moonshine-Tiny + Gemma 4 E4B int4).
 */
class RegexCanonicalizerTest {

    @Test fun convertsDayAIToBidetAI() {
        assertEquals("Bidet AI", RegexCanonicalizer.apply("day AI"))
        assertEquals("Bidet AI", RegexCanonicalizer.apply("the day AI"))
        assertEquals("Bidet AI", RegexCanonicalizer.apply("by day AI"))
        assertEquals("Bidet AI", RegexCanonicalizer.apply("bid eh AI"))
        assertEquals("Bidet AI", RegexCanonicalizer.apply("Bidé AI"))
        // Split "A I" form Moonshine sometimes emits
        assertEquals("Bidet AI", RegexCanonicalizer.apply("day A I"))
    }

    @Test fun convertsJana4E4BToGemma4E4B() {
        assertEquals("Gemma 4 E4B", RegexCanonicalizer.apply("Jana 4E4B"))
        assertEquals("Gemma 4 E4B", RegexCanonicalizer.apply("Jana 4 E4B"))
        assertEquals("Gemma 4 E4B", RegexCanonicalizer.apply("GINA 4 E 4 B"))
        assertEquals("Gemma 4 E4B", RegexCanonicalizer.apply("Gem of 4 E4B"))
        assertEquals("Gemma 4 E4B", RegexCanonicalizer.apply("Gem ah 4-E4B"))
    }

    @Test fun convertsShurpaArnoxToSherpaOnnx() {
        assertEquals("sherpa-onnx", RegexCanonicalizer.apply("Shurpa Arnox"))
        // Case-insensitive
        assertEquals(
            "We use sherpa-onnx on device.",
            RegexCanonicalizer.apply("We use shurpa arnox on device."),
        )
    }

    @Test fun convertsPixelateProToPixel8Pro() {
        assertEquals("Pixel 8 Pro", RegexCanonicalizer.apply("Pixelate Pro"))
        assertEquals("Pixel 8 Pro", RegexCanonicalizer.apply("Pixelet Pro"))
        assertEquals("Pixel 8 Pro", RegexCanonicalizer.apply("pixelate pro"))
    }

    @Test fun convertsCalebJimifordToKaggleHackathon() {
        assertEquals("Kaggle hackathon", RegexCanonicalizer.apply("Caleb Jimiford"))
        assertEquals("Kaggle hackathon", RegexCanonicalizer.apply("Caleb hackathon"))
        assertEquals("Kaggle hackathon", RegexCanonicalizer.apply("Kegel hack-athon"))
        // Bare "Caleb" followed by "contest" → "Kaggle"
        assertEquals(
            "Kaggle contest",
            RegexCanonicalizer.apply("Caleb contest"),
        )
        // Bare "Caleb" NOT followed by trigger word stays as Caleb (no false positive)
        assertEquals(
            "I talked to Caleb yesterday.",
            RegexCanonicalizer.apply("I talked to Caleb yesterday."),
        )
    }

    @Test fun convertsMoonshineMishearToMoonshine() {
        assertEquals("Moonshine", RegexCanonicalizer.apply("Moo shine"))
        assertEquals("Moonshine", RegexCanonicalizer.apply("Mooshine"))
        assertEquals(
            "We use Moonshine on device.",
            RegexCanonicalizer.apply("We use moo shine on device."),
        )
    }

    @Test fun preservesAlreadyCorrectStrings_BidetAI() {
        val s = "Bidet AI is the product."
        assertEquals(s, RegexCanonicalizer.apply(s))
    }

    @Test fun preservesAlreadyCorrectStrings_Gemma4E4B() {
        val s = "Gemma 4 E4B runs on Tensor G3."
        assertEquals(s, RegexCanonicalizer.apply(s))
    }

    @Test fun taglineFixedWithBidetAndMess() {
        val input = "Take a brain dump. AI cleans up your mass."
        assertEquals(
            "Take a brain dump. Bidet AI cleans up your mess.",
            RegexCanonicalizer.apply(input),
        )
    }

    @Test fun taglineFixedWhenMessAlreadyCorrect() {
        val input = "Take a brain dump. AI cleans up your mess."
        assertEquals(
            "Take a brain dump. Bidet AI cleans up your mess.",
            RegexCanonicalizer.apply(input),
        )
    }

    @Test fun taglineLeftAloneWhenAlreadyCanonical() {
        val s = "Take a brain dump. Bidet AI cleans up your mess."
        assertEquals(s, RegexCanonicalizer.apply(s))
    }

    @Test fun idempotent() {
        val inputs = listOf(
            "day AI is great",
            "Jana 4E4B on Tensor G3",
            "Shurpa Arnox plus moo shine",
            "Caleb Jimiford submission",
            "Take a brain dump. AI cleans up your mass.",
            "Bidet AI cleans up your mess. Gemma 4 E4B. sherpa-onnx.",
            "",
            "   ",
        )
        for (input in inputs) {
            val once = RegexCanonicalizer.apply(input)
            val twice = RegexCanonicalizer.apply(once)
            assertEquals("not idempotent for: <$input>", once, twice)
        }
    }

    @Test fun realProductionDumpExcerpt() {
        val raw = "Day version 18.7 shipped. Jana 4E4B cleans it up. " +
            "Shurpa Arnox moo shine on Tensor G 3. Caleb Jimiford deadline soon."
        val out = RegexCanonicalizer.apply(raw)
        assertTrue("missing 'Bidet version 18.7' in: $out", out.contains("Bidet version 18.7"))
        assertTrue("missing 'Gemma 4 E4B' in: $out", out.contains("Gemma 4 E4B"))
        assertTrue("missing 'sherpa-onnx' in: $out", out.contains("sherpa-onnx"))
        assertTrue("missing 'Moonshine' in: $out", out.contains("Moonshine"))
        assertTrue("missing 'Tensor G3' in: $out", out.contains("Tensor G3"))
        assertTrue("missing 'Kaggle hackathon' in: $out", out.contains("Kaggle hackathon"))
        assertFalse("'day AI' should be gone: $out", out.contains("day AI"))
        assertFalse("'Jana' should be gone: $out", out.contains("Jana"))
        assertFalse("'Shurpa' should be gone: $out", out.contains("Shurpa"))
        assertFalse("'Caleb' should be gone: $out", out.contains("Caleb"))
    }

    @Test fun blankInputReturnsAsIs() {
        assertEquals("", RegexCanonicalizer.apply(""))
        assertEquals("   ", RegexCanonicalizer.apply("   "))
    }

    @Test fun caseInsensitiveMatchPreservesCanonicalCasing() {
        // Replacement must always be the canonical (cased) form regardless of input case.
        assertEquals("Bidet AI", RegexCanonicalizer.apply("DAY AI"))
        assertEquals("Bidet AI", RegexCanonicalizer.apply("THE DAY ai"))
        assertEquals("Unsloth", RegexCanonicalizer.apply("UNSLOTH"))
        assertEquals("Unsloth", RegexCanonicalizer.apply("ensloth"))
    }

    @Test fun bareDayWithoutAISuffixIsNotReplaced() {
        // False-positive guard: "day" alone must NOT become "Bidet".
        val s = "It was a sunny day in October."
        assertEquals(s, RegexCanonicalizer.apply(s))
    }

    @Test fun convertsLightRTLMToLiteRTLM() {
        assertEquals("LiteRT-LM", RegexCanonicalizer.apply("Light RT LM"))
        assertEquals("LiteRT-LM", RegexCanonicalizer.apply("Light RTLM"))
    }

    @Test fun convertsWhisperCPPToWhisperCpp() {
        assertEquals("whisper.cpp", RegexCanonicalizer.apply("Whisper C P P"))
        assertEquals("whisper.cpp", RegexCanonicalizer.apply("whisper CPP"))
    }

    @Test fun convertsOMIMishearsToOMI() {
        assertEquals("OMI", RegexCanonicalizer.apply("Oh M I"))
        assertEquals("OMI", RegexCanonicalizer.apply("Omie"))
        assertEquals("OMI", RegexCanonicalizer.apply("Omey"))
    }

    @Test fun convertsTPCToTP3() {
        assertEquals("TP3", RegexCanonicalizer.apply("TPC"))
        assertEquals("TP3", RegexCanonicalizer.apply("TP 3"))
        assertEquals("TP3", RegexCanonicalizer.apply("TP3"))
    }

    @Test fun convertsAdultADHDToAdultADD() {
        assertEquals("adult ADD", RegexCanonicalizer.apply("Adult ADHD"))
        // Mid-sentence too
        val out = RegexCanonicalizer.apply("I have adult ADHD diagnosed in 2008.")
        assertTrue("expected 'adult ADD' in: $out", out.contains("adult ADD"))
    }

    @Test fun convertsGemma4VariantsToGemma4() {
        assertEquals("Gemma 4", RegexCanonicalizer.apply("Jana 4"))
        assertEquals("Gemma 4", RegexCanonicalizer.apply("GINA four"))
        assertEquals("Gemma 4", RegexCanonicalizer.apply("gem of 4"))
        assertEquals("Gemma 4", RegexCanonicalizer.apply("Gym of 4"))
        assertEquals("Gemma 4", RegexCanonicalizer.apply("Gym four"))
    }

    @Test fun convertsTensorG3Variants() {
        assertEquals("Tensor G3", RegexCanonicalizer.apply("Tensor G 3"))
        assertEquals("Tensor G3", RegexCanonicalizer.apply("Tensor G3"))
        assertEquals("Tensor G3", RegexCanonicalizer.apply("tensor g 333"))
    }

    @Test fun convertsGeminiMishears() {
        assertEquals("Gemini 2.5", RegexCanonicalizer.apply("Gemini T 5"))
        assertEquals("Gemini 2.5", RegexCanonicalizer.apply("gemini t5"))
        assertEquals("Gemini 2.5 Pro", RegexCanonicalizer.apply("195 pro"))
    }

    @Test fun convertsEphorbedeToE4B() {
        assertEquals("E4B", RegexCanonicalizer.apply("Ephorbede"))
    }

    @Test fun convertsDayVersionToBidetVersion() {
        assertEquals("Bidet version", RegexCanonicalizer.apply("Day version"))
        assertEquals(
            "Bidet version 18.7 shipped",
            RegexCanonicalizer.apply("Day version 18.7 shipped"),
        )
    }
}
