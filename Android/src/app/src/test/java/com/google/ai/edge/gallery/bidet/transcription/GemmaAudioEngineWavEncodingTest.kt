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

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-05-09 regression test for [float32ToWavBytes].
 *
 * Bug being pinned: previously [GemmaAudioEngine.transcribe] passed raw int16 PCM bytes
 * to [com.google.ai.edge.litertlm.Content.AudioBytes]. LiteRT-LM 0.11 decodes audio bytes
 * via MiniAudio's `ma_decoder_init_memory()`, which only accepts container formats
 * (WAV / FLAC / MP3). Headerless raw PCM is silently rejected — the audio executor decodes
 * 0 frames, the model sees no audio, the user gets an empty RAW transcript with no error
 * surface. This was the "Tonight, two attempts, two failures" symptom.
 *
 * Fix: wrap the int16 PCM in a 44-byte canonical RIFF/WAVE header before handing off to
 * `Content.AudioBytes`. This test pins the byte layout (RIFF magic, PCM/mono/16-bit/16 kHz
 * fields, dataLen math) so a future refactor that loses the header (or e.g. changes the
 * sample-rate field by accident) is caught at unit-test time.
 *
 * Why we test the helper rather than the full transcribe method: GemmaAudioEngine's
 * transcribe() depends on a live LiteRT-LM Conversation with 3.6 GB of weights loaded;
 * exercising it from JVM unit tests is impossible. The encoding logic is pure-Kotlin —
 * extracted to [float32ToWavBytes] (file-internal) so this test can pin the byte layout
 * without booting Gemma.
 */
class GemmaAudioEngineWavEncodingTest {

    @Test
    fun float32ToWavBytes_writesRiffWaveHeader_thenInt16Pcm() {
        // 4 samples is enough to verify both the header and the body layout while keeping
        // the assertions readable.
        val samples = floatArrayOf(0.0f, 0.5f, -0.5f, 1.0f)
        val out = float32ToWavBytes(samples, sampleRateHz = 16_000)

        // Header bytes (44) + body bytes (4 samples × 2 bytes/sample = 8). Total = 52.
        assertEquals(
            "WAV total size must equal 44-byte header + 2 bytes per sample. If the header " +
                "is missing/wrong-sized this is the first thing that fails — and it's also " +
                "the bug we're guarding against (raw int16 PCM with no header at all).",
            44 + samples.size * 2,
            out.size,
        )

        // Magic bytes — RIFF...WAVE...fmt ...data — are the unambiguous "this is WAV"
        // signal MiniAudio uses to even attempt to decode. If any of these are wrong the
        // audio preprocessor returns the same 0-frames silent failure as the original bug.
        assertEquals('R'.code.toByte(), out[0])
        assertEquals('I'.code.toByte(), out[1])
        assertEquals('F'.code.toByte(), out[2])
        assertEquals('F'.code.toByte(), out[3])
        assertEquals('W'.code.toByte(), out[8])
        assertEquals('A'.code.toByte(), out[9])
        assertEquals('V'.code.toByte(), out[10])
        assertEquals('E'.code.toByte(), out[11])
        assertEquals('f'.code.toByte(), out[12])
        assertEquals('m'.code.toByte(), out[13])
        assertEquals('t'.code.toByte(), out[14])
        assertEquals(' '.code.toByte(), out[15])
        assertEquals('d'.code.toByte(), out[36])
        assertEquals('a'.code.toByte(), out[37])
        assertEquals('t'.code.toByte(), out[38])
        assertEquals('a'.code.toByte(), out[39])

        // Read the structured fields back via ByteBuffer to verify them as the WAV spec
        // requires (little-endian) rather than poking individual bytes.
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        // ChunkSize at offset 4 = 36 + dataLen
        assertEquals(36 + samples.size * 2, bb.getInt(4))
        // Subchunk1Size at offset 16 = 16 (PCM)
        assertEquals(16, bb.getInt(16))
        // AudioFormat at offset 20 = 1 (PCM)
        assertEquals(1.toShort(), bb.getShort(20))
        // NumChannels at offset 22 = 1 (mono)
        assertEquals(1.toShort(), bb.getShort(22))
        // SampleRate at offset 24 = 16_000
        assertEquals(16_000, bb.getInt(24))
        // ByteRate at offset 28 = 16_000 * 1 * 16/8 = 32_000
        assertEquals(32_000, bb.getInt(28))
        // BlockAlign at offset 32 = 1 * 16/8 = 2
        assertEquals(2.toShort(), bb.getShort(32))
        // BitsPerSample at offset 34 = 16
        assertEquals(16.toShort(), bb.getShort(34))
        // Subchunk2Size at offset 40 = dataLen
        assertEquals(samples.size * 2, bb.getInt(40))
    }

