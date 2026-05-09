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

package com.google.ai.edge.gallery.bidet.transcription

import android.util.Log
import com.google.ai.edge.gallery.bidet.chunk.Chunk
import com.google.ai.edge.gallery.bidet.chunk.ChunkQueue
import com.google.ai.edge.gallery.bidet.transcript.TranscriptAggregator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single-threaded coroutine consumer of [ChunkQueue].
 *
 * Behaviour per brief §4:
 *  - Reads from [chunkQueue.asFlow()] one chunk at a time.
 *  - For [Chunk.Audio]: convert int16 PCM → Float32, hand to [WhisperEngine.transcribe], then
 *    [TranscriptAggregator.append] the result.
 *  - For [Chunk.MarkerLost]: emit a failure marker to the aggregator (we lost audio for that
 *    index and can't transcribe it).
 *  - On any per-chunk exception: log + appendFailureMarker, then continue (a single bad chunk
 *    must not break the entire session).
 *  - Never starts a second [transcribe] while one is in flight — we collect serially.
 */
class TranscriptionWorker(
    private val chunkQueue: ChunkQueue,
    private val transcriptionEngine: TranscriptionEngine,
    private val aggregator: TranscriptAggregator,
    private val scope: CoroutineScope,
) {

    private var job: Job? = null

    /**
     * Spawn the consumer coroutine. Idempotent — second calls return `true` without spawning
     * a second job (engine state is unchanged from the original spawn).
     *
     * F2.1 (2026-05-09): returns `false` when the engine cannot be initialized. Previously
     * we tried `transcriptionEngine.initialize()` and discarded the result, then started the
     * consumer loop anyway — every chunk fell through to the failure-marker path and the
     * user saw "[chunk N transcription failed]" forever. Now the worker refuses to start
     * and the caller (RecordingService) tears the recording down with an actionable
     * engine-init-failed banner.
     *
     * @return `true` if the consumer is now running (or was already running), `false` if
     *   the engine could not be brought to a ready state and no job was spawned.
     */
    fun start(): Boolean {
        if (job != null) return true
        // Make sure the engine is ready before we begin consuming. If init returns false the
        // engine is unusable — refuse to spawn the consumer rather than spinning a loop that
        // turns every chunk into a failure marker.
        if (!transcriptionEngine.isReady && !transcriptionEngine.initialize()) {
            Log.e(TAG, "transcriptionEngine.initialize() returned false — refusing to start consumer.")
            return false
        }

        job = scope.launch {
            // Merge the audio flow with the markers flow so dropped chunks also get a marker.
            val merged = merge(chunkQueue.asFlow(), chunkQueue.markersFlow())
            merged.collect { chunk ->
                try {
                    when (chunk) {
                        is Chunk.Audio -> handleAudio(chunk)
                        is Chunk.MarkerLost -> aggregator.appendFailureMarker(chunk.idx)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "chunk ${chunk.idx} failed: ${t.message}", t)
                    aggregator.appendFailureMarker(chunk.idx)
                } finally {
                    chunkQueue.acknowledgeProcessed()
                }
            }
        }
        return true
    }

    /**
     * Cancel the consumer coroutine without waiting.
     *
     * F1.1 (2026-05-09): callers that need to tear down native engine resources after
     * cancellation must use [stopAndJoin] instead — this `stop()` only schedules
     * cancellation. Closing the engine before an in-flight transcribe finishes was the
     * use-after-free that motivated the F1.1 fix. `stop()` is preserved for paths that
     * don't subsequently touch native resources.
     */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * F1.1 fix (2026-05-09): cancel the consumer AND await completion.
     *
     * The bug we're fixing:
     *  - [com.google.ai.edge.gallery.bidet.service.RecordingService.stopRecording] used to
     *    do `worker.stop()` + `transcriptionEngine.close()` back-to-back. `worker.stop()`
     *    only *cancels* the consumer's [Job]; it does not *join* it. Meanwhile
     *    [handleAudio] wraps its [TranscriptionEngine.transcribe] call in
     *    `withContext(NonCancellable + Dispatchers.Default)`, so a transcribe that started
     *    before Stop keeps running. The next line — `transcriptionEngine.close()` — yanks
     *    the native pointer out from under the still-running whisper.cpp / LiteRT-LM
     *    call, classic use-after-free. On a real device the failure mode is "user taps
     *    Stop mid-transcribe → app dies + ANR". Bad demo crash.
     *
     * Fix: this method cancels the consumer's outer collect (so we don't pick up new
     * chunks) AND awaits the in-flight handleAudio() to finish via [Job.join]. Once join
     * returns, the caller can safely close the engine knowing nothing is touching it.
     *
     * Note on NonCancellable: handleAudio's `withContext(NonCancellable + Dispatchers.Default)`
     * is what makes the join *useful* — without NonCancellable the cancel would propagate
     * into the transcribe call and surface as a CancellationException; with NonCancellable
     * the transcribe runs to completion, the result is appended to the aggregator, and
     * only then does the join return. Net effect: a Stop tap during transcribe yields the
     * chunk's text in the saved session rather than "[chunk N transcription failed]" + a
     * crash.
     */
    suspend fun stopAndJoin() {
        val j = job ?: return
        j.cancel()
        try {
            j.join()
        } catch (_: Throwable) {
            // join() doesn't throw on a normally-completed or cancelled job, but a parent
            // scope cancellation can race here. Either way the consumer is no longer
            // collecting; the caller's contract — "after this returns, nothing is in
            // transcribe()" — holds.
        } finally {
            job = null
        }
    }

    private suspend fun handleAudio(chunk: Chunk.Audio) {
        // 2026-05-09: wrap in NonCancellable so that when the user taps Stop, an
        // already-in-flight whisper_full call (which can take a few seconds even
        // optimized) gets to finish and write its result to the aggregator/DB.
        // Before this, stop → scope.cancel() → JobCancellationException mid-transcribe
        // → empty rawText. With NonCancellable the transcribe and the aggregator
        // append both complete; only the *next* chunk would be skipped.
        //
        // Heavy lifting (Whisper) is CPU-bound — push onto Dispatchers.Default so we
        // don't compete with the audio capture loop for a single thread.
        val text = withContext(NonCancellable + Dispatchers.Default) {
            val floatPcm = transcriptionEngine.int16ToFloat32(chunk.bytes)
            transcriptionEngine.transcribe(floatPcm, sampleRateHz = AudioCaptureSampleRate)
        }
        withContext(NonCancellable) {
            aggregator.append(idx = chunk.idx, startMs = chunk.startMs, text = text)
        }
    }

    companion object {
        private const val TAG = "BidetTranscribeWorker"

        /**
         * Sample rate guarantee from [com.google.ai.edge.gallery.bidet.audio.AudioCaptureEngine].
         * Whisper requires 16 kHz; pinned here for clarity.
         */
        const val AudioCaptureSampleRate: Int = 16_000
    }
}
