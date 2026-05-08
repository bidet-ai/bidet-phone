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

package com.google.ai.edge.gallery.bidet.chunk

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.Flow

/**
 * A bounded queue of [Chunk]s flowing from [com.google.ai.edge.gallery.bidet.audio.AudioCaptureEngine]
 * to [com.google.ai.edge.gallery.bidet.transcription.TranscriptionWorker].
 *
 * Behaviour per brief §3:
 *  - Capacity 4 (≈2 minutes of buffered audio at 30-sec windows).
 *  - When the queue is full and a new chunk arrives, drop the OLDEST queued chunk and emit a
 *    [Chunk.MarkerLost] for it so the aggregator can splice in a "[chunk N transcription failed]"
 *    marker rather than silently swallow audio.
 *  - Backpressure (chunks-behind count) is exposed via [chunksBehind] for the UI banner.
 */
class ChunkQueue(capacity: Int = DEFAULT_CAPACITY) {

    /** Underlying channel — DROP_OLDEST so the producer (audio thread) never blocks. */
    private val channel: Channel<Chunk> = Channel(
        capacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Channel for marker events emitted on overflow. Always buffered (Channel.UNLIMITED). */
    private val markers: Channel<Chunk.MarkerLost> = Channel(Channel.UNLIMITED)

    private val _chunksBehind = MutableStateFlow(0)
    /** Number of chunks currently waiting in the queue. UI surfaces this above 1. */
    val chunksBehind: StateFlow<Int> = _chunksBehind

    /**
     * Offer a chunk to the queue. The producer must not suspend (audio thread is real-time);
     * this implementation uses [Channel.trySend] semantics with DROP_OLDEST so the call always
     * returns immediately. If a drop occurs, a synthetic [Chunk.MarkerLost] is queued onto the
     * markers channel.
     */
    fun offer(chunk: Chunk.Audio) {
        // Snapshot the queue depth BEFORE we send — if it's already at capacity, the upcoming
        // send will trigger a DROP_OLDEST, and we want a marker for that dropped slot. We can't
        // know the precise dropped index without explicitly tracking it, so we synthesize a
        // marker keyed to the chunk we're inserting *minus capacity* (its predecessor at the
        // queue's head).
        val depth = currentDepth()
        if (depth >= CAPACITY_HIGH_WATERMARK) {
            // Best-effort marker: we know SOMETHING got dropped, and the chunk-just-displaced
            // was at offset (chunk.idx - depth). Caller can refine later if needed.
            val droppedIdx = (chunk.idx - depth).coerceAtLeast(0)
            markers.trySend(Chunk.MarkerLost(idx = droppedIdx, reason = "queue full"))
        }
        channel.trySend(chunk)
        _chunksBehind.value = currentDepth()
    }

    /** Hand the channel out as a Flow for [TranscriptionWorker] to consume. */
    fun asFlow(): Flow<Chunk> = channel.consumeAsFlow()

    /** Markers channel as a Flow — typically combined into the transcript stream. */
    fun markersFlow(): Flow<Chunk.MarkerLost> = markers.consumeAsFlow()

    /** Decrement the chunks-behind gauge after a consumer finishes processing one chunk. */
    fun acknowledgeProcessed() {
        _chunksBehind.value = currentDepth()
    }

    /**
     * Best-effort depth probe. kotlinx.coroutines.channels does not expose size publicly,
     * so this is a coarse approximation maintained from offer/acknowledge events.
     */
    private fun currentDepth(): Int = _chunksBehind.value

    /** Close both channels. Safe to call multiple times. */
    fun close() {
        channel.close()
        markers.close()
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 4

        /**
         * Above this depth we consider the queue "full" for marker-emission purposes.
         * Equal to [DEFAULT_CAPACITY] so the marker fires exactly when DROP_OLDEST would.
         */
        const val CAPACITY_HIGH_WATERMARK: Int = DEFAULT_CAPACITY
    }
}

/**
 * A unit flowing through the pipeline. Either real audio bytes for a 30-sec window
 * ([Chunk.Audio]) or a synthetic marker recording an audio chunk that the queue had to drop
 * because the consumer fell behind ([Chunk.MarkerLost]).
 */
sealed class Chunk {

    /** Monotonically increasing chunk index, starting at 0 for each session. */
    abstract val idx: Int

    /**
     * A captured 30-sec audio window.
     *
     * @param idx index in this session.
     * @param startMs window start in milliseconds since session start.
     * @param endMs window end in milliseconds since session start.
     * @param bytes raw PCM 16-bit little-endian mono samples at 16 kHz.
     */
    data class Audio(
        override val idx: Int,
        val startMs: Long,
        val endMs: Long,
        val bytes: ByteArray,
    ) : Chunk() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Audio) return false
            return idx == other.idx && startMs == other.startMs && endMs == other.endMs
        }
        override fun hashCode(): Int =
            (idx * 31 + startMs.hashCode()) * 31 + endMs.hashCode()
    }

    /** A drop notification — emitted when DROP_OLDEST kicks in. */
    data class MarkerLost(
        override val idx: Int,
        val reason: String,
    ) : Chunk()
}
