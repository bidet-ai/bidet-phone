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

/**
 * v0.2 three-tab restructure (RAW / Clean for me / Clean for others).
 *
 * Internal naming follows the speech-language-pathology axis Mark adopted for grant docs and
 * code:
 *  - "Receptive Support" — output FOR the speaker to read / process. Maps to Tab 2 in the UI,
 *    user-visible label "Clean for me".
 *  - "Expressive Support" — output FROM the speaker to be parsed by another reader (human or
 *    AI). Maps to Tab 3 in the UI, user-visible label "Clean for others".
 *
 * The old Tab4Preset enum is replaced by [SupportPreset], which carries an [axis] field so a
 * single chip catalogue can be filtered by tab. This is the v0.2 successor to the v0.1 Tab4
 * customizable-prompt machinery (preset chips → resolver → DataStore-backed selection +
 * SHA-cache invalidation) but extended across both Clean tabs instead of only Tab 4.
 *
 * Naming discipline (per `feedback_no_clinical_terms_in_user_visible_copy.md`):
 *  - Asset / code / DataStore identifiers may use clinical filenames (e.g. `dyslexic.txt`)
 *    because the prompt files are LLM-side instructions exempt from banWordCheck.
 *  - User-visible labels resolve through the [labelResId] field which points at strings.xml.
 *    The strings.xml entries deliberately avoid clinical terms.
 */
enum class SupportAxis {
    /** Output FOR the speaker — Tab 2, user-visible "Clean for me". */
    RECEPTIVE,
    /** Output FROM the speaker — Tab 3, user-visible "Clean for others". */
    EXPRESSIVE,
}

enum class SupportPreset(
    val id: String,
    val axis: SupportAxis,
    val labelResId: Int,
    val assetPath: String?,
) {
    /* ----------------------------------------------------------------------------------- *
     * RECEPTIVE — Clean for me (Tab 2). Output FOR the speaker to read back.
     * ----------------------------------------------------------------------------------- */

    /** Default Receptive preset — organized prose summary the speaker can scan. */
    RECEPTIVE_SUMMARY(
        id = "receptive_summary",
        axis = SupportAxis.RECEPTIVE,
        labelResId = com.google.ai.edge.gallery.R.string.bidet_preset_summary,
        assetPath = "prompts/receptive_default.txt",
    ),
    /** Dyslexia-aware reformatting: short sentences, simple vocab, literal language. */
    RECEPTIVE_DYSLEXIC(
        id = "receptive_dyslexic",
        axis = SupportAxis.RECEPTIVE,
        labelResId = com.google.ai.edge.gallery.R.string.bidet_preset_short_sentences,
        assetPath = "prompts/dyslexic.txt",
    ),
    /** Dysgraphia-aware reformatting: bullet-heavy hierarchy, explicit action items. */
    RECEPTIVE_DYSGRAPHIC(
        id = "receptive_dysgraphic",
        axis = SupportAxis.RECEPTIVE,
        labelResId = com.google.ai.edge.gallery.R.string.bidet_preset_bullets_and_actions,
        assetPath = "prompts/dysgraphic.txt",
    ),
    /**
     * Tangent-organizer: same shape as the v0.1 ANALYSIS tab (Headline / Threads / Action
     * items / Open questions). The asset filename uses the clinical token because
     * banWordCheck exempts assets/prompts/; the user-visible label deliberately does not.
     */
    RECEPTIVE_TANGENT_ORGANIZER(
        id = "receptive_tangent_organizer",
        axis = SupportAxis.RECEPTIVE,
        labelResId = com.google.ai.edge.gallery.R.string.bidet_preset_tangent_organizer,
        assetPath = "prompts/adhd_organizer.txt",
    ),
    /** Plain-language reformatting at a 9th-grade reading level. */
    RECEPTIVE_PLAIN_LANGUAGE(
        id = "receptive_plain_language",
        axis = SupportAxis.RECEPTIVE,
        labelResId = com.google.ai.edge.gallery.R.string.bidet_preset_plain_language,
        assetPath = "prompts/plain_language.txt",
    ),
    /**
     * Free-form prompt entered by the user via the BottomSheet. [assetPath] is null because
     * the prompt body is read from DataStore at inference time.
     */
    RECEPTIVE_CUSTOM(
        id = "receptive_custom",
        axis = SupportAxis.RECEPTIVE,
        labelResId = com.google.ai.edge.gallery.R.string.bidet_preset_custom,
        assetPath = null,
    ),

    /* ----------------------------------------------------------------------------------- *
     * EXPRESSIVE — Clean for others (Tab 3). Output FROM the speaker for another reader.
     * ----------------------------------------------------------------------------------- */

    /** Default Expressive preset — structured markdown for AI-ingestion (Title/Context/etc). */
    EXPRESSIVE_AI_INGEST(
        id = "expressive_ai_ingest",
        axis = SupportAxis.EXPRESSIVE,
        labelResId = com.google.ai.edge.gallery.R.string.bidet_preset_ai_ingest_markdown,
        assetPath = "prompts/expressive_default.txt",
    ),
    /** Workplace-email rewrite: concise, professional tone, subject line + body + sign-off. */
    EXPRESSIVE_EMAIL_DRAFT(
        id = "expressive_email_draft",
        axis = SupportAxis.EXPRESSIVE,
        labelResId = com.google.ai.edge.gallery.R.string.bidet_preset_email_draft,
        assetPath = "prompts/email_draft.txt",
    ),
    /** Pasted into another autonomous AI agent — the v0.1 forai prompt verbatim. */
    EXPRESSIVE_AI_AGENT(
        id = "expressive_ai_agent",
        axis = SupportAxis.EXPRESSIVE,
        labelResId = com.google.ai.edge.gallery.R.string.bidet_preset_ai_agent_input,
        assetPath = "prompts/forai.txt",
    ),
    /** Free-form prompt entered by the user via the BottomSheet. */
    EXPRESSIVE_CUSTOM(
        id = "expressive_custom",
        axis = SupportAxis.EXPRESSIVE,
        labelResId = com.google.ai.edge.gallery.R.string.bidet_preset_custom,
        assetPath = null,
    );

    /** True iff this preset's prompt body is sourced from the user's DataStore-saved custom text. */
    val isCustom: Boolean
        get() = assetPath == null

    companion object {
        /** All Receptive presets in display order. Default first; Custom last. */
        val RECEPTIVE: List<SupportPreset> = values().filter { it.axis == SupportAxis.RECEPTIVE }

        /** All Expressive presets in display order. Default first; Custom last. */
        val EXPRESSIVE: List<SupportPreset> = values().filter { it.axis == SupportAxis.EXPRESSIVE }

        fun byId(id: String): SupportPreset? = values().firstOrNull { it.id == id }

        /** Default preset id for a given axis. */
        fun defaultFor(axis: SupportAxis): SupportPreset = when (axis) {
            SupportAxis.RECEPTIVE -> RECEPTIVE_SUMMARY
            SupportAxis.EXPRESSIVE -> EXPRESSIVE_AI_INGEST
        }
    }
}
