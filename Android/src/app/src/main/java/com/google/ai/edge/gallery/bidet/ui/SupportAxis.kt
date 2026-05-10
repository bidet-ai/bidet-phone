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
 * Two-tab restructure (2026-05-10): RAW is no longer a tab — it lives at the top of the
 * screen as a reading base, with two GENERATED tabs below it. SupportAxis is retained
 * because the service layer ([com.google.ai.edge.gallery.bidet.service.CleanGenerationService])
 * uses it to pick a notification text and to keep the two streams distinguishable when both
 * tabs generate concurrently.
 *
 * Slot mapping:
 *  - SLOT 0  →  RECEPTIVE  ("Clean for me" by default — output FOR the speaker)
 *  - SLOT 1  →  EXPRESSIVE ("Clean for others" by default — output FROM the speaker)
 *
 * Per the two-tab spec, both label AND prompt are user-editable (via [TabPrefRepository]).
 * Internally we still call them Receptive/Expressive so the service contract doesn't churn.
 */
enum class SupportAxis {
    /** Slot 0 — output FOR the speaker. Default tab name: "Clean for me". */
    RECEPTIVE,
    /** Slot 1 — output FROM the speaker. Default tab name: "Clean for others". */
    EXPRESSIVE;

    /**
     * Display position of this axis in the tab-chip row. The order is locked: slot 0 left,
     * slot 1 right. Renames don't change position.
     */
    val slotIndex: Int
        get() = when (this) {
            RECEPTIVE -> 0
            EXPRESSIVE -> 1
        }

    companion object {
        /** Iteration order matches the chip-row order — slot 0 first. */
        val ALL: List<SupportAxis> = listOf(RECEPTIVE, EXPRESSIVE)

        fun fromSlotIndex(slot: Int): SupportAxis? = ALL.getOrNull(slot)
    }
}
