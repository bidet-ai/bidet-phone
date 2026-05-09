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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * F5.1 (2026-05-09) regression test for [gemmaAudioTruncatedTail].
 *
 * Bug: in [GemmaAudioEngine.transcribe], the previous truncation kept `[0, maxSamples)` —
 * the FIRST 30 s — and dropped the rest. AudioCaptureEngine emits 30-s windows with a 2-s
 * overlap; the *new* audio (the 2 s that distinguishes this chunk from the previous one)
 * lives at the END of the buffer. So the bug silently dropped the dedup window for every
 * chunk that crossed 30 s, breaking adjacency dedup in the aggregator.
 *
 * Fix: keep `[size - maxSamples, size)` — the LAST 30 s — so the most recent audio (and
 * the 2-s overlap window) is what gets fed to Gemma. This test pins that contract by:
 *  - Filling a Float32 array with a unique value per index so we can check exact slice
 *    boundaries by inspecting the first/last sample of the result.
 *  - Verifying short inputs pass through unchanged (no truncation happens, and we return
 *    the original array reference rather than a copy — cheaper allocations matter on the
 *    audio hot path that fires every 30 s).
 *  - Verifying long inputs return EXACTLY the trailing slice of length maxSamples.
 *
 * Why we test the helper rather than the full transcribe method: GemmaAudioEngine's
 * transcribe() depends on a live LiteRT-LM Conversation with 3.6 GB of weights loaded;
 * exercising it from JVM unit tests is impossible. The truncation logic is the bug and
 * is pure-Kotlin — extracted to [gemmaAudioTruncatedTail] (file-internal) so this test can
 * pin the slice math without booting Gemma.
 */
class GemmaAudioEngineTruncationTest {

    @Test
    fun shortInput_returnsUnchanged_andSameInstance() {
        // 16 kHz * 5 s = 80 000 samples, well under the 30-s cap.
        val maxSamples = 16_000 * 30
        val input = FloatArray(80_000) { idx -> idx.toFloat() }
        val out = gemmaAudioTruncatedTail(input, maxSamples)

        // Same instance — no allocation on the hot path when we don't need to truncate.
        assertSame(
            "When input is shorter than maxSamples, helper must return the same array " +
                "instance — copying every chunk on the audio hot path is wasted GC pressure.",
            input,
            out,
        )
        assertEquals(80_000, out.size)
    }

    @Test
    fun exactBoundary_returnsUnchanged() {
        // Exactly 30 s of samples. Helper must NOT truncate (only `>` triggers the slice).
        val maxSamples = 16_000 * 30
        val input = FloatArray(maxSamples) { idx -> idx.toFloat() }
        val out = gemmaAudioTruncatedTail(input, maxSamples)

        assertSame(
            "Exact-boundary input must not trigger truncation. The previous bug also " +
                "left this case alone — but the inverse contract (don't allocate when we " +
                "don't have to) was implicit and worth pinning.",
            input,
            out,
        )
        assertEquals(maxSamples, out.size)
    }

    @Test
    fun longInput_keepsTheLastMaxSamples_notTheFirst() {
        // 31 s of samples (1 s over the cap). Helper must keep the LAST 30 s — i.e. the
        // sample at index (size - maxSamples) becomes out[0], and the sample at index
        // (size - 1) becomes out[maxSamples - 1].
        val maxSamples = 16_000 * 30
        val totalSamples = 16_000 * 31
        val input = FloatArray(totalSamples) { idx -> idx.toFloat() }

        val out = gemmaAudioTruncatedTail(input, maxSamples)

        assertEquals(
            "Truncated output must be exactly maxSamples long.",
            maxSamples,
            out.size,
        )
        // The first sample of the OUTPUT should be sample (totalSamples - maxSamples) of
        // the INPUT — i.e. 16_000. Not 0 — which is what the prior bug produced (it kept
        // the first 30 s).
        assertEquals(
            "F5.1: helper must keep the LAST maxSamples. The first output sample should " +
                "be the (totalSamples - maxSamples)-th input sample, not the 0th. The bug " +
                "we're fixing returned floatPcm.copyOfRange(0, maxSamples) — that would " +
                "make this assertion fail with out[0] == 0.0f.",
            (totalSamples - maxSamples).toFloat(),
            out[0],
            0.0f,
        )
        // The last sample of the output should be the very last sample of the input.
        assertEquals(
            "Last output sample must be the last input sample (the 'newest' audio + the " +
                "2-s overlap window we depend on for adjacency dedup).",
            (totalSamples - 1).toFloat(),
            out[out.size - 1],
            0.0f,
        )
    }

    @Test
    fun longInput_dropsExactlyTheLeadingPrefix() {
        // Use a tiny maxSamples so we can compare arrays by value. 4-sample cap, 7-sample
        // input → output should be exactly the last 4 samples.
        val maxSamples = 4
        val input = floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f, 6f)
        val out = gemmaAudioTruncatedTail(input, maxSamples)
        assertArrayEquals(
            "Helper must return exactly the trailing slice of length maxSamples.",
            floatArrayOf(3f, 4f, 5f, 6f),
            out,
            0.0f,
        )
    }
}
