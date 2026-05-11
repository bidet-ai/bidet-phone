/*
 * Copyright 2026 bidet-ai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.bidet.cleaning

/**
 * Strips Moonshine-class transcription artifacts from a per-chunk text before the chunk
 * is merged into the running RAW. Pure logic — unit-testable, no Android dependencies.
 *
 * Why a sanitizer at all: sherpa-onnx Moonshine-Tiny hallucinates aggressively during
 * silence / breath / file-end. Common patterns we've observed in Mark Barnett's brain
 * dumps (2026-05-10):
 *
 *  - **Music-note runs** (`♪♪♪♪♪♪…`) — Moonshine was trained on YouTube and emits ASCII
 *    music tokens when the audio is silent.
 *  - **CJK / Thai-script trailers** — random Thai or Chinese characters at chunk end
 *    where Moonshine ran out of English to predict (`2-3ตวบนลาง 01/02/2565`).
 *  - **Repeat-token loops** — "really really really…" ×30, "card card card…" ×27,
 *    "well well well…" ×50, "uh uh uh uh…" ×60. Triggered by very short pauses in the
 *    encoder's quantized graph.
 *  - **The bathroom fabrication** — the literal phrase "I'm just going to go to the
 *    bathroom" emitted during silence gaps. Specific YouTube-training-set ghost.
 *  - **Fake-number trailers** — "8, 10, 10, 10, 10, 1, 8…" sequences where Moonshine
 *    mis-heard a coordinate or a time and ran the digit loop.
 *  - **Single-character runs** — "First, first, first, first." patterns where a single
 *    short word repeats >3 times.
 *
 * Design: don't touch real content. The sanitizer's job is to drop obvious garbage
 * without rewriting words. Mishears like "Pixar" for "fix" or "Bidet AI" → "the day AI"
 * stay — those are downstream cleanings (the model picks them up with context).
 */
object TranscriptSanitizer {

    /** Sanitize a per-chunk Moonshine output. Idempotent: calling twice == calling once. */
    fun clean(text: String): String {
        if (text.isBlank()) return ""
        var out = text

        // 1) Drop music-note runs entirely.
        out = MUSIC_NOTE.replace(out, "")

        // 2) Drop non-Latin scripts (Thai, CJK, Cyrillic, Arabic, etc.) — Moonshine
        //    silence trailers, never real English speech. Keep accented Latin
        //    (Latin-1 Supplement and Latin Extended-A/B).
        out = NON_LATIN.replace(out, "")

        // 3) Drop trailing fake-number sequences like "8, 10, 10, 10, 10, 1, 8".
        //    Conservative: only kill when ≥4 commas separating short integers with
        //    repetition (a real conversation rarely emits "1, 1, 1, 1, 1" mid-sentence).
        out = FAKE_NUMBER_RUN.replace(out, "")

        // 4) Drop the specific "I'm just going to go to the bathroom" silence-fill
        //    when it appears ≥2× consecutively (one instance might be real speech).
        out = BATHROOM_GHOST.replace(out, "")

        // 5) Collapse "uh"/"um" runs to a single instance. Real speakers say "uh, uh"
        //    occasionally; cap at 1. (Match case-insensitively.)
        out = FILLER_RUN.replace(out) { match ->
            match.value.split(Regex("[\\s,.]+")).first()
        }

        // 6) Cap any word repeated ≥4× consecutively to 3 (preserves emphasis like
        //    "really, really, really" but cuts "really" × 30 down to 3).
        out = REPEAT_TOKEN_RUN.replace(out) { match ->
            val word = match.groupValues[1]
            "$word $word $word"
        }

        // 7) Collapse multi-space and multi-comma sequences left behind.
        out = MULTI_SPACE.replace(out, " ")
        out = MULTI_COMMA.replace(out, ",")

        return out.trim()
    }

    // --- Patterns ---

    /** Music-note characters (sometimes Moonshine emits the eighth-note glyph). */
    private val MUSIC_NOTE = Regex("[♪♫♬♩]+")

    /**
     * Anything in Unicode ranges OUTSIDE Latin / Latin-1 Supplement / Latin Extended /
     * common punctuation / digits / whitespace. The negated character class keeps
     * U+0000..U+007F (basic), U+00A0..U+024F (Latin Extended), U+2000..U+206F (general
     * punctuation), and drops everything else.
     */
    private val NON_LATIN = Regex("[^\\u0000-\\u007F\\u00A0-\\u024F\\u2000-\\u206F]+")

    /**
     * Trailing fake-number sequence: ≥4 short integers separated by commas/spaces.
     * Examples killed: "8, 10, 10, 10, 10, 1, 8", "1, 1, 1, 1, 1, 1", "10 10 10 10 10".
     * Not killed: "the count was 1, 2, 3" (only 3 numbers).
     */
    private val FAKE_NUMBER_RUN = Regex("\\b\\d{1,4}([\\s,]+\\d{1,4}){3,}\\b")

    /** Two or more consecutive instances of the bathroom ghost phrase. */
    private val BATHROOM_GHOST = Regex(
        "(I'm just going to go to the bathroom\\.?\\s*){2,}",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Filler-word run: "uh, uh, uh, uh" or "um um um um" — cap at 1. Captures runs of
     * the same filler word, allowing comma / period / whitespace separators.
     */
    private val FILLER_RUN = Regex(
        "\\b(uh|um|ah|er|hm+)\\b([\\s,.]+\\1\\b){2,}",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Same word repeated ≥4 consecutive times (case-insensitive). Captures into group 1
     * so we can reuse it. Whitespace / comma separators allowed.
     */
    private val REPEAT_TOKEN_RUN = Regex(
        "\\b([A-Za-z']{1,20})\\b([\\s,]+\\1\\b){3,}",
        RegexOption.IGNORE_CASE,
    )

    private val MULTI_SPACE = Regex("[ \\t]{2,}")
    private val MULTI_COMMA = Regex(",{2,}")
}
