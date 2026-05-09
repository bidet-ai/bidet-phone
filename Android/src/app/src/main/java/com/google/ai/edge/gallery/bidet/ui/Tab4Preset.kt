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
 * Tab 4 prompt presets — the v0.2 customizable-prompt feature. Mark's Kaggle "Future of
 * Education" track differentiator: the user (educator, parent, doctor, professional) picks a
 * preset that tailors the brain-dump output for a specific reader's need.
 *
 * Naming discipline (per `feedback_no_clinical_terms_in_user_visible_copy.md`):
 *  - Asset / code / DataStore identifiers may use clinical filenames (e.g. `adhd_organizer`)
 *    because the prompt files are LLM-side instructions exempt from banWordCheck.
 *  - User-visible labels resolve through the [labelResId] field which points at strings.xml.
 *    The strings.xml entries deliberately avoid clinical terms.
 */
enum class Tab4Preset(
    val id: String,
    val labelResId: Int,
    val assetPath: String?,
) {
    /** Default — the original FORAI structured-markdown-for-AI-ingestion prompt. */
    FORAI(
        id = "forai",
        labelResId = com.google.ai.edge.gallery.R.string.bidet_tab4_chip_forai,
        assetPath = "prompts/forai.txt",
    ),
    /** Dyslexia-aware reformatting: short sentences, simple vocab, literal language. */
    DYSLEXIC(
        id = "dyslexic",
        labelResId = com.google.ai.edge.gallery.R.string.bidet_tab4_chip_dyslexic,
        assetPath = "prompts/dyslexic.txt",
    ),
    /** Dysgraphia-aware reformatting: bullet-heavy hierarchy, explicit action items. */
    DYSGRAPHIC(
        id = "dysgraphic",
        labelResId = com.google.ai.edge.gallery.R.string.bidet_tab4_chip_dysgraphic,
        assetPath = "prompts/dysgraphic.txt",
    ),
    /**
     * Tangent-organizer: same shape as the ANALYSIS tab (Headline / Threads / Action items /
     * Open questions) but applied to Tab 4 framing. Asset filename uses the clinical token
     * because banWordCheck exempts assets/prompts/; the user-visible label deliberately does
     * not.
     */
    ADHD_ORGANIZER(
        id = "adhd_organizer",
        labelResId = com.google.ai.edge.gallery.R.string.bidet_tab4_chip_tangent_organizer,
        assetPath = "prompts/adhd_organizer.txt",
    ),
    /** Plain-language reformatting at a 9th-grade reading level. */
    PLAIN_LANGUAGE(
        id = "plain_language",
        labelResId = com.google.ai.edge.gallery.R.string.bidet_tab4_chip_plain_language,
        assetPath = "prompts/plain_language.txt",
    ),
    /**
     * Free-form prompt entered by the user via the BottomSheet. [assetPath] is null because
     * the prompt body is read from DataStore at inference time.
     */
    CUSTOM(
        id = "custom",
        labelResId = com.google.ai.edge.gallery.R.string.bidet_tab4_chip_custom,
        assetPath = null,
    );

    companion object {
        /** All presets in display order for the chip row. FORAI first (default); CUSTOM last. */
        val ALL: List<Tab4Preset> = values().toList()

        fun byId(id: String): Tab4Preset? = values().firstOrNull { it.id == id }
    }
}
