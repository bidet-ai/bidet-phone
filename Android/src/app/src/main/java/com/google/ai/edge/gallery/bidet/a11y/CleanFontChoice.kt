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

package com.google.ai.edge.gallery.bidet.a11y

/**
 * The set of fonts a user can pick for Clean-tab cleaned-output text.
 *
 * **v0.3 (2026-05-10) — picker replaces the v0.2 single OpenDyslexic switch.**
 *
 * History:
 *  - v0.2 shipped a single boolean toggle "Use OpenDyslexic font for cleaned text" (default OFF).
 *  - v0.3 (this enum) replaces it with a 4-option radio picker. The default flips from "system
 *    default" to [ATKINSON_HYPERLEGIBLE] because the peer-reviewed evidence base for Atkinson is
 *    stronger than OpenDyslexic's (OpenDyslexic literature: Marinus 2016, Wery & Diliberto 2017,
 *    Kuster 2018 — mixed to negative; Atkinson was commissioned by the Braille Institute
 *    specifically for low-vision / reading-difference readers and has Fast Company 2019 +
 *    Cooper Hewitt 2024 design recognition). OpenDyslexic stays in the picker because some
 *    readers subjectively prefer it; that choice is respected.
 *  - Andika is bundled as the third option in place of Lexie Readable (originally requested):
 *    Lexie Readable's free license forbids redistribution by non-charitable / non-educational
 *    parties, so it cannot ship in a public-repo Apache-2.0 app. Andika is genuinely SIL OFL
 *    and shares Lexie's design goals (large x-height, generous spacing, single-storey a/g,
 *    non-symmetric b/d, designed by SIL for literacy and reading-difference readers).
 *
 * The picker applies to BOTH the Clean-for-me and Clean-for-others tabs. The RAW transcript
 * tab is intentionally NOT re-styled — verbatim text stays in the default app typography
 * regardless of the picker value.
 *
 * On-disk persistence:
 *  Stored as the enum constant's [storageKey] string in DataStore (see [A11yPreferences]).
 *  When parsing fails (corrupted preference, future enum value rolled back), the read defaults
 *  to [DEFAULT].
 */
enum class CleanFontChoice(val storageKey: String) {

    /**
     * Render Clean-tab text in the device's default body font (Material baseline, normally
     * Roboto on stock Android, but the system font if the user has overridden it). This option
     * defers fully to the user's existing Android accessibility preferences (font scale,
     * bold-text, etc.) and adds no extra glyph asset.
     */
    SYSTEM_DEFAULT("system_default"),

    /**
     * **Default** — Atkinson Hyperlegible (Braille Institute, SIL OFL). See
     * `third_party/atkinson_hyperlegible/README.md` for design rationale and license.
     */
    ATKINSON_HYPERLEGIBLE("atkinson_hyperlegible"),

    /**
     * OpenDyslexic (SIL OFL). See `third_party/opendyslexic/README.md`. Kept as an option
     * because some readers subjectively prefer the weighted-bottom letterforms even though the
     * RCT evidence is mixed.
     */
    OPEN_DYSLEXIC("open_dyslexic"),

    /**
     * Andika (SIL International, SIL OFL). See `third_party/andika/README.md`. Substituted for
     * Lexie Readable, which has a license incompatible with our redistribution model.
     */
    ANDIKA("andika"),
    ;

    companion object {
        /** Default the picker selects when the user has not chosen a font yet. */
        val DEFAULT: CleanFontChoice = ATKINSON_HYPERLEGIBLE

        /**
         * Parse a stored key back into an enum value. Any unrecognised key (older builds,
         * future builds rolled back, corrupted preference) falls back to [DEFAULT]; we never
         * crash the Clean-tab render path for a bad preference value.
         */
        fun fromStorageKey(key: String?): CleanFontChoice =
            values().firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}
