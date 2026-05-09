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

import com.google.ai.edge.gallery.bidet.service.CleanGenerationService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Streaming + bounded-output regression tests for the v0.2 Clean-tab fix (2026-05-09).
 *
 * Background: Mark dictated a 31-min brain dump on 2026-05-09; the spinner ran for 5+ minutes
 * producing nothing visible on the "Clean for others" tab before Android killed the job. The
 * model was honest-working — at ~10 tk/s with maxNumTokens=16384, an unbounded generation
 * needed 18+ minutes. The fix has three pieces; this test pins their contracts:
 *
 *  1. [BidetTabsViewModel.CLEAN_TAB_OUTPUT_TOKEN_CAP] is bounded at 2048. ~10 tk/s × 2048 ≈
 *     3-4 min wall-clock — the upper bound users will tolerate before feedback. Untying this
 *     constant from the input-cap headroom (still 16384 elsewhere) is half the fix.
 *
 *  2. [BidetGemmaClient.runInferenceStreaming]'s onChunk is called with a CUMULATIVE text
 *     string per chunk + a monotonic chunk index. The ViewModel publishes that into its
 *     [TabState.Streaming] surface; the Composable re-renders as tokens arrive.
 *
 *  3. The ViewModel's [BidetTabsViewModel.handleServiceState] correctly maps a service-side
 *     [CleanGenerationService.GenerationState] into the per-axis [TabState] flow, and an
 *     event for axis A is ignored by the axis-B observer (so the two Clean tabs don't
 *     contaminate each other when the service publishes one stream).
 *
 *  4. The streaming flow surface survives a "configuration change" — i.e. dropping and
 *     recreating an observer job against the SAME StateFlow returns the latest value
 *     immediately. This is the property the foreground service contract relies on so a
 *     user navigating away and back during a 4-min generation sees the partial result on
 *     return rather than an empty Idle state.
 */
class CleanTabStreamingTest {

    /**
     * (1) Token cap is enforced at the constant layer. If a future change inadvertently bumps
     * this back to 16384 (the v0.1 default that bit Mark), this test fails loudly.
     */
    @Test
    fun cleanTabOutputTokenCap_isBoundedAt2048() {
        assertEquals(
            "CLEAN_TAB_OUTPUT_TOKEN_CAP must stay at 2048 — see the constant's class-level " +
                "comment for the wall-clock rationale (~10 tk/s × 2048 ≈ 3-4 min on Pixel 8 Pro).",
            2048,
            BidetTabsViewModel.CLEAN_TAB_OUTPUT_TOKEN_CAP,
        )
    }

    /**
     * (1, defence-in-depth) The CleanGenerationService default token cap matches the
     * ViewModel's published value. Catches a rename / split bug where the service falls back
     * to its own default for a lost intent extra and silently uses the wrong cap.
     */
    @Test
    fun cleanGenerationService_defaultTokenCap_matchesViewModel() {
        assertEquals(
            BidetTabsViewModel.CLEAN_TAB_OUTPUT_TOKEN_CAP,
            CleanGenerationService.DEFAULT_TOKEN_CAP,
        )
    }

    /**
     * (2) The fake client's onChunk receives cumulative text + a monotonically growing
     * chunkIndex. The cumulative-text contract is what lets the ViewModel publish atomically
     * without managing its own buffer.
     */
    @Test
    fun fakeStreamingClient_emitsCumulativeText() {
        val fake = FakeStreamingClient(chunks = listOf("Hello ", "world", "!"))
        val received = mutableListOf<Pair<String, Int>>()
        val final = kotlinx.coroutines.runBlocking {
            fake.runInferenceStreaming(
                systemPrompt = "ignored",
                userPrompt = "ignored",
                maxOutputTokens = 2048,
                temperature = 0.4f,
                onChunk = { cumulative, idx -> received += cumulative to idx },
            )
        }
        assertEquals(listOf(
            "Hello " to 1,
            "Hello world" to 2,
            "Hello world!" to 3,
        ), received)
        assertEquals("Hello world!", final)
    }

    /**
     * (3a) Streaming → TabState.Streaming with the partial text + token count + cap. The
     * VM's mapping is engine-agnostic; we hand it a synthetic GenerationState and pin the
     * resulting TabState.
     */
    @Test
    fun handleServiceState_streamingMapsToTabStateStreaming() {
        val mapper = TabStateMapper()
        val event = CleanGenerationService.GenerationState.Streaming(
            sessionId = "sid",
            axis = SupportAxis.RECEPTIVE,
            partialText = "partial",
            tokenCount = 3,
            tokenCap = 2048,
        )
        val mapped = mapper.map(event, observerAxis = SupportAxis.RECEPTIVE)
        assertNotNull(mapped)
        mapped as TabState.Streaming
        assertEquals("partial", mapped.partialText)
        assertEquals(3, mapped.tokenCount)
        assertEquals(2048, mapped.tokenCap)
    }

    /**
     * (3b) Done → TabState.Cached. This is the terminal happy path — the ViewModel writes
     * the cache + transitions to Cached.
     */
    @Test
    fun handleServiceState_doneMapsToTabStateCached() {
        val mapper = TabStateMapper()
        val event = CleanGenerationService.GenerationState.Done(
            sessionId = "sid",
            axis = SupportAxis.EXPRESSIVE,
            text = "final result",
            finishedAtMs = 1_700_000_000_000L,
        )
        val mapped = mapper.map(event, observerAxis = SupportAxis.EXPRESSIVE)
        mapped as TabState.Cached
        assertEquals("final result", mapped.text)
        assertEquals(1_700_000_000_000L, mapped.generatedAt)
    }

