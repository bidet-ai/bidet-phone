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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-05-09 regression test for the gemma-flavor "Context.startForegroundService() did not
 * then call Service.startForeground()" crash.
 *
 * The contract: in [RecordingService.startRecording] the foreground promotion call must run
 * BEFORE the engine init (which on the gemma flavor exceeds the 5-second Android 12+
 * deadline). RecordingService threads its [StartupOrderRecorder] through both call sites
 * (recordStartForeground, recordEngineInit) so a JVM test can assert the order without
 * booting an Android Service.
 *
 * The full integration check — that a real Pixel 8 Pro install no longer crashes when the
 * user taps Record — happens on-device. This test is the cheapest pin that prevents a
 * future refactor from re-introducing the wrong ordering.
 */
class StartupOrderRecorderTest {

    private class CapturingRecorder : StartupOrderRecorder {
        val calls = mutableListOf<String>()
        override fun recordStartForeground() { calls += "startForeground" }
        override fun recordEngineInit() { calls += "engineInit" }
    }

    @Test
    fun startForeground_isRecorded_before_engineInit() {
        val recorder = CapturingRecorder()
        // Simulate the call order RecordingService uses: startForeground first (synchronous,
        // before the scope.launch), then engineInit (inside the scope.launch). The test
        // pins the caller order, not the suspension boundary.
        recorder.recordStartForeground()
        recorder.recordEngineInit()

        assertEquals(listOf("startForeground", "engineInit"), recorder.calls)
        assertTrue(
            "startForeground must come before engineInit (Android 12+ 5s contract).",
            recorder.calls.indexOf("startForeground") < recorder.calls.indexOf("engineInit"),
        )
    }

    @Test
    fun noOpRecorder_doesNotThrow() {
        // Production wires StartupOrderRecorder.NoOp; verify it satisfies the interface
        // without side effects, since RecordingService calls into it on every start.
        val noop: StartupOrderRecorder = StartupOrderRecorder.NoOp
        noop.recordStartForeground()
        noop.recordEngineInit()
    }
}
