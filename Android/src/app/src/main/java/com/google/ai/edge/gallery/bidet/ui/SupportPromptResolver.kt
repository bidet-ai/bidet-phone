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
 * Pure-Kotlin state machine for the v0.2 preset chips on Tab 2 (Clean for me / Receptive
 * Support) and Tab 3 (Clean for others / Expressive Support). Extracted from
 * [BidetTabsViewModel] so resolution rules can be unit-tested without Android Context,
 * DataStore, or Hilt.
 *
 * Resolution rules:
 *  1. If the preset id is unknown, fall back to the axis default — prevents user-visible
 *     state loss if a future build removes a preset that was previously persisted.
 *  2. A built-in preset (non-custom) returns its bundled asset body.
 *  3. A custom preset returns the user's [customPrompt] when non-blank; if blank, falls
 *     back to the axis default — defensive against the BottomSheet's Save button being
 *     bypassed via a previous build's persistence.
 */
object SupportPromptResolver {

    /**
     * Resolve the system prompt body for the active preset on a given axis.
     *
     * @param axis           which tab is asking — gates the default fallback.
     * @param activePresetId DataStore-persisted preset id ([SupportPreset.id]).
     * @param customPrompt   DataStore-persisted free-form prompt (used iff the active preset
     *                       is the axis-specific custom preset).
     * @param loadAsset      caller lambda that returns the contents of an asset path. The
     *                       ViewModel passes the real Android AssetManager loader; tests pass
     *                       a deterministic stub.
     * @return the resolved system-prompt string. Always non-null.
     */
    fun resolve(
        axis: SupportAxis,
        activePresetId: String,
        customPrompt: String,
        loadAsset: (String) -> String,
    ): String {
        val preset = SupportPreset.byId(activePresetId)
            ?.takeIf { it.axis == axis }
            ?: SupportPreset.defaultFor(axis)

        return if (preset.isCustom) {
            val trimmed = customPrompt.trim()
            if (trimmed.isBlank()) {
                // Defensive: never fire inference with an empty system prompt. Fall back to
                // the axis default so the user gets *some* sensible output.
                val fallback = SupportPreset.defaultFor(axis)
                val path = fallback.assetPath
                    ?: error("Default preset $fallback has no assetPath; should be unreachable")
                loadAsset(path)
            } else {
                customPrompt
            }
        } else {
            val path = preset.assetPath
                ?: error("Built-in preset $preset has no assetPath; should be unreachable")
            loadAsset(path)
        }
    }
}
