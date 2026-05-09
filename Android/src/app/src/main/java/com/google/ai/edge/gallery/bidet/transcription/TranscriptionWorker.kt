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
    private val whisperEngine: WhisperEngine,
    private val aggregator: TranscriptAggregator,
    private val scope: CoroutineScope,
) {

    private var job: Job? = null

    /** Spawn the consumer coroutine. Idempotent — second calls return without spawning. */
    fun start() {
        if (job != null) return
        // Make sure the engine is ready before we begin consuming. If init fails, every chunk
        // will fall through to the failure-marker path; we still want the loop to run so the
        // aggregator's RAW transcript reflects what was attempted.
        if (!whisperEngine.isReady) whisperEngine.initialize()

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
    }

    /** Cancel the consumer coroutine. */
    fun stop() {
        job?.cancel()
        job = null
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
            val floatPcm = whisperEngine.int16ToFloat32(chunk.bytes)
            whisperEngine.transcribe(floatPcm, sampleRateHz = AudioCaptureSampleRate)
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
