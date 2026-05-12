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

package com.google.ai.edge.gallery.bidet.cleaning

import com.google.ai.edge.gallery.bidet.ui.BidetGemmaClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay

/**
 * JVM test double for the LiteRT-LM-backed [BidetGemmaClient].
 *
 * Motivation
 * ----------
 * v18.7 (2026-05-10) shipped an inference mutex in [com.google.ai.edge.gallery.bidet.ui.LiteRtBidetGemmaClient]
 * to fix a native SIGSEGV inside liblitertlm_jni.so at offset 0x4c9060. The crash only
 * surfaced on a Tensor G3 with a real 3.6 GB Gemma 4 model — three hours of debugging on
 * the phone to find it. This fake reproduces the *class* of bug (two callers entering the
 * inference path at the same time) in deterministic JVM unit-test form so any future change
 * that bypasses the production mutex fails CI in 30 seconds instead of crashing the phone.
 *
 * Detection model
 * ---------------
 * Each call to [runInference]/[runInferenceStreaming] increments [activeCallCount] on entry,
 * sleeps for [latencyMillis], and decrements on exit. If the counter ever exceeds 1 we record
 * the overlap in [concurrentEntryDetected] and throw an [IllegalStateException]. The thrown
 * exception is the deterministic stand-in for the native SIGSEGV — it's what LiteRT-LM
 * "would have done" if it were a polite library.
 *
 * The detection runs even when callers don't `await` the throw — the [concurrentEntryDetected]
 * flag captures the violation for assertion in tests that want to inspect state rather than
 * catch.
 *
 * Threading
 * ---------
 * [calls] uses [CopyOnWriteArrayList] so test assertions don't race with in-flight
 * appends. [activeCallCount] is an [AtomicInteger]. The fake is safe to share across
 * coroutines, which is exactly the property the production code needs.
 *
 * What this fake does NOT model
 * -----------------------------
 *  - Real model output. [responseText] is returned verbatim with the user prompt
 *    appended so tests that care about output content can pin a unique echo.
 *  - LiteRT-LM streaming chunk granularity. [runInferenceStreaming] fires [onChunk] once
 *    with the full response. Tests that need fine-grained streaming behavior should mock
 *    that separately.
 *  - Token-aware truncation, KV cache, sampler config. The production mutex sits OUTSIDE
 *    all of that, so the fake doesn't need to either.
 */
class FakeGemmaEngine(
    /** Wall-clock time each call holds the "inference path open" before returning. */
    private val latencyMillis: Long = DEFAULT_LATENCY_MILLIS,
    /** Static response fragment returned by every call (real text gets appended). */
    private val responseText: String = "fake-clean: ",
) : BidetGemmaClient {

    /**
     * One entry per [runInference]/[runInferenceStreaming] call, captured at entry time.
     * [CopyOnWriteArrayList] keeps test reads cheap and race-free without an explicit lock.
     */
    data class CallRecord(
        val systemPrompt: String,
        val userPrompt: String,
        /** Monotonic-ish timestamp at call entry. */
        val timestampMillis: Long,
        /** Name of the thread that entered the call — handy for spotting dispatch issues. */
        val threadName: String,
    )

    val calls: MutableList<CallRecord> = CopyOnWriteArrayList()

    /**
     * Number of calls currently inside the latency window. Production code that holds the
     * mutex correctly should keep this <= 1 at all times. We expose it so tests can poll
     * "is the first call still running?" without needing wall-clock tuning.
     */
    val activeCallCount: AtomicInteger = AtomicInteger(0)

    /**
     * Flips true the first time two calls overlap. Sticky — once set, stays set. Tests can
     * assert this is false after a serialized run, or true after a deliberately racy run.
     */
    @Volatile var concurrentEntryDetected: Boolean = false
        private set

    /** Highest [activeCallCount] ever observed. Useful for tests that want a >1 assertion. */
    @Volatile var peakActiveCallCount: Int = 0
        private set

    override suspend fun runInference(
        systemPrompt: String,
        userPrompt: String,
        maxOutputTokens: Int,
        temperature: Float,
    ): String = runInferenceStreaming(
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        maxOutputTokens = maxOutputTokens,
        temperature = temperature,
        onChunk = { _, _ -> /* non-streaming caller drops chunks */ },
    )

    override suspend fun runInferenceStreaming(
        systemPrompt: String,
        userPrompt: String,
        maxOutputTokens: Int,
        temperature: Float,
        onChunk: (cumulativeText: String, chunkIndex: Int) -> Unit,
    ): String {
        // Increment the active counter FIRST so a racing call sees the overlap immediately.
        // The increment+check is two ops, but since we hold the result of incrementAndGet we
        // can reason about it atomically per call: a counter value of 2 here means there's
        // another call currently in flight whose decrement hasn't run yet.
        val active = activeCallCount.incrementAndGet()
        try {
            if (active > peakActiveCallCount) peakActiveCallCount = active
            calls.add(
                CallRecord(
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    timestampMillis = System.currentTimeMillis(),
                    threadName = Thread.currentThread().name,
                )
            )
            if (active > 1) {
                concurrentEntryDetected = true
                throw IllegalStateException(
                    "concurrent inference: $active calls active. This is the JVM stand-in for " +
                        "the native SIGSEGV in liblitertlm_jni.so at 0x4c9060 — production " +
                        "code must hold the inference mutex around the full suspend body."
                )
            }
            // Hold the call open for the configured latency window. Using kotlinx.coroutines
            // delay() means runTest's virtual scheduler can fast-forward it — tests don't
            // pay 100ms per call wall-clock.
            delay(latencyMillis)
            val result = responseText + userPrompt
            onChunk(result, 1)
            return result
        } finally {
            activeCallCount.decrementAndGet()
        }
    }

    companion object {
        /**
         * Default per-call latency. Matches the brief's "default 100ms" — large enough that
         * a racy test on a real wall-clock dispatcher will reliably overlap, small enough that
         * a serialized run stays under one second total even for a dozen calls.
         */
        const val DEFAULT_LATENCY_MILLIS: Long = 100L
    }
}
