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

/**
 * Deterministic regex canonicalization of project nouns (v18.9, 2026-05-11).
 *
 * Why
 * ---
 * v18.8 prepended a project-noun glossary to the cleaning prompt so Gemma 4 E4B could
 * canonicalize Moonshine mishears in context. In production tonight (2026-05-11) the int4
 * E4B on Tensor G3 was not consistently applying the glossary — Mark's brain dump still
 * shipped with "day AI", "Jana 4E4B", "Shurpa Arnox", "Caleb Jimiford", etc. The model
 * just isn't smart enough at this quant + this hardware to treat a context list as a
 * substitution table.
 *
 * Fix: pull canonicalization OUT of the model and INTO a pure-Kotlin regex pass that runs
 * at the sanitizer layer, before the RAW chunk lands in the stitched buffer. Defense in
 * depth: the v18.8 glossary preamble is upgraded to imperative ("MANDATORY SUBSTITUTION
 * RULES") so any NOVEL mishear the regex misses can still be caught by Gemma.
 *
 * Design rules
 * ------------
 *   * Case-insensitive matching; replacement is ALWAYS the canonical (cased) form.
 *   * Idempotent — running twice equals running once. Use canonical strings in the
 *     replacement that never re-match the pattern.
 *   * Order matters: more-specific rules first. (E.g. "Jana 4 E4B" must fire before
 *     "Jana 4" so we don't end up with "Gemma 4 E4B" → "Gemma 4 E4B" via two passes
 *     that mangled the suffix.)
 *   * No false positives on bare words. "day" alone is NOT replaced — only "day AI"
 *     becomes "Bidet AI". Same for "Caleb" — only "Caleb hackathon/contest/prize/etc."
 *     resolves to Kaggle.
 *   * Runs AFTER all v18.7 sanitizer passes (music-note, CJK, repeat-token, phrase-
 *     repeat) so we don't unbalance their invariants.
 */
object RegexCanonicalizer {

    /**
     * Apply the canonicalization table. Returns [text] unchanged when blank.
     * Idempotent: `apply(apply(x)) == apply(x)` for all x.
     */
    fun apply(text: String): String {
        if (text.isBlank()) return text
        var out = text
        for (rule in RULES) {
            out = rule.pattern.replace(out, rule.replacement)
        }
        // Tagline post-processing pass — handle the "AI cleans up your mass/mess"
        // case where we must (a) insert "Bidet " before AI if not already there, and
        // (b) normalize "mass" → "mess". Done after the table because the table's
        // simple replacement can't conditionally insert text based on the preceding
        // word.
        out = applyTaglineFix(out)
        return out
    }

    // --- Tagline fix --------------------------------------------------------------

    /**
     * Tagline: `... AI cleans up your mass|mess ...` not preceded by `Bidet `.
     * Inserts `Bidet ` before `AI` and forces the noun to `mess`. The trailing
     * punctuation (if any) is preserved.
     *
     * Idempotent: input "Bidet AI cleans up your mess" is left alone because the
     * lookbehind `(?<!Bidet )` does not match.
     */
    private fun applyTaglineFix(text: String): String {
        return TAGLINE_PATTERN.replace(text) { m ->
            "Bidet AI cleans up your mess"
        }
    }

    /**
     * Negative lookbehind on "Bidet " so we don't double-insert. Matches both
     * "mass" and "mess"; output is always "mess".
     */
    private val TAGLINE_PATTERN = Regex(
        "(?<!Bidet\\s)\\bAI\\s+cleans\\s+up\\s+your\\s+(?:mass|mess)\\b",
        RegexOption.IGNORE_CASE,
    )

    // --- Substitution table -------------------------------------------------------

    private data class Rule(val pattern: Regex, val replacement: String)

    private fun rule(pat: String, replacement: String): Rule =
        Rule(Regex(pat, RegexOption.IGNORE_CASE), replacement)

    /**
     * Order matters. More specific rules fire first. Every replacement uses the
     * canonical (cased) form so a second pass over the output is a no-op.
     */
    private val RULES: List<Rule> = listOf(
        // --- Bidet AI ---
        // "the day AI", "day AI", "by day AI", "bid eh AI", "Bidé AI" → "Bidet AI".
        // Allow "A I" with optional space between A and I (Moonshine sometimes splits).
        rule("""\b(?:the\s+day|by\s+day|bid\s*eh|Bid[eé]|day)\s+(?:AI|A\s*I)\b""", "Bidet AI"),

        // "Day version" → "Bidet version"
        rule("""\bDay\s+version\b""", "Bidet version"),

        // --- Gemma 4 E4B (must run BEFORE the bare "Gemma 4" rule) ---
        // "Jana 4E4B", "GINA 4 E 4 B", "Gem of 4 E4B", "Gem ah 4-E4B" → "Gemma 4 E4B"
        rule("""\b(?:Jana|GINA|Gem(?:\s*of|\s*ah))\s*4\s*[-]?\s*E\s*4\s*B\b""", "Gemma 4 E4B"),

        // --- Gemma 4 (after E4B) ---
        rule("""\b(?:Jana|GINA|gem(?:\s*of|\s*ah))\s*(?:4|four)\b""", "Gemma 4"),
        rule("""\bGym(?:\s*of)?\s*(?:4|four)\b""", "Gemma 4"),

        // --- E4B standalone mishear ---
        rule("""\bEphorbede\b""", "E4B"),

        // --- Unsloth ---
        rule("""\b(?:unsloss|un-sloth|uns-?loth|ens-?loth|an-?sloth)\b""", "Unsloth"),

        // --- Kaggle hackathon (must run BEFORE bare Kaggle rule) ---
        rule("""\b(?:Caleb|Kegel)\s+(?:hack-?athon|Jimiford)\b""", "Kaggle hackathon"),

        // --- Kaggle bare (only when followed by contest/prize/submission/hackathon) ---
        rule("""\b(?:Caleb|Kegel)\b(?=\s*(?:contest|prize|submission|hackathon))""", "Kaggle"),

        // --- sherpa-onnx ---
        rule("""\bShurpa\s+Arnox\b""", "sherpa-onnx"),

        // --- Moonshine ---
        rule("""\bMoo\s*shine\b""", "Moonshine"),

        // --- Tensor G3 ---
        rule("""\bTensor\s+G\s*3+\b""", "Tensor G3"),

        // --- Pixel 8 Pro ---
        rule("""\bPixel(?:ate|let)\s+(?:Pro|pro)\b""", "Pixel 8 Pro"),

        // --- LiteRT-LM ---
        rule("""\bLight\s+RT\s*L\s*M\b""", "LiteRT-LM"),

        // --- whisper.cpp ---
        rule("""\bWhisper\s+C\s*P\s*P\b""", "whisper.cpp"),

        // --- OMI ---
        rule("""\bOh\s+M\s+I\b""", "OMI"),
        rule("""\b(?:Omie|Omey)\b""", "OMI"),

        // --- TP3 ---
        rule("""\bTP\s*(?:3|C)\b""", "TP3"),

        // --- Gemini ---
        rule("""\bGemini\s+T\s*5\b""", "Gemini 2.5"),
        rule("""\b195\s+pro\b""", "Gemini 2.5 Pro"),

        // --- adult ADD (Mark has ADD not ADHD) ---
        rule("""\bAdult\s+ADHD\b""", "adult ADD"),
    )
}
