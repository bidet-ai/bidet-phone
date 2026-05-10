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
 * User-editable label + prompt template for a single generated tab. Two-tab restructure
 * (2026-05-10): replaces the v0.2 [SupportPreset] enum + chip catalogue with a flat
 * (label, prompt) pair per axis, both editable via the bottom-sheet editor.
 *
 * - `label` is the user-facing chip text. Defaults to "Clean for me" (axis 0) or
 *   "Clean for others" (axis 1).
 * - `promptTemplate` is the system prompt sent to Gemma. The placeholder `{transcript}` is
 *   substituted with the RAW transcript at inference time (the existing default prompts in
 *   `prompts/receptive_default.txt` and `prompts/expressive_default.txt` already use this
 *   token).
 *
 * Why a flat pair instead of a preset enum: Mark's redirect — two tabs, two editable prompts,
 * sensible defaults. The preset chip rows (Summary / Dyslexic / Plain language / etc) painted
 * the model into a "more chips = more value" corner; renaming the tab IS the customization.
 */
data class TabPref(
    val axis: SupportAxis,
    val label: String,
    val promptTemplate: String,
) {
    companion object {
        /**
         * Default label for an axis. These ship as the chip text on first launch and can be
         * restored via the editor's "Reset to default" button.
         */
        fun defaultLabel(axis: SupportAxis): String = when (axis) {
            SupportAxis.RECEPTIVE -> "Clean for me"
            SupportAxis.EXPRESSIVE -> "Clean for others"
        }

        /**
         * Default asset path containing the fidelity-first prompt for an axis. These are the
         * PR #28 prompts; do not edit them in this PR — the bottom-sheet "Reset to default"
         * button reads them through this map. Both files use the `{transcript}` placeholder.
         */
        fun defaultPromptAssetPath(axis: SupportAxis): String = when (axis) {
            SupportAxis.RECEPTIVE -> "prompts/receptive_default.txt"
            SupportAxis.EXPRESSIVE -> "prompts/expressive_default.txt"
        }
    }
}
