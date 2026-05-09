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

package com.google.ai.edge.gallery.bidet.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration

/**
 * Word-level diff highlighter for the v0.2 "Show me what changed" toggle.
 *
 * Why this exists (Vygotskyan-scaffolding rationale, per the brief): showing the speaker
 * which words the model added, removed, or kept preserves the speaker's authorship over the
 * output and counters the cognitive-skill-decay critique of unconditional AI rewriting. A
 * speaker who can see what the model did to their words can choose to push back.
 *
 * Algorithm: classic Hirschberg-flavoured longest-common-subsequence over whitespace-split
 * tokens. Pure Kotlin, no library, O(N*M) time and O(N*M) memory. The brain-dump RAW + tab
 * output sit comfortably under a few thousand words each (Mark's median session is ~2 min,
 * ~300 spoken words; outputs are similarly capped by maxOutputTokens), so the matrix is at
 * most a few-million-cell int[]. Acceptable on-device.
 *
 * Output is an [AnnotatedString] rather than a plain string so the calling Composable can
 * render insertions in green and deletions struck through in one Text() call.
 */
object DiffHighlighter {

    /** Color used to tint inserted (model-added) text. */
    private val INSERT_COLOR: Color = Color(0xFF1B5E20) // Material green-900

    /** Color used to dim and strike through deleted (model-dropped) text. */
    private val DELETE_COLOR: Color = Color(0xFFB71C1C) // Material red-900

    /**
     * Build an [AnnotatedString] that shows [generated] with insertions tinted green and
     * deletions from [raw] struck-through-and-tinted in red. Unchanged tokens render in the
     * caller's default text color.
     *
     * @param raw       the original RAW transcript the model started from.
     * @param generated the model's output (Clean for me / Clean for others tab body).
     */
    fun annotate(raw: String, generated: String): AnnotatedString {
        val rawTokens = tokenize(raw)
        val genTokens = tokenize(generated)

        val ops = lcsDiff(rawTokens, genTokens)
        val builder = AnnotatedString.Builder()

        for (op in ops) {
            when (op) {
                is DiffOp.Equal -> builder.append(op.text)
                is DiffOp.Insert -> {
                    builder.pushStyle(SpanStyle(color = INSERT_COLOR))
                    builder.append(op.text)
                    builder.pop()
                }
                is DiffOp.Delete -> {
                    builder.pushStyle(
                        SpanStyle(
                            color = DELETE_COLOR,
                            textDecoration = TextDecoration.LineThrough,
                        )
                    )
                    builder.append(op.text)
                    builder.pop()
                }
            }
        }

        return builder.toAnnotatedString()
    }

    /**
     * Token = either a whitespace run or a non-whitespace run. Preserving the original
     * whitespace (rather than collapsing to single spaces) means the diff output looks like
     * the original paragraph structure — newlines and indentation render correctly.
     */
    internal fun tokenize(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val start = i
            val isWs = text[i].isWhitespace()
            while (i < text.length && text[i].isWhitespace() == isWs) i++
            out.add(text.substring(start, i))
        }
        return out
    }

    sealed interface DiffOp {
        val text: String
        data class Equal(override val text: String) : DiffOp
        data class Insert(override val text: String) : DiffOp
        data class Delete(override val text: String) : DiffOp
    }

    /**
     * Classic O(N*M) LCS-based diff. Returns a flat list of [DiffOp]s in order. Adjacent
     * ops of the same kind are coalesced for cheaper rendering (one SpanStyle push per run
     * instead of one per token).
     */
    internal fun lcsDiff(a: List<String>, b: List<String>): List<DiffOp> {
        val n = a.size
        val m = b.size
        // dp[i][j] = LCS length of a[0..i) and b[0..j).
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) {
            for (j in 1..m) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        // Walk back from (n, m) to (0, 0) building ops in reverse.
        val rev = ArrayDeque<DiffOp>()
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            if (a[i - 1] == b[j - 1]) {
                rev.addFirst(DiffOp.Equal(b[j - 1]))
                i--; j--
            } else if (dp[i - 1][j] > dp[i][j - 1]) {
                // Strict > so that ties on a substitution prefer the Insert-then-Delete
                // walk-back direction. Because we prepend via addFirst, that yields
                // Delete-then-Insert in the final forward-order list — which is the
                // human-readable substitution order ("removed cat, added dog").
                rev.addFirst(DiffOp.Delete(a[i - 1]))
                i--
            } else {
                rev.addFirst(DiffOp.Insert(b[j - 1]))
                j--
            }
        }
        while (i > 0) { rev.addFirst(DiffOp.Delete(a[i - 1])); i-- }
        while (j > 0) { rev.addFirst(DiffOp.Insert(b[j - 1])); j-- }

        // Coalesce adjacent same-kind ops so the AnnotatedString has fewer style spans.
        val out = mutableListOf<DiffOp>()
        for (op in rev) {
            val last = out.lastOrNull()
            if (last != null && last::class == op::class) {
                out[out.lastIndex] = when (last) {
                    is DiffOp.Equal -> DiffOp.Equal(last.text + op.text)
                    is DiffOp.Insert -> DiffOp.Insert(last.text + op.text)
                    is DiffOp.Delete -> DiffOp.Delete(last.text + op.text)
                }
            } else {
                out.add(op)
            }
        }
        return out
    }
}
