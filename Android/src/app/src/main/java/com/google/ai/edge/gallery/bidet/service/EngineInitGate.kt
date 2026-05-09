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

import com.google.ai.edge.gallery.bidet.data.BidetSession
import com.google.ai.edge.gallery.bidet.data.BidetSessionDao
import com.google.ai.edge.gallery.bidet.transcription.TranscriptionEngine

/**
 * F2.1 (2026-05-09): pure decision helper extracted from [RecordingService.startRecording] so
 * the "engine init failed → persist a marker row, refuse to start the capture pipeline"
 * decision can be unit-tested without booting a real Android Service.
 *
 * Why this exists:
 *  - Before: `RecordingService.startRecording()` did
 *      `val engine = TranscriptionEngine.create(ctx).also { it.initialize() }`
 *    The boolean return from `initialize()` was discarded. When init failed (e.g. the user
 *    installed a flavor APK without the bundled Whisper model — exactly the bug Mark hit on
 *    2026-05-09), `AudioCaptureEngine` was started anyway, every chunk fell through to the
 *    "[chunk N transcription failed]" marker path, and the user had no actionable signal.
 *  - After: this gate (called by `startRecording` BEFORE `AudioCaptureEngine.start()`)
 *    captures the boolean. On false: returns [Outcome.InitFailed] with the failure-row to
 *    persist + the engine type. The caller (RecordingService) launches the persistence on
 *    its own service scope; this keeps the gate a pure synchronous decision and easy to
 *    unit-test without coroutine machinery.
 *
 * The gate is engine-agnostic — both [com.google.ai.edge.gallery.bidet.transcription.WhisperEngine]
 * and [com.google.ai.edge.gallery.bidet.transcription.GemmaAudioEngine] satisfy it. The
 * [classifyEngine] callback exists only so the caller can map class → enum without forcing
 * this file to import both impls (keeping the dependency graph one-way: gate → interface,
 * not gate → impls).
 */
object EngineInitGate {

    /** Outcome of [tryInit]. */
    sealed interface Outcome {
        /** Engine initialized successfully; caller may proceed with the capture pipeline. */
        object Ready : Outcome

        /**
         * Engine init returned false (or threw). [closeEngine] has already been called to
         * release any partial native allocation. The caller should:
         *  1. Persist [failureRow] (caller controls the dispatcher so we don't force a
         *     specific coroutine context on this helper).
         *  2. Emit a `Status(engineInitError=...)` so the UI surfaces an actionable banner.
         *  3. NOT start [com.google.ai.edge.gallery.bidet.audio.AudioCaptureEngine].
         */
        data class InitFailed(
            val engine: RecordingService.EngineType,
            val failureRow: BidetSession,
        ) : Outcome
    }

    /**
     * Call [TranscriptionEngine.initialize] and decide whether the caller should proceed.
     *
     *  - On success: returns [Outcome.Ready]. The caller proceeds to start the capture
     *    pipeline.
     *  - On failure (false return OR thrown exception): closes the engine to release any
     *    partial native allocation, builds a [BidetSession] row stamped
     *    [RecordingService.ENGINE_INIT_FAILED_NOTE], and returns [Outcome.InitFailed]. The
     *    caller persists the row on its own coroutine scope.
     *
     * @param engine the engine instance to initialize.
     * @param sessionId the UUID the caller would have used for the (now-aborted) session;
     *   stored on the failure row so History entries align with the user's intent.
     * @param now milliseconds since epoch — caller-injected for test determinism.
     * @param classifyEngine maps the engine instance to [RecordingService.EngineType] so
     *   the caller can pick UI copy. See [RecordingService.engineTypeOf].
     */
    fun tryInit(
        engine: TranscriptionEngine,
        sessionId: String,
        now: Long,
        classifyEngine: (TranscriptionEngine) -> RecordingService.EngineType,
    ): Outcome {
        val ok = try {
            engine.initialize()
        } catch (_: Throwable) {
            // Some engine impls might throw rather than return false (defensive). We treat
            // a thrown initialize() identically to a false return — both mean "do not start
            // the capture pipeline".
            false
        }
        if (ok) return Outcome.Ready

        val type = classifyEngine(engine)
        try { engine.close() } catch (_: Throwable) { /* ignore */ }
        val row = BidetSession(
            sessionId = sessionId,
            startedAtMs = now,
            endedAtMs = now,
            durationSeconds = 0,
            rawText = "",
            chunkCount = 0,
            notes = RecordingService.ENGINE_INIT_FAILED_NOTE,
        )
        return Outcome.InitFailed(engine = type, failureRow = row)
    }
}
