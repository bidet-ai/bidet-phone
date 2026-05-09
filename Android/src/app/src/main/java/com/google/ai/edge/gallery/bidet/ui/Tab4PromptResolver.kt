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
 * Pure-Kotlin state machine for the Tab 4 prompt-selection feature. Extracted from
 * [BidetTabsViewModel] so the resolution rules can be unit-tested without Android Context,
 * DataStore, or Hilt. The ViewModel uses this object to translate (activePresetId,
 * customPrompt) into the final system prompt body.
 *
 * Resolution rules (verbatim from brief):
 *  1. If the preset id is unknown, fall back to FORAI default — prevents user-visible state
 *     loss if a future build removes a preset that was previously persisted.
 *  2. FORAI: returns null. The caller treats null as "use the existing v0.1 path" — which
 *     means consult the debug override, then the bundled FORAI asset.
 *  3. CUSTOM: returns the [customPrompt] if non-blank; null otherwise (so an empty custom
 *     prompt falls through to FORAI default rather than firing inference with an empty
 *     system prompt).
 *  4. Any built-in preset (DYSLEXIC / DYSGRAPHIC / ADHD_ORGANIZER / PLAIN_LANGUAGE):
 *     returns the prompt body loaded from the preset's asset. The asset-load is delegated
 *     to a caller-supplied lambda so tests can substitute a fake.
 */
object Tab4PromptResolver {

    /**
     * Resolve the Tab 4 system prompt.
     *
     * @param activePresetId DataStore-persisted preset id ([Tab4Preset.id]).
     * @param customPrompt   DataStore-persisted free-form prompt (used iff CUSTOM).
     * @param loadAsset      Caller lambda that returns the contents of an asset path. The
     *                       ViewModel passes the real Android AssetManager loader; tests pass
     *                       a deterministic stub.
     * @return the resolved system-prompt string, or null to signal the caller should fall
     *         through to its existing FORAI-default path.
     */
    fun resolve(
        activePresetId: String,
        customPrompt: String,
        loadAsset: (String) -> String,
    ): String? {
        val preset = Tab4Preset.byId(activePresetId) ?: Tab4Preset.FORAI
        return when (preset) {
            Tab4Preset.FORAI -> null
            Tab4Preset.CUSTOM -> {
                val trimmed = customPrompt.trim()
                if (trimmed.isBlank()) null else customPrompt
            }
            else -> {
                val path = preset.assetPath
                    ?: error("Tab4Preset $preset has no assetPath; should be unreachable")
                loadAsset(path)
            }
        }
    }
}
