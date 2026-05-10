/*
 * Copyright 2025 The Bidet AI authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.bidet.cleaning

/**
 * Sliding-window splitter for long RAW transcripts. Gemma 4 E2B has a hard 2048-token
 * context cap; with MAX_OUTPUT_TOKENS=1024 reserved and a system prompt of ~200-300 tokens,
 * the per-call user-input budget is ~700 tokens (≈2400 chars of English at 3.5 chars/token).
 *
 * Splits at sentence boundaries when possible, falls back to whitespace, finally hard-cuts.
 * Adjacent windows share a small overlap so cleaning prompts see enough context to resolve
 * mid-thought continuations.
 */
object RawChunker {

    /**
     * Split [raw] into windows of at most [maxChars] characters with [overlapChars] of
     * trailing context from the previous window prepended to the next. Returns a single
     * element list when the input already fits.
     */
    fun chunk(
        raw: String,
        maxChars: Int = DEFAULT_MAX_CHARS,
        overlapChars: Int = DEFAULT_OVERLAP_CHARS,
    ): List<String> {
        require(maxChars > overlapChars) { "maxChars must exceed overlapChars" }
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        if (text.length <= maxChars) return listOf(text)

        val sentences = splitSentences(text)
        val windows = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            val pieces = if (sentence.length <= maxChars) listOf(sentence) else hardSplit(sentence, maxChars)
            for (piece in pieces) {
                if (current.isEmpty()) {
                    current.append(piece)
                } else if (current.length + 1 + piece.length <= maxChars) {
                    current.append(' ').append(piece)
                } else {
                    windows.add(current.toString())
                    current.clear()
                    current.append(piece)
                }
            }
        }
        if (current.isNotEmpty()) windows.add(current.toString())

        if (windows.size <= 1 || overlapChars <= 0) return windows
        return windows.mapIndexed { index, window ->
            if (index == 0) {
                window
            } else {
                val tail = windows[index - 1].takeLast(overlapChars)
                val joinIndex = tail.indexOfFirst { it == ' ' }
                val cleanTail = if (joinIndex in 0 until tail.length - 1) tail.substring(joinIndex + 1) else tail
                if (cleanTail.isBlank()) window else "$cleanTail $window"
            }
        }
    }

    private fun splitSentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            sb.append(c)
            val isTerminator = c == '.' || c == '?' || c == '!'
            val nextIsBoundary = i + 1 >= text.length || text[i + 1].isWhitespace()
            if (isTerminator && nextIsBoundary) {
                val piece = sb.toString().trim()
                if (piece.isNotEmpty()) out.add(piece)
                sb.clear()
            }
            i++
        }
        val tail = sb.toString().trim()
        if (tail.isNotEmpty()) out.add(tail)
        return out
    }

    private fun hardSplit(s: String, maxChars: Int): List<String> {
        val parts = mutableListOf<String>()
        var rest = s
        while (rest.length > maxChars) {
            val cut = rest.lastIndexOf(' ', maxChars).let { if (it <= 0) maxChars else it }
            parts.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) parts.add(rest)
        return parts
    }

    /** Conservative budget: 2048 ctx - 1024 output - ~300 system prompt = ~700 input tokens ≈ 2400 chars. */
    const val DEFAULT_MAX_CHARS: Int = 2400

    /** Small bridge between windows so cleaning sees the end of the previous thought. */
    const val DEFAULT_OVERLAP_CHARS: Int = 200
}
