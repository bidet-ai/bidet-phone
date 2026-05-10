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

package com.google.ai.edge.gallery.bidet.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.google.ai.edge.gallery.bidet.chunk.Chunk
import com.google.ai.edge.gallery.bidet.chunk.ChunkQueue
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Single continuous capture engine that drives the brain-dump pipeline.
 *
 * Spec (brief §2):
 *  - One [AudioRecord] at 16 kHz, mono, PCM 16-bit.
 *  - Buffer size = max(getMinBufferSize() * 4, 32 000) bytes.
 *  - Reads on a dedicated thread, writes into a circular ring buffer sized for 32 sec
 *    (30 sec window + 2 sec rolling backbuffer).
 *  - Every 30 seconds emits a [Chunk.Audio] covering [start..start+30s] with 2-sec overlap
 *    against the previous chunk's tail.
 *  - Persists each chunk to `${getExternalFilesDir(null)}/sessions/<session_id>/chunks/<idx>.pcm`
 *    via tmp+rename atomic write for crash recovery.
 *  - PCM bytes only (NOT WAV-with-header) — sherpa-onnx's OfflineStream.acceptWaveform takes raw float32 PCM.
 *
 * Lifecycle is owned by [com.google.ai.edge.gallery.bidet.service.RecordingService]:
 *  - [start] arms the AudioRecord and spawns the reader thread (idempotent — if [start] has
 *    already run for this session, subsequent calls clear [paused] and resume the existing
 *    reader thread without resetting [nextChunkIdx] / [pendingBuffer] / [backbuffer]).
 *  - [pause] / [resume] flip a soft-pause flag for transient AudioFocus loss (e.g. a phone
 *    call). The reader thread + AudioRecord stay alive; reads loop without dispatching bytes
 *    to the pending buffer. Resume picks up the chunk index where pause left it.
 *  - [stop] flushes any tail audio (<30 sec since last emit), terminates the thread, releases
 *    the AudioRecord. Safe to call multiple times.
 *
 * Phase 4A.1 fix: prior code reset `nextChunkIdx = 0` + cleared `pendingBuffer` /
 * `backbuffer` on every `start()`. When AudioFocus listener called `stop()`/`start()` on
 * transient loss/gain (incoming call), the second `start()` overwrote chunks `0.pcm`,
 * `1.pcm`, ... in the same `sessions/<id>/chunks/` directory. Fix: gate the reset on a
 * one-shot `initialized` flag (only the first start of a session resets), and switch the
 * AudioFocus transient handler to use the soft-pause path so AudioRecord/threading state
 * survives the focus blip.
 */
