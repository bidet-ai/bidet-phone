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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives the side-effects for [RecordingCaps] (haptic at WARN_VISUAL_MS, beeps from
 * WARN_AUDIBLE_START_MS, auto-stop at HARD_CAP_MS). Decouples the side-effects from the
 * Service lifecycle so tests can supply a fake [Sink] and a controllable clock.
 *
 * The watcher is a thin loop over [RecordingCapTimer.tick] — the threshold arithmetic
 * lives in the timer; this class only owns the latching ("vibrate ONCE on the rising
 * edge") and the cancellation contract ("an early stopRecording cancels the beep
 * coroutine before completion").
 */
class RecordingCapWatcher(
    private val sink: Sink,
    private val clock: () -> Long,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {

    /**
     * Side-effects the watcher invokes. Production: [RecordingService] supplies an impl
     * backed by Vibrator + ToneGenerator + this.stopRecording(). Tests supply a fake.
     */
    interface Sink {
        /** Emit a single short haptic buzz at the visual-warn threshold. */
        fun vibrateOnce()

        /** Emit a single short audible beep — called once per [RecordingCaps.BEEP_INTERVAL_MS]. */
        fun beepOnce()

        /** Trigger the same teardown path as a user STOP tap. */
        fun onHardCapReached()

        /**
         * Always invoked when the watcher coroutine exits (cancel OR hard-cap-reached). The
         * production impl uses this to release the lazily-allocated ToneGenerator handle so
         * an early STOP doesn't leak it. Default impl is a no-op for tests that don't care.
         */
        fun release() {}
    }

    /**
     * Launch the cap-watcher coroutine on [scope]. Returns the [Job] so the caller can
     * cancel it on stopRecording (the beeps must STOP if the user taps STOP early — see
     * spec). The loop runs until cancelled OR until [Sink.onHardCapReached] is called.
     */
    fun start(scope: CoroutineScope, startedAtMs: Long): Job = scope.launch {
        var visualWarnFired = false
        var hardCapFired = false
        try {
            while (isActive && !hardCapFired) {
                ensureActive()
                val elapsedMs = clock() - startedAtMs
                val tick = RecordingCapTimer.tick(elapsedMs)

                if (tick.hasReachedVisualWarn && !visualWarnFired) {
                    visualWarnFired = true
                    sink.vibrateOnce()
                }

                if (tick.audibleBeepActive) {
                    sink.beepOnce()
                    delay(RecordingCaps.BEEP_INTERVAL_MS)
                    continue
                }

                if (tick.hasReachedHardCap) {
                    hardCapFired = true
                    sink.onHardCapReached()
                    break
                }

                delay(pollIntervalMs)
            }
        } finally {
            sink.release()
        }
    }

    companion object {
        /**
         * Pre-warning poll cadence. Coarse (500 ms) is fine — the only side-effect before
         * 40:00 is reaching the visual warn threshold, which is latency-tolerant by ~half a
         * second. Beeping switches to its own 1 s cadence once active.
         */
        const val DEFAULT_POLL_INTERVAL_MS: Long = 500L
    }
}
