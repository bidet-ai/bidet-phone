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

import com.google.ai.edge.gallery.bidet.transcription.TranscriptionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * F2.1 (2026-05-09) regression test.
 *
 * Bug: [com.google.ai.edge.gallery.bidet.service.RecordingService.startRecording] used to
 * call `transcriptionEngine.initialize()` and discard the boolean return value. When init
 * failed (e.g. the Whisper model file was missing from the APK — exactly the bug Mark hit
 * on the flavor APKs on 2026-05-09), the audio capture loop still started and every chunk
 * fell through to the "[chunk N transcription failed]" marker path with no actionable signal.
 *
 * Fix: [EngineInitGate.tryInit] runs initialize() inside startRecording BEFORE
 * AudioCaptureEngine is constructed; on a false return it produces an [EngineInitGate.Outcome.InitFailed]
 * carrying a session row marked `notes=engine_init_failed` and the engine type.
 *
 * This test verifies:
 *  1. When `initialize()` returns false, the gate returns InitFailed (NOT Ready) — the
 *     contract that lets RecordingService skip the AudioCaptureEngine.start() call.
 *  2. The failure row carries `notes=engine_init_failed` AND `endedAtMs == startedAtMs == now`
 *     so it surfaces in History as a terminal-failure session, not an empty in-progress one.
 *  3. The engine's close() is called on the failure path so any partially-allocated native
 *     resources are released (the previous discard-the-return code path could leak).
 *  4. When `initialize()` returns true, the gate returns Ready and DOES NOT close the
 *     engine (caller still owns it).
 *  5. When `initialize()` THROWS, the gate treats it identically to a false return —
 *     defensive against engine impls that throw rather than return false.
 *
 * Why this is unit-tested at the gate layer: RecordingService extends android.app.Service
 * so direct instantiation in JVM unit tests requires Robolectric (which adds ~30s to CI).
 * The gate is a pure-Kotlin object; this test runs in the existing JUnit setup with no
 * extra deps.
 */
class EngineInitGateTest {

    private class FakeEngine(
        private val initResult: Boolean,
        private val throwOnInit: Boolean = false,
    ) : TranscriptionEngine {
        var initialized: Boolean = false
            private set
        var closed: Boolean = false
            private set
        override val isReady: Boolean get() = initialized

        override fun initialize(): Boolean {
            if (throwOnInit) throw IllegalStateException("simulated init failure")
            initialized = initResult
            return initResult
        }

        override fun transcribe(floatPcm: FloatArray, sampleRateHz: Int): String {
            fail("transcribe must not be called when init failed")
            throw IllegalStateException()
        }

        override fun close() {
            closed = true
        }
    }

    private val classifier: (TranscriptionEngine) -> RecordingService.EngineType = {
        // Tests don't care about Whisper vs Gemma — the gate's behaviour is identical for
        // both. Hard-code one to keep the test focused on the init-return path.
        RecordingService.EngineType.Whisper
    }

    @Test
    fun initFalse_returnsInitFailed_withFailureRowMarked() {
        val engine = FakeEngine(initResult = false)
        val now = 1_700_000_000_000L
        val sessionId = "test-session-init-false"

        val outcome = EngineInitGate.tryInit(
            engine = engine,
            sessionId = sessionId,
            now = now,
            classifyEngine = classifier,
        )

        // (1) Gate signaled InitFailed — RecordingService.startRecording will skip the
        //     AudioCaptureEngine.start() call on this path. This is the property the
        //     full-service test would verify; we verify the gate's contract here.
        assertTrue(
            "Expected Outcome.InitFailed when initialize() returns false; got $outcome",
            outcome is EngineInitGate.Outcome.InitFailed,
        )
        outcome as EngineInitGate.Outcome.InitFailed

        // (2) Failure row is correctly stamped — this is what shows up in History so the
        //     user sees "engine init failed" instead of an empty in-progress session.
        val row = outcome.failureRow
        assertEquals(sessionId, row.sessionId)
        assertEquals(RecordingService.ENGINE_INIT_FAILED_NOTE, row.notes)
        assertEquals(now, row.startedAtMs)
        assertEquals(
            "endedAtMs must equal startedAtMs so the row reads as a terminal failure, " +
                "not an in-progress recording that never finalizes.",
            now,
            row.endedAtMs,
        )
        assertEquals(0, row.durationSeconds)
        assertEquals("", row.rawText)
        assertEquals(0, row.chunkCount)

        // (3) Engine close() was called — releases any partial native allocation.
        assertTrue(
            "engine.close() must be called on the InitFailed path",
            engine.closed,
        )
    }

    @Test
    fun initTrue_returnsReady_doesNotClose() {
        val engine = FakeEngine(initResult = true)
        val outcome = EngineInitGate.tryInit(
            engine = engine,
            sessionId = "test-session-init-true",
            now = 1_700_000_000_000L,
            classifyEngine = classifier,
        )
        assertEquals(EngineInitGate.Outcome.Ready, outcome)
        assertTrue("Expected initialize() to be called and return true", engine.isReady)
        assertEquals(
            "Engine must NOT be closed on the Ready path — caller still owns it",
            false,
            engine.closed,
        )
    }

    @Test
    fun initThrows_isTreatedSameAsInitFalse() {
        val engine = FakeEngine(initResult = false, throwOnInit = true)
        val outcome = EngineInitGate.tryInit(
            engine = engine,
            sessionId = "test-session-init-throw",
            now = 1_700_000_000_000L,
            classifyEngine = classifier,
        )
        assertTrue(
            "A thrown initialize() must be treated identically to a false return",
            outcome is EngineInitGate.Outcome.InitFailed,
        )
        assertNotNull((outcome as EngineInitGate.Outcome.InitFailed).failureRow)
        assertTrue("engine.close() must still be called when initialize() threw", engine.closed)
    }

    @Test
    fun classifierResultIsCarriedThrough() {
        val engine = FakeEngine(initResult = false)
        val outcome = EngineInitGate.tryInit(
            engine = engine,
            sessionId = "test-session-engine-type",
            now = 0L,
            classifyEngine = { RecordingService.EngineType.Gemma },
        )
        outcome as EngineInitGate.Outcome.InitFailed
        assertEquals(RecordingService.EngineType.Gemma, outcome.engine)
    }
}
