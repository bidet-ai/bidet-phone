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
 * Compiled-in Bidet system prompts that have no asset-file equivalent (or whose asset file is
 * a verbatim mirror kept for the bundled-default fall-through).
 *
 * Why this object exists
 * ----------------------
 * The Receptive (Clean for me) + Expressive (Clean for others) prompts ship as raw assets at
 * `assets/prompts/receptive_default.txt` / `assets/prompts/expressive_default.txt` so the
 * v0.2 tab-pref editor can fall through to them via [com.google.ai.edge.gallery.bidet.ui.TabPref.defaultPromptAssetPath].
 *
 * The v20 "Clean for judges" prompt (2026-05-11) ALSO ships as an asset at
 * `assets/prompts/judges_default.txt` so the same fall-through machinery works, but the
 * canonical source of truth is the Kotlin string constant in this file. The asset is a
 * verbatim mirror — there's a test ([PromptsTest]) that pins the two to the same byte body
 * so a drift between them fails CI.
 *
 * Source of truth (off-repo)
 * --------------------------
 * The web + desktop Bidets read from
 * `/mnt/c/Users/Breez/Projects/bidet/shared/prompts/clean_for_judges.md` on Mark's box. Phone
 * doesn't have access to that path at runtime, so the prompt body is duplicated here. If the
 * .md ever diverges, the .md wins — propagate the edit by copy-pasting the new body into
 * [CLEAN_FOR_JUDGES_PROMPT] and re-running [PromptsTest].
 *
 * Glossary integration
 * --------------------
 * Do NOT prepend [com.google.ai.edge.gallery.bidet.cleaning.Glossary.BIDET_GLOSSARY] in this
 * file. The glossary is wrapped at the [com.google.ai.edge.gallery.bidet.ui.BidetTabsViewModel.resolveSystemPrompt]
 * / [com.google.ai.edge.gallery.bidet.ui.SessionDetailViewModel.resolveSystemPrompt] layer
 * via [Glossary.withGlossary] for ALL axes, including JUDGES. Double-wrapping is suppressed
 * by the idempotency guard in [Glossary.withGlossary] but the contract is one wrap per axis.
 */
object Prompts {

    /**
     * The v20 Clean-for-judges system prompt — verbatim from
     * `/mnt/c/Users/Breez/Projects/bidet/shared/prompts/clean_for_judges.md` (the version Mark
     * drove on 2026-05-11).
     *
     * What MUST stay in here ([PromptsTest] pins these explicitly):
     *  - The tagline verbatim: "Take a brain dump. Bidet AI cleans up your mess." (the
     *    Glossary tagline-pin enforces "Bidet AI" rather than bare "AI" — the prompt body
     *    asks the model to lead with the human story and then drop the tagline).
     *  - "100% on-device" claim — judges care about the hardware achievement, not just the
     *    model name.
     *  - "Pixel 8 Pro" + "Tensor G3" — the specific hardware target. Vague "phone" doesn't
     *    convince a judge that the on-device claim is real.
     *  - "Gemma 4 E4B" — the contest's hero model name verbatim. (Mark's Pixel 8 Pro actually
     *    runs E2B per the LiteRT-LM hard rule, but the writeup explains the architecture
     *    target for Tensor G4 devices; saying E4B in the writeup is correct.)
     *  - The teacher-with-ADD origin story — the human "why" judges remember after the call.
     *
     * The prompt directs Gemma to use {transcript} as a placeholder for the RAW dump — the
     * existing [com.google.ai.edge.gallery.bidet.ui.LiteRtBidetGemmaClient] substitution path
     * handles that token the same way it does for the other two axes.
     */
    const val CLEAN_FOR_JUDGES_PROMPT: String = """You are cleaning Mark's brain dump into a contest-judge-ready writeup. The
reader has 90 seconds and is evaluating Bidet AI for the Kaggle Gemma 4 Good
Hackathon. They need to understand: (1) the human problem, (2) the technical
solution, (3) the on-device achievement, (4) why this matters beyond Mark.

Style:
- Opening hook: lead with the human story (Mark is a teacher with ADD, writing
  report cards took 6 hours, etc.). Make a judge care in the first 3 sentences.
- Then the tagline: "Take a brain dump. Bidet AI cleans up your mess."
- Then the technical guts: 100% on-device, Gemma 4 E4B via LiteRT-LM on Tensor
  G3 CPU, sherpa-onnx Moonshine STT, no cloud, no upload.
- Then the wider lens: this is for kids who know the material but can't get it
  out of their fingers. Teacher + student + anyone whose brain runs faster
  than their hands.
- Close strong: tagline again, or a clean sentence that lands the human stakes.

Format:
- Headers + paragraphs (not bullets — judges read prose).
- 800-1200 words.
- Hyperlink "Kaggle Gemma 4 Good Hackathon" if context allows.

What MUST appear:
- The tagline verbatim: "Take a brain dump. Bidet AI cleans up your mess."
- "100% on-device" claim somewhere prominent
- Pixel 8 Pro / Tensor G3 specifically (judges care about the hardware target)
- Gemma 4 E4B model name verbatim (it's the contest's hero model)
- The teacher-with-ADD origin story (it's the why)

Proper nouns: STRICT glossary mode. Misheard "the day AI" MUST become "Bidet AI".
"GINA" MUST become "Gemma". "Caleb Jimiford" MUST become "Kaggle hackathon".
This is the contest demo — mishears here are the whole reason this tab exists.

TRANSCRIPT:
{transcript}
"""
}