    @Test
    fun float32ToWavBytes_clipsAndConvertsSamplesToInt16() {
        // 0.0 → 0; 0.5 → ~16383; -0.5 → ~-16384; 1.0 → 32767.
        // We allow ±1 of slop on the rounding-direction since (0.5 * 32767 = 16383.5).
        val samples = floatArrayOf(0.0f, 0.5f, -0.5f, 1.0f)
        val out = float32ToWavBytes(samples, sampleRateHz = 16_000)

        val body = ByteBuffer.wrap(out, 44, samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        val s0 = body.getShort()
        val s1 = body.getShort()
        val s2 = body.getShort()
        val s3 = body.getShort()

        assertEquals("0.0f → 0", 0.toShort(), s0)
        assertTrue(
            "0.5f → ~16383 (got $s1). Tolerance ±1 for rounding direction.",
            kotlin.math.abs(s1 - 16383) <= 1,
        )
        assertTrue(
            "-0.5f → ~-16383 (got $s2). Tolerance ±1 for rounding direction.",
            kotlin.math.abs(s2 - (-16383)) <= 1,
        )
        assertEquals("1.0f → 32767 (max int16)", 32767.toShort(), s3)
    }

    @Test
    fun float32ToWavBytes_clipsValuesOutsidePlusMinusOne() {
        // Passing ±1.5 should clip to ±1.0 then scale → ±32767. Without the clip we'd get
        // integer overflow producing garbage int16 values. AudioCaptureEngine should never
        // emit out-of-range floats, but the helper is called from a worker that doesn't
        // know that contract — defensive clipping is part of what we ship.
        val samples = floatArrayOf(1.5f, -1.5f, 100f, -100f)
        val out = float32ToWavBytes(samples, sampleRateHz = 16_000)

        val body = ByteBuffer.wrap(out, 44, samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(32767.toShort(), body.getShort())
        assertEquals((-32767).toShort(), body.getShort())
        assertEquals(32767.toShort(), body.getShort())
        assertEquals((-32767).toShort(), body.getShort())
    }

    @Test
    fun float32ToWavBytes_emptyInputProducesHeaderOnly() {
        // Edge case: an empty Float32 array still produces a syntactically valid 44-byte
        // WAV file with dataLen=0. MiniAudio decodes that as "0 frames" (vs. crashing on
        // a malformed file), which then propagates as an empty transcript — but at least
        // we never produce invalid bytes ourselves.
        val out = float32ToWavBytes(floatArrayOf(), sampleRateHz = 16_000)
        assertEquals(44, out.size)

        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36, bb.getInt(4)) // ChunkSize = 36 + 0
        assertEquals(0, bb.getInt(40)) // Subchunk2Size = 0
    }

    @Test
    fun float32ToWavBytes_dataLenIsTwoTimesSampleCount() {
        // 100 samples → 200 bytes of body (one int16 = 2 bytes). The Subchunk2Size field
        // at offset 40 must reflect this exactly. Off-by-one or off-by-factor-of-2 here
        // is the kind of header-corruption bug MiniAudio surfaces as a decode error.
        val samples = FloatArray(100) { 0f }
        val out = float32ToWavBytes(samples, sampleRateHz = 16_000)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(200, bb.getInt(40))
        assertEquals(44 + 200, out.size)
    }

    @Test
    fun float32ToWavBytes_honorsSampleRate() {
        // Pin that the sample rate field reflects what the caller passed, not a hardcoded
        // 16 000. AudioCaptureEngine emits 16 kHz today, but the API contract is "whatever
        // you pass" — and a future change in the capture path (e.g. 24 kHz support) must
        // not silently produce a WAV that says 16 kHz.
        val samples = floatArrayOf(0f, 0f, 0f, 0f)
        val out8k = float32ToWavBytes(samples, sampleRateHz = 8_000)
        val out16k = float32ToWavBytes(samples, sampleRateHz = 16_000)
        val out48k = float32ToWavBytes(samples, sampleRateHz = 48_000)

        assertEquals(
            8_000,
            ByteBuffer.wrap(out8k).order(ByteOrder.LITTLE_ENDIAN).getInt(24),
        )
        assertEquals(
            16_000,
            ByteBuffer.wrap(out16k).order(ByteOrder.LITTLE_ENDIAN).getInt(24),
        )
        assertEquals(
            48_000,
            ByteBuffer.wrap(out48k).order(ByteOrder.LITTLE_ENDIAN).getInt(24),
        )
        // ByteRate must update too — pin so a future refactor doesn't decouple SampleRate
        // from ByteRate (a real WAV file with mismatched fields plays back at the wrong
        // pitch and confuses MiniAudio's resampler).
        assertEquals(
            16_000, // 8 kHz * 1 * 2
            ByteBuffer.wrap(out8k).order(ByteOrder.LITTLE_ENDIAN).getInt(28),
        )
        assertEquals(
            32_000, // 16 kHz * 1 * 2
            ByteBuffer.wrap(out16k).order(ByteOrder.LITTLE_ENDIAN).getInt(28),
        )
        assertEquals(
            96_000, // 48 kHz * 1 * 2
            ByteBuffer.wrap(out48k).order(ByteOrder.LITTLE_ENDIAN).getInt(28),
        )
    }

    @Test
    fun float32ToWavBytes_rejectsNonPositiveSampleRate() {
        // Pre-condition. 0 or negative would produce a WAV with ByteRate=0 → MiniAudio
        // divides by zero in resampling. Surface as IllegalArgumentException upfront.
        try {
            float32ToWavBytes(floatArrayOf(0f), sampleRateHz = 0)
            org.junit.Assert.fail("Expected IllegalArgumentException for sampleRateHz=0")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Error message should reference sampleRateHz so the failed contract is " +
                    "obvious in logs.",
                e.message?.contains("sampleRateHz") == true,
            )
        }
    }

    @Test
    fun float32ToWavBytes_outputDiffersFromRawPcm_thatProducedTheBug() {
        // Sanity check that the bytes we emit are NOT the same as the bytes the previous
        // (broken) implementation emitted. If a future refactor accidentally reverts to
        // raw int16 PCM (the F3.2-era code), the first 4 bytes will be `00 00 00 00`
        // (sample 0 = silence × 4 bytes) instead of `R I F F` and this test catches it.
        val samples = floatArrayOf(0.0f, 0.5f, -0.5f, 1.0f)
        val withWavHeader = float32ToWavBytes(samples, sampleRateHz = 16_000)

        // Sample-count × 2 bytes, no header — that's what the buggy code produced.
        val rawInt16OnlyBytesSize = samples.size * 2
        assertNotEquals(
            "WAV bytes must NOT equal raw int16-only bytes — that's the original bug we " +
                "are guarding against.",
            rawInt16OnlyBytesSize,
            withWavHeader.size,
        )
        // First byte is 'R', not 0 (= sample 0.0f's low byte).
        assertEquals('R'.code.toByte(), withWavHeader[0])
    }
}
