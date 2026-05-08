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

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the per-chunk PCM files written by [AudioCaptureEngine] and produces a single
 * playable WAV at `${getExternalFilesDir(null)}/sessions/<sessionId>/audio.wav`.
 *
 * Design notes:
 *  - Chunk files are raw PCM 16 kHz mono int16 with a 2-second leading backbuffer (per
 *    [AudioCaptureEngine]). We strip the backbuffer overlap from chunks 1..N so the WAV
 *    plays back without doubled audio.
 *  - We prepend a standard 44-byte RIFF/WAVE header. PCM 16-bit, mono, 16 kHz.
 *  - Idempotent: if `audio.wav` already exists with a non-zero size we skip and return it.
 *  - All I/O is synchronous; callers should run on a background dispatcher.
 *
 * The chunk-format invariant (raw PCM, no per-chunk WAV header) is locked by Phase 1's brief
 * §2 — see [AudioCaptureEngine.persistAtomic].
 */
object WavConcatenator {

    private const val TAG = "BidetWavConcat"

    /**
     * Concatenate every `<idx>.pcm` file in
     * `${getExternalFilesDir(null)}/sessions/<sessionId>/chunks/` into one playable WAV.
     *
     * Returns the resulting [File] (which may already have existed before this call).
     * Returns null if there are no chunk files (e.g. recording never produced audio).
     *
     * @param context any Context — only [Context.getExternalFilesDir] is used.
     * @param sessionId the session UUID matching [AudioCaptureEngine]'s `sessionId` parameter.
     */
    fun concatenateChunksToWav(context: Context, sessionId: String): File? {
        val baseDir = context.getExternalFilesDir(null) ?: return null
        val sessionDir = File(baseDir, "sessions/$sessionId")
        val chunksDir = File(sessionDir, "chunks")
        val outFile = File(sessionDir, "audio.wav")

        if (outFile.exists() && outFile.length() > WAV_HEADER_SIZE) {
            Log.i(TAG, "concatenateChunksToWav: idempotent skip — $outFile")
            return outFile
        }

        if (!chunksDir.exists() || !chunksDir.isDirectory) {
            Log.w(TAG, "No chunks directory at $chunksDir — nothing to concatenate.")
            return null
        }

        // Walk chunks in numerical (not lexicographic) order: 2.pcm < 10.pcm.
        val chunkFiles = chunksDir.listFiles { f ->
            f.isFile && f.name.endsWith(".pcm") && !f.name.endsWith(".tmp")
        }?.sortedBy { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE } ?: emptyList()

        if (chunkFiles.isEmpty()) {
            Log.w(TAG, "No .pcm chunk files in $chunksDir.")
            return null
        }

        // Compute the data byte total. Every chunk except chunk 0 has a 2-sec backbuffer
        // prepended (see AudioCaptureEngine.prependBackbuffer); strip that overlap to avoid
        // doubled playback.
        val backbufferBytes = AudioCaptureEngine.BACKBUFFER_BYTES
        var dataBytesTotal = 0L
        chunkFiles.forEachIndexed { i, f ->
            val len = f.length()
            dataBytesTotal += if (i == 0) len else (len - backbufferBytes).coerceAtLeast(0)
        }

        if (dataBytesTotal <= 0L) {
            Log.w(TAG, "Computed dataBytesTotal=$dataBytesTotal — refusing to write empty WAV.")
            return null
        }

        // Phase 4A.1 (#8): the canonical WAV header is 32-bit (`Subchunk2Size` is a uint32
        // for the data section, with `ChunkSize = 36 + dataLen` also 32-bit). At 16 kHz mono
        // PCM 16-bit = 32 KB/s, the limit is hit at ≈18.6 hours of audio. Anything past
        // that overflows when we narrow `Long → Int` and writes a corrupt header. Bidet's
        // brain-dump use case is 5-15 min, but lectures/podcasts can hit the bound; refuse
        // to write rather than ship a corrupt WAV. RF64 extension is a Phase 5+ task.
        if (dataBytesTotal > Int.MAX_VALUE.toLong() - 36L) {
            Log.w(
                TAG,
                "Recording exceeds 32-bit WAV size limit (data=$dataBytesTotal bytes). " +
                    "Refusing to write a corrupt header. Phase 5+: switch to RF64.",
            )
            return null
        }

        sessionDir.mkdirs()
        val tmp = File(sessionDir, "audio.wav.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(buildWavHeader(dataBytesTotal.toInt()))
                chunkFiles.forEachIndexed { i, f ->
                    // Phase 4A.1 (#11): stream the chunk through a 64 KB buffer instead of
                    // `f.readBytes()` (which allocates the whole chunk on heap — 960 KB/30 s
                    // chunk plus 64 KB backbuffer overlap = ~1 MB allocation per chunk on a
                    // tight memory device).
                    val skip = if (i == 0) 0L else minOf(backbufferBytes.toLong(), f.length())
                    f.inputStream().buffered(STREAM_BUFFER_BYTES).use { input ->
                        if (skip > 0) {
                            var remaining = skip
                            while (remaining > 0) {
                                val skipped = input.skip(remaining)
                                if (skipped <= 0) break
                                remaining -= skipped
                            }
                        }
                        input.copyTo(out, bufferSize = STREAM_BUFFER_BYTES)
                    }
                }
            }
            // Atomic rename.
            if (outFile.exists()) outFile.delete()
            if (!tmp.renameTo(outFile)) {
                throw java.io.IOException(
                    "Failed to rename ${tmp.absolutePath} to ${outFile.absolutePath}"
                )
            }
            Log.i(TAG, "Wrote ${outFile.length()} bytes to $outFile (data=$dataBytesTotal).")
            return outFile
        } catch (t: Throwable) {
            Log.e(TAG, "concatenateChunksToWav failed: ${t.message}", t)
            try {
                tmp.delete()
            } catch (_: Throwable) {
                // ignore
            }
            return null
        }
    }

    /**
     * Build a 44-byte WAV header for PCM 16-bit mono at 16 kHz.
     *
     * Layout per WAVEFORMATEX / RIFF spec:
     *   bytes  0–3  "RIFF"
     *   bytes  4–7  ChunkSize        (36 + dataLen)
     *   bytes  8–11 "WAVE"
     *   bytes 12–15 "fmt "
     *   bytes 16–19 Subchunk1Size    (16 for PCM)
     *   bytes 20–21 AudioFormat      (1 = PCM)
     *   bytes 22–23 NumChannels      (1 = mono)
     *   bytes 24–27 SampleRate       (16000)
     *   bytes 28–31 ByteRate         (SampleRate * NumChannels * BitsPerSample/8)
     *   bytes 32–33 BlockAlign       (NumChannels * BitsPerSample/8)
     *   bytes 34–35 BitsPerSample    (16)
     *   bytes 36–39 "data"
     *   bytes 40–43 Subchunk2Size    (dataLen)
     */
    internal fun buildWavHeader(dataLen: Int): ByteArray {
        val sampleRate = AudioCaptureEngine.SAMPLE_RATE_HZ
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        val header = ByteArray(WAV_HEADER_SIZE)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // "RIFF"
        bb.put(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))
        // ChunkSize = 36 + dataLen
        bb.putInt(36 + dataLen)
        // "WAVE"
        bb.put(byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()))
        // "fmt "
        bb.put(byteArrayOf('f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte()))
        // Subchunk1Size = 16
        bb.putInt(16)
        // AudioFormat = 1 (PCM)
        bb.putShort(1)
        // NumChannels
        bb.putShort(channels.toShort())
        // SampleRate
        bb.putInt(sampleRate)
        // ByteRate
        bb.putInt(byteRate)
        // BlockAlign
        bb.putShort(blockAlign.toShort())
        // BitsPerSample
        bb.putShort(bitsPerSample.toShort())
        // "data"
        bb.put(byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()))
        // Subchunk2Size
        bb.putInt(dataLen)

        return header
    }

    const val WAV_HEADER_SIZE: Int = 44

    /** Phase 4A.1: 64 KB streaming buffer for chunk → WAV concat. */
    private const val STREAM_BUFFER_BYTES: Int = 64 * 1024
}