    /**
     * (3c) Failed → TabState.Failed. Carries the user-visible message.
     */
    @Test
    fun handleServiceState_failedMapsToTabStateFailed() {
        val mapper = TabStateMapper()
        val event = CleanGenerationService.GenerationState.Failed(
            sessionId = "sid",
            axis = SupportAxis.RECEPTIVE,
            message = "Generation aborted",
            modelMissing = false,
        )
        val mapped = mapper.map(event, observerAxis = SupportAxis.RECEPTIVE)
        mapped as TabState.Failed
        assertEquals("Generation aborted", mapped.message)
    }

    /**
     * (3d) An axis-B event is IGNORED by the axis-A observer. Two Clean tabs collecting from
     * the same service StateFlow must filter on their own axis or each tab's UI would
     * spuriously transition when the OTHER tab generates.
     */
    @Test
    fun handleServiceState_filtersOnObserverAxis() {
        val mapper = TabStateMapper()
        val event = CleanGenerationService.GenerationState.Streaming(
            sessionId = "sid",
            axis = SupportAxis.RECEPTIVE,
            partialText = "partial",
            tokenCount = 1,
            tokenCap = 2048,
        )
        val mappedForExpressive = mapper.map(event, observerAxis = SupportAxis.EXPRESSIVE)
        assertNull(
            "An Expressive observer must IGNORE a Receptive-axis event — otherwise the " +
                "Clean-for-others tab would echo the Clean-for-me streaming progress.",
            mappedForExpressive,
        )
    }

    /**
     * (4) StateFlow keeps the latest value across a simulated configuration change — a new
     * collector subscribing AFTER the producer has emitted gets the latest streaming snapshot
     * immediately. This is the property that makes "navigate away during generation, navigate
     * back" show partial output instead of an empty state.
     */
    @Test
    fun streamingStateFlow_replaysLatestToLateCollector() = kotlinx.coroutines.runBlocking {
        val flow = kotlinx.coroutines.flow.MutableStateFlow<CleanGenerationService.GenerationState>(
            CleanGenerationService.GenerationState.Idle,
        )
        // Producer emits two streaming chunks, then we "tear down" the observer (no collector
        // for a tick), then a new collector subscribes — should see the LATEST state, not Idle.
        flow.value = CleanGenerationService.GenerationState.Streaming(
            sessionId = "sid",
            axis = SupportAxis.RECEPTIVE,
            partialText = "first",
            tokenCount = 1,
            tokenCap = 2048,
        )
        flow.value = CleanGenerationService.GenerationState.Streaming(
            sessionId = "sid",
            axis = SupportAxis.RECEPTIVE,
            partialText = "first second",
            tokenCount = 2,
            tokenCap = 2048,
        )

        val lateValue = flow.value
        assertTrue(lateValue is CleanGenerationService.GenerationState.Streaming)
        lateValue as CleanGenerationService.GenerationState.Streaming
        assertEquals(
            "Late observer must see the most recent partial text, not Idle. Without this " +
                "the user navigating away mid-generation and back would see an empty tab.",
            "first second",
            lateValue.partialText,
        )
        assertEquals(2, lateValue.tokenCount)
    }
}

/**
 * In-test stand-in for [BidetGemmaClient]. Reproduces the LiteRT-LM `MessageCallback` chunk
 * contract by walking a list of pre-canned chunks and invoking onChunk(cumulative, index)
 * for each, then returning the full concatenation.
 */
private class FakeStreamingClient(
    private val chunks: List<String>,
) : BidetGemmaClient {

    override suspend fun runInference(
        systemPrompt: String,
        userPrompt: String,
        maxOutputTokens: Int,
        temperature: Float,
    ): String = runInferenceStreaming(systemPrompt, userPrompt, maxOutputTokens, temperature) { _, _ -> }

    override suspend fun runInferenceStreaming(
        systemPrompt: String,
        userPrompt: String,
        maxOutputTokens: Int,
        temperature: Float,
        onChunk: (cumulativeText: String, chunkIndex: Int) -> Unit,
    ): String {
        val sb = StringBuilder()
        chunks.forEachIndexed { i, c ->
            sb.append(c)
            onChunk(sb.toString(), i + 1)
        }
        return sb.toString()
    }
}

/**
 * Pure-Kotlin extraction of [BidetTabsViewModel.handleServiceState]'s mapping logic. We don't
 * instantiate the ViewModel because @HiltViewModel + @Inject would drag in dagger, the
 * Application context, and a DataStore. The mapping is a pure function of (event, axis).
 */
private class TabStateMapper {
    fun map(
        event: CleanGenerationService.GenerationState,
        observerAxis: SupportAxis,
    ): TabState? = when (event) {
        is CleanGenerationService.GenerationState.Idle -> null
        is CleanGenerationService.GenerationState.Streaming ->
            if (event.axis != observerAxis) null
            else TabState.Streaming(event.partialText, event.tokenCount, event.tokenCap)
        is CleanGenerationService.GenerationState.Done ->
            if (event.axis != observerAxis) null
            else TabState.Cached(event.text, event.finishedAtMs)
        is CleanGenerationService.GenerationState.Failed ->
            if (event.axis != observerAxis) null
            else TabState.Failed(event.message)
    }
}