class AudioCaptureEngine(
    private val context: Context,
    private val chunkQueue: ChunkQueue,
    private val sessionId: String,
) {

    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    /** One-shot: true after the first successful [start] for this engine instance. */
    private val initialized = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var readerThread: Thread? = null
    private var sessionDir: File? = null

    /** Wall-clock millis at recording start, for `startMs` / `endMs` on chunks. */
    private var sessionStartMs: Long = 0L

    /** Monotonically incrementing chunk index. */
    private var nextChunkIdx: Int = 0

    /** Bytes accumulated since the last chunk emission. */
    private val pendingBuffer = ArrayDeque<ByteArray>()
    private var pendingBytes: Int = 0

    /** Last 2-sec backbuffer carried over from the previous emission. */
    private var backbuffer: ByteArray = ByteArray(0)

    @SuppressLint("MissingPermission")
    fun start() {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "start() called while already running — no-op.")
            return
        }
        // Phase 4A.1: only the FIRST start() of this engine instance resets per-session state.
        // Subsequent start() calls (after stop()/start() ping-pongs) preserve the chunk index +
        // pending buffer + backbuffer so chunk filenames don't collide with previously written
        // ones in `sessions/<id>/chunks/`. The preferred recovery path for transient AudioFocus
        // loss is now [pause]/[resume]; this guard is belt-and-suspenders for any caller that
        // still reaches for stop()/start().
        if (initialized.compareAndSet(false, true)) {
            sessionStartMs = System.currentTimeMillis()
            nextChunkIdx = 0
            pendingBuffer.clear()
            pendingBytes = 0
            backbuffer = ByteArray(0)
        }

        // Prepare per-session storage for crash recovery.
        sessionDir = File(context.getExternalFilesDir(null), "sessions/$sessionId/chunks")
            .also { it.mkdirs() }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuf <= 0) {
            running.set(false)
            throw IllegalStateException("AudioRecord.getMinBufferSize returned $minBuf — device unsupported.")
        }
        val bufferSize = max(minBuf * 4, 32_000)
        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize,
        ).also { it.startRecording() }

        // Always clear the soft-pause flag — both fresh starts and post-stop restarts mean
        // "ingest bytes again".
        paused.set(false)

        readerThread = thread(name = "BidetAudioReader", isDaemon = true) {
            readLoop(bufferSize)
        }

        Log.i(TAG, "start: sessionId=$sessionId bufferSize=$bufferSize " +
            "nextChunkIdx=$nextChunkIdx (initialized=${initialized.get()})")
    }

    /**
     * Phase 4A.1: soft-pause for transient AudioFocus loss (incoming call etc.).
     *
     * Sets [paused] = true. The reader thread keeps draining the AudioRecord (so the
     * hardware doesn't overflow its internal buffer) but skips appending those bytes to
     * [pendingBuffer]. AudioRecord and the reader thread stay alive across the focus blip.
     * On [resume] the chunk index, pending buffer, and backbuffer are exactly where pause
     * left them — no chunk overwrite, no new session.
     */
    fun pause() {
        if (paused.compareAndSet(false, true)) {
            Log.i(TAG, "pause: soft-pause active (idx=$nextChunkIdx, pending=$pendingBytes).")
        }
    }

    /** Phase 4A.1: counterpart to [pause]. Idempotent. */
    fun resume() {
        if (paused.compareAndSet(true, false)) {
            Log.i(TAG, "resume: continuing capture from idx=$nextChunkIdx pending=$pendingBytes.")
        }
    }

    /** Stop reading, flush any pending tail audio, release the recorder. Idempotent. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        Log.i(TAG, "stop: flushing pending=${pendingBytes} bytes")
        try {
            readerThread?.join(2_000)
        } catch (_: InterruptedException) {
            // ignore
        }
        readerThread = null
        try {
            recorder?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "stop: recorder.stop threw ${t.message}")
        }
        try {
            recorder?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "stop: recorder.release threw ${t.message}")
        }
        recorder = null

        // Flush any remaining unfilled window so the user doesn't lose the tail.
        flushPending(force = true)
    }

    private fun readLoop(bufferSize: Int) {
        val readBuf = ByteArray(bufferSize)
        while (running.get()) {
            val rec = recorder ?: break
            val n = rec.read(readBuf, 0, readBuf.size)
            if (n <= 0) continue
            // Phase 4A.1: while soft-paused (transient AudioFocus loss), keep draining the
            // hardware buffer but discard the bytes — the AudioRecord stays armed so
            // [resume] is instant, but we don't ingest the user's voice during a phone call.
            if (paused.get()) continue
            // Append a copy of the just-read chunk into the pending deque.
            val slice = readBuf.copyOf(n)
            pendingBuffer.addLast(slice)
            pendingBytes += n

            // Emit a chunk every 30 seconds of pending audio.
            if (pendingBytes >= WINDOW_BYTES) {
                emitWindow()
            }
        }
    }

    /**
     * Coalesce [pendingBuffer] into one byte array of length [WINDOW_BYTES] (or less, if
     * [force] is true and we're flushing a tail), with the previous chunk's 2-sec backbuffer
     * prepended for overlap. Then send it to [chunkQueue] and persist atomically to disk.
     */
    private fun emitWindow() {
        val windowBytes = drain(WINDOW_BYTES)
        sendChunk(prependBackbuffer(windowBytes))
        // Set up the backbuffer for next emission: keep the last [BACKBUFFER_BYTES] of the
        // chunk we just emitted (post-prepend) so the next chunk's overlap region is 2 sec.
        backbuffer = if (windowBytes.size >= BACKBUFFER_BYTES) {
            windowBytes.copyOfRange(windowBytes.size - BACKBUFFER_BYTES, windowBytes.size)
        } else {
            windowBytes
        }
    }

    private fun flushPending(force: Boolean) {
        if (!force) return
        if (pendingBytes <= 0) return
        val tail = drain(pendingBytes)
        sendChunk(prependBackbuffer(tail))
    }

    private fun drain(maxBytes: Int): ByteArray {
        val take = minOf(maxBytes, pendingBytes)
        val out = ByteArray(take)
        var written = 0
        while (written < take && pendingBuffer.isNotEmpty()) {
            val head = pendingBuffer.first()
            val remainHead = head.size
            val remainOut = take - written
            val copyN = minOf(remainHead, remainOut)
            System.arraycopy(head, 0, out, written, copyN)
            written += copyN
            if (copyN == remainHead) {
                pendingBuffer.removeFirst()
            } else {
                // Replace head with the unconsumed suffix.
                val leftover = ByteArray(remainHead - copyN)
                System.arraycopy(head, copyN, leftover, 0, leftover.size)
                pendingBuffer.removeFirst()
                pendingBuffer.addFirst(leftover)
            }
        }
        pendingBytes -= take
        return out
    }

    private fun prependBackbuffer(window: ByteArray): ByteArray {
        if (backbuffer.isEmpty()) return window
        val out = ByteArray(backbuffer.size + window.size)
        System.arraycopy(backbuffer, 0, out, 0, backbuffer.size)
        System.arraycopy(window, 0, out, backbuffer.size, window.size)
        return out
    }

    private fun sendChunk(bytes: ByteArray) {
        val idx = nextChunkIdx++
        val nowMs = System.currentTimeMillis() - sessionStartMs
        val durationMs = bytes.size * 1_000L / BYTES_PER_SECOND
        val chunk = Chunk.Audio(
            idx = idx,
            startMs = (nowMs - durationMs).coerceAtLeast(0),
            endMs = nowMs,
            bytes = bytes,
        )
        persistAtomic(idx, bytes)
        chunkQueue.offer(chunk)
    }

    /** Atomic write: tmp file + rename. Keeps disk consistent across crashes. */
    private fun persistAtomic(idx: Int, bytes: ByteArray) {
        val dir = sessionDir ?: return
        val tmp = File(dir, "$idx.pcm.tmp")
        val final = File(dir, "$idx.pcm")
        try {
            FileOutputStream(tmp).use { it.write(bytes) }
            if (!tmp.renameTo(final)) {
                // Fallback: copy + delete
                final.delete()
                tmp.renameTo(final)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "persistAtomic($idx) failed: ${t.message}")
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "BidetAudioCapture"

        const val SAMPLE_RATE_HZ: Int = 16_000
        const val CHANNEL_CONFIG: Int = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT: Int = AudioFormat.ENCODING_PCM_16BIT

        /** Bytes per second at 16 kHz mono PCM 16-bit = 32000. */
        const val BYTES_PER_SECOND: Int = 16_000 * 2

        /** 30 seconds @ 32 KB/s = 960 000 bytes. */
        const val WINDOW_BYTES: Int = 30 * BYTES_PER_SECOND

        /** 2-sec rolling backbuffer = 64 000 bytes. */
        const val BACKBUFFER_BYTES: Int = 2 * BYTES_PER_SECOND
    }
}
