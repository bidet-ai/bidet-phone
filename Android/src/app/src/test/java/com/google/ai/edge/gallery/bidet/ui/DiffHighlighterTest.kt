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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2 unit tests for [DiffHighlighter] — the word-level diff driving the "Show me what
 * changed" toggle. We test the internal LCS algorithm directly because the public [annotate]
 * surface returns an [androidx.compose.ui.text.AnnotatedString], which would drag in the
 * Compose runtime + Robolectric for a JVM unit test. The internal API is the same call shape
 * as the production path; the AnnotatedString builder is a thin wrapper.
 */
class DiffHighlighterTest {

    @Test
    fun tokenize_preservesWhitespaceRuns() {
        // Whitespace runs are tokens too — diff output must keep newlines + indentation.
        val toks = DiffHighlighter.tokenize("hello   world\nfoo")
        assertEquals(listOf("hello", "   ", "world", "\n", "foo"), toks)
    }

    @Test
    fun emptyInputs_produceEmptyOpList() {
        val ops = DiffHighlighter.lcsDiff(emptyList(), emptyList())
        assertTrue(ops.isEmpty())
    }

    @Test
    fun identicalInputs_produceOnlyEqualOps() {
        val a = listOf("foo", " ", "bar")
        val ops = DiffHighlighter.lcsDiff(a, a)
        assertEquals(1, ops.size) // coalesced into a single Equal run
        assertTrue(ops[0] is DiffHighlighter.DiffOp.Equal)
        assertEquals("foo bar", ops[0].text)
    }

    @Test
    fun pureInsertion_atTail_isMarkedInsert() {
        val a = DiffHighlighter.tokenize("hello")
        val b = DiffHighlighter.tokenize("hello world")
        val ops = DiffHighlighter.lcsDiff(a, b)
        // Expect: Equal("hello"), Insert(" world")
        assertEquals(2, ops.size)
        assertTrue(ops[0] is DiffHighlighter.DiffOp.Equal)
        assertEquals("hello", ops[0].text)
        assertTrue(ops[1] is DiffHighlighter.DiffOp.Insert)
        assertEquals(" world", ops[1].text)
    }

    @Test
    fun pureDeletion_atTail_isMarkedDelete() {
        val a = DiffHighlighter.tokenize("hello world")
        val b = DiffHighlighter.tokenize("hello")
        val ops = DiffHighlighter.lcsDiff(a, b)
        assertEquals(2, ops.size)
        assertTrue(ops[0] is DiffHighlighter.DiffOp.Equal)
        assertEquals("hello", ops[0].text)
        assertTrue(ops[1] is DiffHighlighter.DiffOp.Delete)
        assertEquals(" world", ops[1].text)
    }

    @Test
    fun substitution_producesDeleteThenInsert() {
        val a = DiffHighlighter.tokenize("the cat sat")
        val b = DiffHighlighter.tokenize("the dog sat")
        val ops = DiffHighlighter.lcsDiff(a, b)
        // Expect: Equal("the "), Delete("cat"), Insert("dog"), Equal(" sat")
        // Adjacent same-kind ops are coalesced; "Delete then Insert" stays separate (they
        // are different op types).
        val flat = ops.joinToString(separator = "|") {
            "${it::class.simpleName}<${it.text}>"
        }
        // The exact LCS output shape is "Equal('the '), Delete('cat'), Insert('dog'),
        // Equal(' sat')" because the token sequence is [the][ ][cat][ ][sat] vs
        // [the][ ][dog][ ][sat] and only the third token differs.
        assertEquals(
            "Equal<the >|Delete<cat>|Insert<dog>|Equal< sat>",
            flat,
        )
    }

    @Test
    fun reorderedTokens_diffStillRoundtrips() {
        // Sanity: reconstructing [generated] from the diff (Equal + Insert kept; Delete
        // skipped) must reproduce the model output verbatim. Reconstructing [raw] (Equal +
        // Delete kept; Insert skipped) must reproduce the original RAW. This proves the
        // diff is lossless on both sides.
        val raw = "the quick brown fox jumps over the lazy dog"
        val gen = "a quick brown fox jumps over a lazy dog and barks"
        val ops = DiffHighlighter.lcsDiff(
            DiffHighlighter.tokenize(raw),
            DiffHighlighter.tokenize(gen),
        )
        val reconstructedGen = ops
            .filter { it !is DiffHighlighter.DiffOp.Delete }
            .joinToString("") { it.text }
        val reconstructedRaw = ops
            .filter { it !is DiffHighlighter.DiffOp.Insert }
            .joinToString("") { it.text }
        assertEquals(gen, reconstructedGen)
        assertEquals(raw, reconstructedRaw)
    }
}
