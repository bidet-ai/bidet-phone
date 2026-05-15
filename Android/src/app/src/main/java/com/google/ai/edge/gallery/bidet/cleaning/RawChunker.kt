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

import kotlin.math.max

/**
 * Sliding-window splitter for long RAW transcripts. Gemma 4 E2B has a hard 2048-token
 * context cap (see [com.google.ai.edge.gallery.bidet.ui.LiteRtBidetGemmaClient.ENGINE_CONTEXT_BUDGET]
 * for why we don't bump it — bigger KV cache halves per-token decode speed on Tensor G3).
 *
 * Sizing math
 * -----------
 * Each runInference prefill = system prompt + user window. With per-chunk output cap = 512
 * tokens reserved for decode, the per-call INPUT ceiling is roughly:
 *
 *   inputBudgetTokens = 2048 - 512 - systemPromptTokens
 *
 * Pre-v18.8 the system prompt was ~250-300 tokens and 2400 chars of user input
 * (~700 tokens at 3.5 chars/token) fit comfortably. After v18.8 added the project-noun
 * glossary (~400 tokens) and v20 added the longer "Clean for judges" prompt
 * (~1685-char base → ~800-1500 tokens with glossary), 2400-char windows can overflow:
 *   judges system (~1500 tok) + window (~1200 tok) + output reserve (~512 tok) = 3200 > 2048.
 * That's the failure mode behind "input token IDs too long ... 2634 ≥ 1024" on long dumps.
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

    /**
     * Convenience wrapper that derives a safe [maxChars] from the actual [systemPrompt]
     * length so each axis (RECEPTIVE / EXPRESSIVE / JUDGES) gets the largest window that
     * still fits in the engine's prefill budget. The "Clean for judges" prompt is roughly
     * 3x the others (1685 vs ~600 chars base, then both get the ~1411-char glossary), so
     * applying a single hard-coded 2400-char threshold to all three was the proximate
     * cause of the "2634 ≥ 1024" overflow on long dumps. Using this helper from the call
     * site makes the chunk size track the prompt size automatically.
     *
     * Math (all in tokens; we convert chars↔tokens at the conservative 2.5 chars/token
     * ratio so Mark-jargon and dense punctuation don't blow the budget):
     *
     *   inputTokenBudget = ENGINE_CONTEXT_BUDGET - perChunkOutputCap - systemPromptTokens
     *   maxChars         = inputTokenBudget * CHARS_PER_TOKEN_CONSERVATIVE
     *
     * Floored at [MIN_SAFE_MAX_CHARS] so a pathologically long system prompt never
     * collapses windows to a useless size — better to risk one overflow with a loud
     * error than to silently produce 80 noise-sized windows.
     *
     * @param raw the user's RAW transcript.
     * @param systemPrompt the resolved system prompt including any glossary wrap. The
     *   length of this string drives the per-window budget calculation.
     * @param engineContextBudget total prefill+decode budget of the LiteRT-LM engine.
     *   Defaults to [DEFAULT_ENGINE_CONTEXT_BUDGET] which matches the value pinned in
     *   [com.google.ai.edge.gallery.bidet.ui.LiteRtBidetGemmaClient].
     * @param perChunkOutputCap reservation for the model's output during each window's
     *   decode. Defaults to [DEFAULT_PER_CHUNK_OUTPUT_CAP] = 512 to match
     *   [com.google.ai.edge.gallery.bidet.service.CleanGenerationService.CHUNKED_PER_WINDOW_OUTPUT_TOKEN_CAP].
     * @param overlapChars overlap between adjacent windows; defaults to
     *   [DEFAULT_OVERLAP_CHARS].
     */
    fun chunkForPrompt(
        raw: String,
        systemPrompt: String,
        engineContextBudget: Int = DEFAULT_ENGINE_CONTEXT_BUDGET,
        perChunkOutputCap: Int = DEFAULT_PER_CHUNK_OUTPUT_CAP,
        overlapChars: Int = DEFAULT_OVERLAP_CHARS,
    ): List<String> {
        val systemTokens = estimateTokens(systemPrompt)
        val inputBudgetTokens = engineContextBudget - perChunkOutputCap - systemTokens
        val derivedMaxChars = max(
            MIN_SAFE_MAX_CHARS,
            (inputBudgetTokens * CHARS_PER_TOKEN_CONSERVATIVE).toInt(),
        )
        // Never go above the historical DEFAULT (2400) even if the system prompt is
        // unusually short — the original budget was tuned for decode-time-per-window as
        // well as memory headroom, not just the prefill ceiling.
        val safeMaxChars = derivedMaxChars.coerceAtMost(DEFAULT_MAX_CHARS)
        // Ensure overlap stays strictly less than maxChars or chunk() throws.
        val safeOverlap = overlapChars.coerceAtMost(safeMaxChars / 4)
        return chunk(raw, maxChars = safeMaxChars, overlapChars = safeOverlap)
    }

    /**
     * Conservative chars-per-token used by [chunkForPrompt]. We pick a low value so the
     * budget calculation errs toward smaller windows: English averages ~3.5 chars/token,
     * but Mark's transcripts often include code-like tokens (model versions, file paths,
     * proper nouns split into BPE pieces) that push real-world density to ~2-2.5
     * chars/token. Choosing 2.5 keeps long-tail safe without halving throughput.
     */
    private const val CHARS_PER_TOKEN_CONSERVATIVE: Double = 2.5

    /** Tokens-from-chars estimator that mirrors [CHARS_PER_TOKEN_CONSERVATIVE]. */
    private fun estimateTokens(text: String): Int =
        (text.length / CHARS_PER_TOKEN_CONSERVATIVE).toInt()

    /**
     * Engine context budget = 2048 tokens (see ENGINE_CONTEXT_BUDGET in LiteRtBidetGemmaClient
     * for why we don't bump it: bigger KV cache halves per-token decode speed on Tensor G3).
     *
     * Historical default: 2400 chars. Pre-v18.8 (no glossary) and pre-v20 (no judges
     * prompt) the per-call system prompt averaged ~250 tokens and 2400 chars of user
     * window (~700 tokens at 3.5 chars/token) fit comfortably. Today the receptive and
     * expressive prompts wrap with the glossary to ~600-1100 tokens system, and the
     * judges prompt reaches ~800-1500 tokens system. With 512 output reserved that
     * leaves only ~400 tokens of input headroom on JUDGES — a 2400-char window can be
     * 900-1200 tokens of dense input and overflow.
     *
     * [chunkForPrompt] is the path that solves this correctly; this constant remains as
     * a sane upper bound when callers don't know the system prompt up front (e.g.
     * tests).
     */
    const val DEFAULT_MAX_CHARS: Int = 2400

    /**
     * Conservative floor for [chunkForPrompt]. A long system prompt could in principle
     * derive a tiny per-window budget; floor at 800 chars so we don't fall off a cliff
     * into 100+ windows for a 20-min dump. If the floor is in play the run will still
     * succeed because the engine itself has the same hard ceiling — the worst case is a
     * single window that LiteRT-LM rejects with the same loud overflow error users saw
     * before the chunker existed.
     */
    const val MIN_SAFE_MAX_CHARS: Int = 800

    /** Small bridge between windows so cleaning sees the end of the previous thought. */
    const val DEFAULT_OVERLAP_CHARS: Int = 200

    /**
     * Matches [com.google.ai.edge.gallery.bidet.ui.LiteRtBidetGemmaClient.ENGINE_CONTEXT_BUDGET].
     * Kept here to avoid an import cycle — both constants must stay in lockstep.
     */
    const val DEFAULT_ENGINE_CONTEXT_BUDGET: Int = 2048

    /**
     * Matches [com.google.ai.edge.gallery.bidet.service.CleanGenerationService.CHUNKED_PER_WINDOW_OUTPUT_TOKEN_CAP].
     */
    const val DEFAULT_PER_CHUNK_OUTPUT_CAP: Int = 512

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
}
