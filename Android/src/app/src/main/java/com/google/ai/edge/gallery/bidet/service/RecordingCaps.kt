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

package com.google.ai.edge.gallery.bidet.service

/**
 * Recording-time cap thresholds.
 *
 * Mark dictated a 31-minute brain dump on 2026-05-09; without a cap an unattended phone
 * could run the AudioRecord pipeline indefinitely (mic hot, battery drain, eMMC writes).
 * The cap is set above his typical session length but with explicit warnings so he can
 * wrap up rather than be cut off mid-thought.
 *
 * Tuning this is one-line per knob — every threshold is sourced from here.
 */
object RecordingCaps {
    /** Hard cap. RecordingService auto-stops at this elapsed time, same path as user STOP. */
    const val HARD_CAP_MS: Long = 45L * 60 * 1000

    /** First warning: visible countdown appears in RecordingHeader + one short haptic buzz. */
    const val WARN_VISUAL_MS: Long = 40L * 60 * 1000

    /** Second warning: ToneGenerator emits one beep per second from here through HARD_CAP_MS. */
    const val WARN_AUDIBLE_START_MS: Long = 44L * 60 * 1000 + 50 * 1000

    /** Single haptic-buzz length at the visual-warning threshold. */
    const val VISUAL_WARN_VIBRATE_MS: Long = 100L

    /** Beep cadence (one tone per second between WARN_AUDIBLE_START_MS and HARD_CAP_MS). */
    const val BEEP_INTERVAL_MS: Long = 1_000L
}
