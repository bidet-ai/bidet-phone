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

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-05-09 watcher-behavior test. Three concrete properties from the spec:
 *
 *  1. **Auto-stop at HARD_CAP_MS** — when elapsed crosses 45:00, the watcher invokes
 *     `Sink.onHardCapReached()` (which production-wires to `RecordingService.stopRecording()`).
 *  2. **Exactly 10 beeps in the last 10 seconds** — between 44:50 and 45:00 the watcher emits
 *     one beep per BEEP_INTERVAL_MS, then stops once hard-cap fires. The integration test for
 *     this asserts on count, not per-tick scheduling.
 *  3. **Early STOP cancels the beep coroutine before completion** — if `cancelAndJoin` is
 *     called partway through the beep cadence, the watcher stops emitting beeps and its
 *     `Sink.release()` finalizer runs.
 *
 * Why we use `runTest` + `StandardTestDispatcher` here:
 *  The watcher's only blocking operation is `kotlinx.coroutines.delay` — that respects the
 *  test scheduler's virtual clock, so `advanceTimeBy` drives the loop deterministically. No
 *  real wall-clock waits, no flakes. (Contrast with TranscriptionWorkerStopAndJoinTest, which
 *  exercises a `withContext(NonCancellable + Dispatchers.Default)` block that escapes the
 *  test scheduler — different problem, different tool.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingCapWatcherTest {

    /** Recording sink. Production swaps in [RecordingService.defaultCapSink]. */
    private class FakeSink : RecordingCapWatcher.Sink {
        val vibrateCount = AtomicInteger(0)
        val beepCount = AtomicInteger(0)
        val hardCapCount = AtomicInteger(0)
        val releaseCount = AtomicInteger(0)
        override fun vibrateOnce() { vibrateCount.incrementAndGet() }
        override fun beepOnce() { beepCount.incrementAndGet() }
        override fun onHardCapReached() { hardCapCount.incrementAndGet() }
        override fun release() { releaseCount.incrementAndGet() }
    }

    @Test
    fun crossing_HARD_CAP_invokes_onHardCapReached() = runTest(StandardTestDispatcher()) {
        val sink = FakeSink()
        // Simulate "the watcher's clock is already 46 minutes past startedAt" — i.e. the
        // very first iteration evaluates elapsedMs > HARD_CAP_MS, exercising the
        // "mock the elapsed-ms tick past 45:00, assert RecordingService.stop() gets called"
        // contract from the spec. The Sink.onHardCapReached is the production wire to
        // RecordingService.stopRecording().
        val watcher = RecordingCapWatcher(
            sink = sink,
            clock = { 46L * 60 * 1000 },
            pollIntervalMs = 100L,
        )
        val job = watcher.start(backgroundScope, startedAtMs = 0L)

        advanceUntilIdle()

        assertEquals(
            "onHardCapReached must fire exactly once when the watcher's first tick is past hard cap",
            1,
            sink.hardCapCount.get(),
        )
        assertTrue(job.isCompleted)
        assertEquals(
            "Sink.release() must run exactly once via the watcher's finally{} on cap-reached",
            1,
            sink.releaseCount.get(),
        )
    }

    @Test
    fun visual_warn_fires_exactly_once_on_rising_edge() = runTest(StandardTestDispatcher()) {
        val sink = FakeSink()
        // Test driver explicitly steps the simulated wall-clock alongside the test
        // dispatcher's virtual clock. We use a manual AtomicLong rather than reading
        // testScheduler.currentTime so this test compiles against any kotlinx-coroutines-test
        // version (1.8.x extension-property visibility quirks aside) — and the test stays
        // entirely in our control: every advanceTimeBy(N) is paired with an addAndGet(N).
        val virtualNow = java.util.concurrent.atomic.AtomicLong(0)
        val watcher = RecordingCapWatcher(
            sink = sink,
            clock = { virtualNow.get() },
            pollIntervalMs = 200L,
        )
        val job = watcher.start(
            scope = backgroundScope,
            startedAtMs = -(RecordingCaps.WARN_VISUAL_MS - 1),
        )

        // Pump the watcher to its first delay() suspension, without advancing virtual time.
        // runCurrent() is the right tool here: it runs everything scheduled at the *current*
        // virtual time but does NOT advance the clock — important because the watcher loop
        // is infinite (it only exits on cancel or hard-cap), and a bare advanceUntilIdle()
        // would step the virtual clock indefinitely while pumping the loop body.
        runCurrent()
        assertEquals(
            "Below WARN_VISUAL_MS the watcher must NOT vibrate.",
            0,
            sink.vibrateCount.get(),
        )

        // Cross the threshold. One 200 ms poll-interval tick is enough for the watcher to
        // re-read the clock and fire vibrateOnce on the rising edge.
        virtualNow.addAndGet(500)
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(
            "Crossing WARN_VISUAL_MS must produce exactly one vibrate on the rising edge.",
            1,
            sink.vibrateCount.get(),
        )

        // Hold (still in the visual-warn-but-not-audible window) for many more polling
        // ticks — vibrate must NOT re-fire (latched).
        virtualNow.addAndGet(5_000)
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(
            "vibrate must latch — only fires once on rising edge, not on every subsequent tick.",
            1,
            sink.vibrateCount.get(),
        )

        job.cancelAndJoin()
    }

    @Test
    fun ten_beeps_emitted_between_audibleStart_and_hardCap() = runTest(StandardTestDispatcher()) {
        val sink = FakeSink()
        // Step the watcher's simulated wall-clock alongside the test scheduler's virtual
        // clock. We use a manual AtomicLong rather than reading testScheduler.currentTime
        // because TestScope's currentTime/testScheduler accessors are extension properties
        // that don't always resolve cleanly inside a non-receiver lambda passed as a clock.
        // startedAt is anchored to (-WARN_AUDIBLE_START_MS) so virtualNow=0 evaluates to
        // elapsed=44:50:000 — the first iteration is the FIRST tick of the audible-warn
        // window.
        val virtualNow = java.util.concurrent.atomic.AtomicLong(0)
        val watcher = RecordingCapWatcher(
            sink = sink,
            clock = { virtualNow.get() },
            pollIntervalMs = 200L,
        )
        val job = watcher.start(
            scope = backgroundScope,
            startedAtMs = -RecordingCaps.WARN_AUDIBLE_START_MS,
        )

        // Step the simulated wall-clock + the dispatcher's virtual clock together,
        // BEEP_INTERVAL_MS at a time. After 10 increments we're at HARD_CAP_MS; the
        // watcher's next iteration breaks via onHardCapReached and terminates. The 100 ms
        // slop after the loop gives that final iteration room to read the (now hard-cap-
        // passed) clock and fire the cap.
        repeat(10) {
            virtualNow.addAndGet(RecordingCaps.BEEP_INTERVAL_MS)
            advanceTimeBy(RecordingCaps.BEEP_INTERVAL_MS)
        }
        advanceTimeBy(100)
        runCurrent()

        assertEquals(
            "Exactly 10 beeps must fire in the closed-open interval [44:50, 45:00).",
            10,
            sink.beepCount.get(),
        )
        assertEquals(
            "Hard-cap must fire exactly once at the 45:00 boundary, immediately after the 10th beep.",
            1,
            sink.hardCapCount.get(),
        )
        assertTrue("watcher must complete on hard-cap path", job.isCompleted)
    }

    @Test
    fun early_stop_cancels_beep_coroutine_before_10_beeps() = runTest(StandardTestDispatcher()) {
        val sink = FakeSink()
        val virtualNow = java.util.concurrent.atomic.AtomicLong(0)
        val watcher = RecordingCapWatcher(
            sink = sink,
            clock = { virtualNow.get() },
            pollIntervalMs = 200L,
        )
        val job = watcher.start(
            scope = backgroundScope,
            startedAtMs = -RecordingCaps.WARN_AUDIBLE_START_MS,
        )

        // Drive 3 BEEP_INTERVAL_MS ticks worth of simulated time — enough for several beeps
        // but well short of the hard-cap. We use runCurrent() (NOT advanceUntilIdle()) because
        // the watcher loops infinitely until cancel-or-hard-cap; advanceUntilIdle would
        // happily run all the way through to the 10th beep + hard-cap in this test,
        // defeating the "stopped early" contract.
        repeat(3) {
            virtualNow.addAndGet(RecordingCaps.BEEP_INTERVAL_MS)
            advanceTimeBy(RecordingCaps.BEEP_INTERVAL_MS)
        }
        runCurrent()
        val beepsBeforeCancel = sink.beepCount.get()
        assertTrue(
            "Sanity check: the watcher must have emitted at least one beep before we cancel.",
            beepsBeforeCancel >= 1,
        )
        assertTrue(
            "Sanity check: cancel timing must precede the 10th beep so we can verify the cancel actually shortened the cadence.",
            beepsBeforeCancel < 10,
        )

        job.cancelAndJoin()

        // After cancel, advancing the clock further must NOT produce more beeps. This is
        // the spec contract: "make sure beeps STOP if the user taps STOP early". advanceUntilIdle
        // is safe here — the cancelled job has no pending tasks, so it returns immediately.
        virtualNow.addAndGet(20 * RecordingCaps.BEEP_INTERVAL_MS)
        advanceTimeBy(20 * RecordingCaps.BEEP_INTERVAL_MS)
        advanceUntilIdle()

        assertEquals(
            "Beep count must be unchanged after cancelAndJoin — the cancelled coroutine must not emit further beeps even as the simulated clock continues past hard-cap.",
            beepsBeforeCancel,
            sink.beepCount.get(),
        )
        assertEquals(
            "An early-cancelled watcher must NOT call onHardCapReached — the user's stop is the source of truth, not the watcher's.",
            0,
            sink.hardCapCount.get(),
        )
        assertEquals(
            "Sink.release() must run exactly once via the watcher's finally{} on cancel.",
            1,
            sink.releaseCount.get(),
        )
        assertTrue(job.isCancelled)
    }

    @Test
    fun cancel_below_visualWarn_emits_no_beeps_no_vibrates() = runTest(StandardTestDispatcher()) {
        val sink = FakeSink()
        // Common case: a normal short session ends well before 40:00. The watcher must
        // run + cancel cleanly with zero side-effects. We simulate "5 minutes elapsed" by
        // anchoring startedAt to -5min so virtualNow=0 = elapsed-5min, well below the
        // WARN_VISUAL_MS threshold so the watcher just polls without firing anything.
        val virtualNow = java.util.concurrent.atomic.AtomicLong(0)
        val watcher = RecordingCapWatcher(
            sink = sink,
            clock = { virtualNow.get() },
            pollIntervalMs = 100L,
        )
        val job = watcher.start(
            scope = backgroundScope,
            startedAtMs = -(5L * 60 * 1000),
        )

        virtualNow.addAndGet(10_000)
        advanceTimeBy(10_000L)
        runCurrent()
        job.cancelAndJoin()

        assertEquals(0, sink.vibrateCount.get())
        assertEquals(0, sink.beepCount.get())
        assertEquals(0, sink.hardCapCount.get())
        assertEquals(
            "Sink.release() must always run on cancel — even if no side-effect ever fired.",
            1,
            sink.releaseCount.get(),
        )
    }

    @Test
    fun visual_countdown_remaining_seconds_at_boundaries() {
        // Direct boundary check — the same arithmetic that drives the RecordingHeader
        // composable's "X:XX remaining" subtitle. Asserts the spec's four named values:
        //   40:00 → 300, 44:30 → 30, 44:59 → 1, 45:00 → 0.
        // RecordingCapsTest covers the broader timer behavior; this test is the explicit
        // mapping the spec called out.
        assertEquals(300, RecordingCapTimer.tick(40L * 60 * 1000).remainingSeconds)
        assertEquals(30, RecordingCapTimer.tick(44L * 60 * 1000 + 30 * 1000).remainingSeconds)
        assertEquals(1, RecordingCapTimer.tick(44L * 60 * 1000 + 59 * 1000).remainingSeconds)
        assertEquals(0, RecordingCapTimer.tick(45L * 60 * 1000).remainingSeconds)
    }

    @Test
    fun no_visual_warn_below_threshold_for_remaining_seconds_check() {
        // Below WARN_VISUAL_MS the header keeps the countdown hidden. The remainingSeconds
        // value is still computed (it's a function of elapsed time, not a flag) — this test
        // just guards against a future refactor that conflates the two and makes the
        // countdown blink on at 0:00 elapsed.
        val tick = RecordingCapTimer.tick(0L)
        assertFalse(tick.hasReachedVisualWarn)
        assertEquals(45 * 60, tick.remainingSeconds)
    }
}
