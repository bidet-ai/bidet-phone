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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-05-09 cap-thresholds test. Asserts the three thresholds sit in the strict ordering
 * the rest of the system depends on (visual warn < audible warn < hard cap), and that the
 * boundary `RecordingCapTimer.tick` returns the values the UI countdown will display
 * (40:00 → 300 sec remaining; 44:30 → 30; 44:59 → 1; 45:00 → 0).
 *
 * Why this exists: the constants are tuned in one place per spec, but a future tuning
 * could accidentally invert the order (e.g. set audible warn AFTER hard cap, which would
 * never trigger). This test fails fast if that happens.
 */
class RecordingCapsTest {

    @Test
    fun thresholdsAreStrictlyOrdered() {
        assertTrue(
            "WARN_VISUAL_MS must be strictly less than WARN_AUDIBLE_START_MS",
            RecordingCaps.WARN_VISUAL_MS < RecordingCaps.WARN_AUDIBLE_START_MS,
        )
        assertTrue(
            "WARN_AUDIBLE_START_MS must be strictly less than HARD_CAP_MS",
            RecordingCaps.WARN_AUDIBLE_START_MS < RecordingCaps.HARD_CAP_MS,
        )
    }

    @Test
    fun thresholdsMatchSpec() {
        // Spec literal values — defensively checked so a future "let's tweak the cap"
        // commit must update both the constants AND this test (and therefore the public
        // string resource that documents the cap to the user).
        assertEquals(45L * 60 * 1000, RecordingCaps.HARD_CAP_MS)
        assertEquals(40L * 60 * 1000, RecordingCaps.WARN_VISUAL_MS)
        assertEquals(44L * 60 * 1000 + 50 * 1000, RecordingCaps.WARN_AUDIBLE_START_MS)
    }

    @Test
    fun timer_below_visualWarn_reportsNoFlags() {
        val tick = RecordingCapTimer.tick(elapsedMs(0, 0))
        assertFalse(tick.hasReachedVisualWarn)
        assertFalse(tick.audibleBeepActive)
        assertFalse(tick.hasReachedHardCap)
    }

    @Test
    fun timer_at_40_00_setsVisualWarn_and_300_remaining() {
        val tick = RecordingCapTimer.tick(elapsedMs(40, 0))
        assertTrue("visual warn must be active at 40:00", tick.hasReachedVisualWarn)
        assertFalse("audible beep must NOT be active at 40:00", tick.audibleBeepActive)
        assertFalse("hard cap must NOT be reached at 40:00", tick.hasReachedHardCap)
        assertEquals(300, tick.remainingSeconds)
    }

    @Test
    fun timer_at_44_30_reports_30_remaining() {
        val tick = RecordingCapTimer.tick(elapsedMs(44, 30))
        assertTrue(tick.hasReachedVisualWarn)
        assertFalse(tick.audibleBeepActive)
        assertEquals(30, tick.remainingSeconds)
    }

    @Test
    fun timer_at_44_50_setsAudibleBeepActive() {
        val tick = RecordingCapTimer.tick(elapsedMs(44, 50))
        assertTrue(tick.hasReachedVisualWarn)
        assertTrue("audible beep must START at 44:50", tick.audibleBeepActive)
        assertFalse("hard cap must NOT be reached at 44:50", tick.hasReachedHardCap)
        assertEquals(10, tick.remainingSeconds)
    }

    @Test
    fun timer_at_44_59_reports_1_remaining_and_audibleBeepActive() {
        val tick = RecordingCapTimer.tick(elapsedMs(44, 59))
        assertTrue(tick.audibleBeepActive)
        assertFalse(tick.hasReachedHardCap)
        assertEquals(1, tick.remainingSeconds)
    }

    @Test
    fun timer_at_45_00_setsHardCap_andStopsAudibleBeep() {
        val tick = RecordingCapTimer.tick(elapsedMs(45, 0))
        assertTrue(tick.hasReachedVisualWarn)
        // Beep is open-on-the-right: at exactly HARD_CAP_MS the auto-stop fires and the
        // beep coroutine exits. If beep stayed active past hard cap, the post-stop teardown
        // could re-trigger it from a stale clock read.
        assertFalse(
            "audible beep must STOP at the same instant hard cap is reached",
            tick.audibleBeepActive,
        )
        assertTrue(tick.hasReachedHardCap)
        assertEquals(0, tick.remainingSeconds)
    }

    @Test
    fun timer_negative_elapsed_clampsToZero() {
        // Defensive: if `clock() - startedAtMs` ever goes negative (clock jumped back, or
        // tests pass an out-of-order pair), the watcher must not crash. remainingSeconds
        // should stay capped at the full HARD_CAP_MS / 1000.
        val tick = RecordingCapTimer.tick(-500L)
        assertFalse(tick.hasReachedVisualWarn)
        assertEquals(45 * 60, tick.remainingSeconds)
    }

    /** Build an elapsed-ms value from minutes + seconds for readability. */
    private fun elapsedMs(min: Int, sec: Int): Long = (min * 60L + sec) * 1000L
}
