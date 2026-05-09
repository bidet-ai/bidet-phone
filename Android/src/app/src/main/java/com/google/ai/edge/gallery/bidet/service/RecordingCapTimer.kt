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
 * Pure-Kotlin decision helper for the recording-time cap. Extracted so the threshold
 * arithmetic can be unit-tested in plain JVM JUnit without booting a Service or running
 * Robolectric.
 *
 * The decision is ENTIRELY a function of `elapsedMs` — the helper is stateless and idempotent
 * within a single call. The caller (RecordingService) owns the side-effects (vibrate, beep,
 * stop) and the once-per-threshold latching.
 */
object RecordingCapTimer {

    /**
     * Computed at each tick. The caller looks at the four flags + `remainingSeconds` and
     * fires the appropriate side-effects (vibrate the once on hasReachedVisualWarn rising
     * edge, drive beeps while audibleBeepActive is true, call stopRecording on hasReachedHardCap).
     *
     * `remainingSeconds` is what the RecordingHeader composable shows under the timer.
     * Computed against HARD_CAP_MS, clamped at 0; unused below WARN_VISUAL_MS (the UI keeps
     * it hidden until the visual-warn flag flips).
     */
    data class Tick(
        val hasReachedVisualWarn: Boolean,
        val audibleBeepActive: Boolean,
        val hasReachedHardCap: Boolean,
        val remainingSeconds: Int,
    )

    /**
     * Compute the cap-state for an elapsed time. Boundaries are inclusive on the lower side
     * (40:00 → visual warn is on; 44:50 → beep is on; 45:00 → hard cap is on).
     */
    fun tick(elapsedMs: Long): Tick {
        val capped = elapsedMs.coerceAtLeast(0L)
        val remainingMs = (RecordingCaps.HARD_CAP_MS - capped).coerceAtLeast(0L)
        val remainingSec = ((remainingMs + 999L) / 1000L).toInt()
        return Tick(
            hasReachedVisualWarn = capped >= RecordingCaps.WARN_VISUAL_MS,
            audibleBeepActive = capped >= RecordingCaps.WARN_AUDIBLE_START_MS &&
                capped < RecordingCaps.HARD_CAP_MS,
            hasReachedHardCap = capped >= RecordingCaps.HARD_CAP_MS,
            remainingSeconds = remainingSec,
        )
    }
}
