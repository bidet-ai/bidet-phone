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
 * Project-noun glossary + tagline pin for the cleaning prompts (v18.8, 2026-05-11).
 *
 * Why
 * ---
 * v18.7 stopped crashing but every clean output still mishears Mark's project vocabulary.
 * From a 2026-05-11 brain-dump on the Pixel 8 Pro (Tensor G3 CPU, Gemma 4 E4B):
 *   "Bidet AI"        → "the day AI"
 *   "Gemma"           → "GINA"
 *   "Unsloth"         → "ensloth"
 *   "Kaggle hackathon" → "Caleb Jimiford"
 *
 * Moonshine (the on-device STT) hears these wrong; Gemma faithfully cleans the mishear.
 * Fixing the upstream STT model is out of scope for v18.8. Prepending this canonical list
 * to the cleaning prompt gives Gemma enough context to canonicalize-on-the-fly when the
 * surrounding sentence makes the intended noun unambiguous.
 *
 * The list is intentionally short. Tensor G3 decode is ~10 tk/s; every prompt token costs
 * wall-clock. Mark accepted the ~250-token cost as the right tradeoff for contest-week
 * (Kaggle Gemma 4 deadline 2026-05-18) — do NOT shrink it without asking.
 *
 * Source of truth
 * ---------------
 * Keep this string in sync with `/mnt/c/Users/Breez/Projects/bidet/shared/glossary.md` on
 * Mark's box. The .md file is the human-edited spec; this .kt is the compiled-in copy. If
 * the two diverge, the .md wins.
 *
 * Integration
 * -----------
 * Injected at the top of every cleaning prompt by [withGlossary]. Both the per-chunk
 * receptive prompt (ChunkCleaner during recording) and the on-tap receptive/expressive
 * prompts (SessionDetailScreen + BidetTabsViewModel) call it. The existing fidelity-first
 * prompt body (PR #28) is preserved verbatim — this is an additive preamble.
 */
object Glossary {

    /**
     * The canonical glossary block. Prepended to every cleaning prompt.
     *
     * NEVER trim, reorder, or "summarize" this without Mark's sign-off. The mishears column
     * is the reason the model can recover the canonical spelling — those are the surface
     * forms Moonshine actually produces.
     */
    const val BIDET_GLOSSARY: String = """PROJECT VOCABULARY — Mark uses these proper nouns. Moonshine often mishears
them; canonicalize when context clearly refers to one of these. NEVER invent.

Canonical (mishears observed):
- Bidet AI (mishears: "the day AI", "Bidé AI", "by day AI")
- Bidet (mishears: "the day", "bid eh")
- Honest Answers (mishears: "honest hours")
- Gemma 4 (mishears: "GINA 4", "gem ah", "gem of for")
- Gemma 4 E4B (mishears: "Gemma 4 E for B", "GINA 4 E for B")
- Unsloth (mishears: "ensloth", "uns loth")
- Kaggle (mishears: "Caleb", "kegel")
- Kaggle hackathon (mishears: "Caleb Jimiford")
- LiteRT-LM (mishears: "light RT LM")
- sherpa-onnx (mishears: "sherpa Onyx")
- Moonshine
- Tensor G3 (mishears: "tensor G three")
- whisper.cpp (mishears: "whisper C P P")
- OMI (mishears: "Oh M I", "Omie")
- TP3 (mishears: "TPC", "tee pee three")
- Apex
- G16
- St. Francis (school)
- Pixel 8 Pro

TAGLINE PIN: Mark's product tagline is exactly "Take a brain dump. Bidet AI cleans
up your mess." When the transcript references the tagline or paraphrases it,
canonicalize to that exact wording. Never strip the word "Bidet" from "Bidet AI
cleans up your mess".
"""

    /**
     * Prepend [BIDET_GLOSSARY] (followed by a blank line) to [basePrompt]. Used by every
     * cleaning call site so the glossary lands in:
     *  - ChunkCleaner per-chunk receptive prompt (RecordingService.kt)
     *  - SessionDetailScreen on-tap receptive/expressive prompts
     *  - BidetTabsViewModel live-screen clean prompts
     *
     * Returns the base prompt unchanged if it already starts with the glossary header — this
     * makes the function idempotent so a future caller can call it twice without doubling
     * the preamble (and so unit tests can assert the prefix once).
     */
    fun withGlossary(basePrompt: String): String {
        if (basePrompt.startsWith(GLOSSARY_HEADER)) return basePrompt
        return BIDET_GLOSSARY + "\n" + basePrompt
    }

    /** First-line marker used by [withGlossary]'s idempotency guard. */
    private const val GLOSSARY_HEADER: String =
        "PROJECT VOCABULARY — Mark uses these proper nouns."
}
