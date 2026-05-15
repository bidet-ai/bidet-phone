/*
 * Copyright 2026 bidet-ai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.bidet.cleaning

import android.util.Log
import com.google.ai.edge.gallery.bidet.ui.BidetGemmaClient
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Path B (2026-05-10): pre-clean each transcribed chunk during recording so by the time
 * the user taps **Clean for me**, most of the on-device Gemma inference is already done
 * and the tap-to-result wait collapses from minutes to seconds.
 *
 * Architecture
 * ------------
 *  - One [ChunkCleaner] per recording session, owned by
 *    [com.google.ai.edge.gallery.bidet.service.RecordingService].
 *  - [TranscriptAggregator]'s new `onChunkAppended(idx, chunkText)` callback enqueues
 *    a task here.
 *  - A single worker coroutine drains the channel serially — Gemma's LiteRT-LM engine
 *    is not safe to call concurrently, and on Tensor G3 CPU we couldn't get useful
 *    parallelism anyway.
 *  - Per-chunk result is written to `<sessionExternalDir>/cleanings/<idx>_<axis>.txt`.
 *  - When the recording stops, [drainAndStop] waits for the queue to empty (with a hard
 *    timeout) before letting the service tear down.
 *
 * Trade-offs
 * ----------
 *  - One axis only for v1 — RECEPTIVE (Clean for me). EXPRESSIVE keeps the lazy path.
 *    Doubles inference cost otherwise and we want to measure the win first.
 *  - Per-chunk cleaning loses cross-chunk coherence. The existing chunked cleaning path
 *    has the same trade so this isn't a regression. Mark's Regenerate button still runs
 *    the full-context path if cross-chunk coherence matters for a specific dump.
 *  - Per-chunk Gemma inference competes for CPU with sherpa-onnx STT during recording.
 *    Empirically a 30-sec chunk's STT is ~3-5 sec and cleaning is ~10 sec on E4B — they
 *    happen in series here, so the cleaner can fall behind on long sessions and finish
 *    a bit after stop. Acceptable: we only need to beat the chunked-on-tap path which
 *    takes minutes.
 */
