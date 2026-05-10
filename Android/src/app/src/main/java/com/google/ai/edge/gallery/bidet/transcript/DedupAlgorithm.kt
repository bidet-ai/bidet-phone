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

package com.google.ai.edge.gallery.bidet.transcript

/**
 * The one defined dedup algorithm for stitching consecutive ASR chunks together (Moonshine
 * on the moonshine flavor; Gemma audio on the gemma flavor).
 *
 * Each chunk emitted by [com.google.ai.edge.gallery.bidet.audio.AudioCaptureEngine] overlaps
 * the previous chunk's tail by ~2 seconds, which protects the ASR from cutting words in half.
 * That overlap means consecutive transcripts share a suffix-prefix of repeated words; we strip
 * it deterministically rather than asking Gemma to do it.
 *
 * Spec (see brief §5):
 *  - Compare the last 200 chars of [prev] with the first 250 chars of [next], both lowercased
 *    and whitespace-stripped (the "stripped" form).
 *  - Find the longest tail-suffix of `prev_stripped` that matches a prefix of `next_stripped`
 *    with at most 2 mismatches per 50 chars (Levenshtein edit distance ≤ matchLen / 25).
 *  - On match, cut [next] at the position-mapped offset (back into the original-cased,
 *    whitespace-preserved string) and concatenate.
 *  - On no match (matchLen 0): just concatenate with a single space.
 *
 * The minimum match length is 10 chars — anything shorter is too likely to be a false-positive
 * noise match on common short word fragments.
 */
object DedupAlgorithm {

    /** Last [tailWindow] chars of prev are searched. */
    const val TAIL_WINDOW: Int = 200

    /** First [headWindow] chars of next are searched. */
    const val HEAD_WINDOW: Int = 250

    /** Below this match length, we treat it as no overlap (false-positive bias on short matches). */
    const val MIN_MATCH_LEN: Int = 10

    /**
     * Merges two transcript fragments by deterministic fuzzy suffix-prefix overlap.
     *
     * @param prev the running transcript so far. May be empty.
     * @param next the new chunk's transcript. May be empty.
     * @return the merged transcript with the overlap region (if any) appearing exactly once.
     */
    fun mergeWithDedup(prev: String, next: String): String {
        if (prev.isEmpty()) return next
        if (next.isEmpty()) return prev

        // Normalize: lowercase + drop whitespace.
        val tail = prev.takeLast(TAIL_WINDOW).lowercase().filterNot { it.isWhitespace() }
        val head = next.take(HEAD_WINDOW).lowercase().filterNot { it.isWhitespace() }

        if (tail.isEmpty() || head.isEmpty()) return prev + " " + next

        // Search longest tail-suffix → head-prefix match (longest-wins per brief §5).
        var bestMatchLen = 0
        var bestNextOffset = 0
        for (matchLen in tail.length downTo MIN_MATCH_LEN) {
            val tailSuffix = tail.takeLast(matchLen)
            val maxEdits = matchLen / 25 // ≤2 mismatches per 50 chars
            // Walk possible starting offsets in head; first hit at this matchLen wins.
            var found = false
            val maxStart = head.length - matchLen
            if (maxStart < 0) continue
            for (start in 0..maxStart) {
                val candidate = head.substring(start, start + matchLen)
                val edits = levenshtein(tailSuffix, candidate)
                if (edits <= maxEdits) {
                    bestMatchLen = matchLen
                    bestNextOffset = start + matchLen
                    found = true
                    break
                }
            }
            if (found) break
        }

        if (bestMatchLen == 0) return prev + " " + next

        // Map the offset in the stripped (lowercased, whitespace-removed) `next.take(headWindow)`
        // back to the original-cased, whitespace-preserved `next`.
        val originalCutInWindow = mapStrippedOffsetToOriginal(
            original = next.take(HEAD_WINDOW),
            strippedOffset = bestNextOffset,
        )
        return prev + next.substring(originalCutInWindow)
    }

    /**
     * Maps an offset in the stripped (whitespace-removed) version of [original] back to the
     * corresponding index in [original]. Used to convert the matcher's offset (which operates
     * on the stripped form) back into a cut point in the original-cased text.
     *
     * @return the index in [original] just past the [strippedOffset]-th non-whitespace char.
     *   If [strippedOffset] equals or exceeds the count of non-whitespace chars, returns the
     *   end of [original].
     */
    fun mapStrippedOffsetToOriginal(original: String, strippedOffset: Int): Int {
        if (strippedOffset <= 0) return 0
        var strippedCounter = 0
        for ((i, ch) in original.withIndex()) {
            if (!ch.isWhitespace()) {
                strippedCounter++
                if (strippedCounter == strippedOffset) {
                    // Return position just AFTER this non-whitespace char (i+1), so the substring
                    // beginning at the returned index excludes the matched overlap.
                    return i + 1
                }
            }
        }
        return original.length
    }

    /**
     * Classic two-row iterative Levenshtein distance. Inline; no dependency added.
     *
     * Time: O(|a| · |b|). Space: O(min(|a|, |b|)). For our windows (≤200 × ≤250) this is
     * trivially fast and called at most ~190 times per chunk merge.
     */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }
}
