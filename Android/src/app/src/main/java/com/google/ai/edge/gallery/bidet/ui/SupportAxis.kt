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
 * Three-tab restructure (v20, 2026-05-11): RAW is the always-visible reading base, with
 * THREE GENERATED tabs below it. SupportAxis is retained because the service layer
 * ([com.google.ai.edge.gallery.bidet.service.CleanGenerationService]) uses it to pick a
 * notification text and to keep the streams distinguishable when tabs generate concurrently.
 *
 * Slot mapping:
 *  - SLOT 0  →  RECEPTIVE  ("Clean for me" by default — output FOR the speaker)
 *  - SLOT 1  →  EXPRESSIVE ("Clean for others" by default — output FROM the speaker)
 *  - SLOT 2  →  JUDGES     ("Clean for judges" — contest-pitch output mode; v20)
 *
 * Why JUDGES: the Kaggle Gemma 4 Good Hackathon writeup (deadline 2026-05-18) is one of the
 * two prize-determining artifacts (video + writeup). Mark's brain dumps into Clean for me /
 * Clean for others don't produce a contest writeup; the third tab does. The web + desktop
 * Bidets shipped this 2026-05-11; phone is the last surface to gain it.
 *
 * Per the two-tab spec (preserved for v20), label AND prompt are user-editable per axis
 * (via [TabPrefRepository]). Internally we keep the historical Receptive/Expressive names
 * + add JUDGES so the service contract doesn't churn for the existing two streams.
 */
enum class SupportAxis {
    /** Slot 0 — output FOR the speaker. Default tab name: "Clean for me". */
    RECEPTIVE,
    /** Slot 1 — output FROM the speaker. Default tab name: "Clean for others". */
    EXPRESSIVE,
    /**
     * Slot 2 — contest-pitch output. Default tab name: "Clean for judges". v20 (2026-05-11):
     * built for the Kaggle Gemma 4 Good Hackathon writeup. Output target is ~800-1200 words
     * with a tagline pin + on-device claim — longer than the other two axes, which is the
     * Tensor G3 decode-budget tradeoff documented in the v20 PR body.
     */
    JUDGES;

    /**
     * Display position of this axis in the tab-chip row. The order is locked: slot 0 left,
     * slot 2 right. Renames don't change position.
     */
    val slotIndex: Int
        get() = when (this) {
            RECEPTIVE -> 0
            EXPRESSIVE -> 1
            JUDGES -> 2
        }

    companion object {
        /** Iteration order matches the chip-row order — slot 0 first. */
        val ALL: List<SupportAxis> = listOf(RECEPTIVE, EXPRESSIVE, JUDGES)

        fun fromSlotIndex(slot: Int): SupportAxis? = ALL.getOrNull(slot)
    }
}