class ChunkCleaner(
    private val sessionExternalDir: File,
    private val gemma: BidetGemmaClient,
    private val cleanForMePrompt: String,
    private val maxOutputTokens: Int = DEFAULT_PER_CHUNK_OUTPUT_TOKENS,
    private val temperature: Float = DEFAULT_TEMPERATURE,
) {

    /**
     * Owned scope so cleanings outlive RecordingService.scope.cancel() — if the user stops
     * recording with chunks still in the queue, we want those cleanings to finish writing
     * to disk rather than getting cancelled mid-decode (which would waste the work AND
     * leave the on-tap path with nothing usable). The service that owns this cleaner
     * should still call [drainAndStop] on stop so the queue closes, but the worker
     * coroutine itself isn't tied to the service's scope lifetime.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Per-chunk task: (idx, chunkText). Axis is fixed to RECEPTIVE for v1. */
    private data class CleanTask(val idx: Int, val chunkText: String)

    // Unbounded channel — chunks arrive at ~30 sec cadence; even an hour-long dump is
    // only ~120 chunks. No backpressure concerns at this scale.
    private val queue = Channel<CleanTask>(capacity = Channel.UNLIMITED)
    private var worker: Job? = null

    /** Start the worker coroutine. Idempotent. */
    fun start() {
        if (worker != null) return
        val cleaningsDir = File(sessionExternalDir, CLEANINGS_DIR)
        cleaningsDir.mkdirs()
        worker = scope.launch {
            for (task in queue) {
                runCatching {
                    val outFile = File(cleaningsDir, "${task.idx}_${RECEPTIVE_SUFFIX}.txt")
                    if (outFile.exists() && outFile.length() > 0L) {
                        // Already cleaned (resume / re-enqueue). Skip the model call.
                        Log.i(TAG, "skip idx=${task.idx} — already cleaned")
                        return@runCatching
                    }
                    val startMs = System.currentTimeMillis()
                    // Belt-and-suspenders: a stuck STT buffer or a late-cut Moonshine
                    // window can emit a very long chunk text (saw 4-5k chars on a Pixel 8
                    // VAD glitch 2026-05-09). Route through the same RawChunker the on-
                    // tap path uses so we never feed the engine more than one safe window
                    // at a time. For the common case (300-600 chars per ~30s audio) this
                    // is a no-op fast path: chunkForPrompt returns a single-element list
                    // and we make one inference call.
                    val subWindows = RawChunker.chunkForPrompt(
                        raw = task.chunkText,
                        systemPrompt = cleanForMePrompt,
                        perChunkOutputCap = maxOutputTokens,
                    )
                    val resultBuilder = StringBuilder()
                    for ((i, sub) in subWindows.withIndex()) {
                        val partial = gemma.runInference(
                            systemPrompt = cleanForMePrompt,
                            userPrompt = sub,
                            maxOutputTokens = maxOutputTokens,
                            temperature = temperature,
                        )
                        if (i > 0) resultBuilder.append("\n\n")
                        resultBuilder.append(partial.trim())
                    }
                    val result = resultBuilder.toString()
                    val durMs = System.currentTimeMillis() - startMs
                    outFile.writeText(result.trim())
                    Log.i(TAG, "cleaned idx=${task.idx} inChars=${task.chunkText.length} subWindows=${subWindows.size} outChars=${result.length} dur=${durMs}ms")
                }.onFailure { t ->
                    Log.w(TAG, "clean idx=${task.idx} failed: ${t.message}", t)
                    // Don't write a partial result — the on-tap fallback will fill the gap.
                }
            }
        }
    }

    /** Enqueue a chunk for cleaning. Non-blocking. */
    fun enqueue(idx: Int, chunkText: String) {
        val trimmed = chunkText.trim()
        if (trimmed.isEmpty()) return
        val result = queue.trySend(CleanTask(idx, trimmed))
        if (result.isFailure) {
            Log.w(TAG, "enqueue idx=$idx failed: ${result.exceptionOrNull()?.message}")
        }
    }

    /**
     * Close the queue and let the worker drain remaining tasks. Returns the [Job] so the
     * caller can [join][Job.join] it with a timeout. The service should do that on stop
     * so the foreground notification stays up while the last chunk finishes.
     */
    fun drainAndStop(): Job? {
        queue.close()
        return worker
    }

    companion object {
        private const val TAG = "BidetChunkCleaner"

        /** Subdirectory under the session external dir for per-chunk cleaning outputs. */
        const val CLEANINGS_DIR: String = "cleanings"

        /** Filename suffix for the receptive (Clean-for-me) per-chunk output. */
        const val RECEPTIVE_SUFFIX: String = "receptive"

        /**
         * Per-chunk output token cap. Each 30-sec audio window is ~50-100 spoken words
         * (~150-300 chars); cleaned output is typically 1-3 bullets of similar length.
         * 256 tokens covers worst-case verbose chunks while keeping decode wall-clock to
         * ~15-30s per chunk on Tensor G3 CPU at ~10 tk/s — comfortably under the next
         * chunk's 30-sec arrival.
         */
        const val DEFAULT_PER_CHUNK_OUTPUT_TOKENS: Int = 256

        /** Matches the brief-locked single-shot cleaning temperature. */
        const val DEFAULT_TEMPERATURE: Float = 0.3f

        /**
         * Helper for [com.google.ai.edge.gallery.bidet.ui.SessionDetailScreen] to check
         * whether a session has full per-chunk pre-cleaning. Returns the stitched output
         * if all chunks under [expectedChunkCount] have a cleaning file; null if any are
         * missing (caller should fall back to the on-tap chunked path).
         */
        fun loadStitched(sessionExternalDir: File, expectedChunkCount: Int): String? {
            if (expectedChunkCount <= 0) return null
            val dir = File(sessionExternalDir, CLEANINGS_DIR)
            if (!dir.isDirectory) return null
            val parts = mutableListOf<String>()
            for (i in 0 until expectedChunkCount) {
                val f = File(dir, "${i}_${RECEPTIVE_SUFFIX}.txt")
                if (!f.exists() || f.length() == 0L) return null
                parts.add(f.readText().trim())
            }
            return parts.joinToString("\n\n")
        }
    }
}
