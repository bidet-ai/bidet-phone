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

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Minimal stub Application for this Robolectric test. The real
 * [com.google.ai.edge.gallery.GalleryApplication] is @HiltAndroidApp and its
 * onCreate() reads DataStore + initializes WorkManager + injected engine
 * providers — none of which the pure-Compose Clean-tab render needs, and
 * which throw under Robolectric. Scoped to this class via @Config so it does
 * not affect any other (current or future) Robolectric test.
 */
class CleanTabTestApplication : Application()

/**
 * v26 (2026-05-14): the verification gate v22→v25 never had.
 *
 * Every Clean tab (Clean for Me / RECEPTIVE, Clean for Others / EXPRESSIVE,
 * Clean for Judges / JUDGES) MUST show a tappable "Generate" button when the
 * tab is in [TabState.Idle] (no cached output, Gemma not busy).
 *
 * Why this test exists / what it would have caught:
 *
 *  - v25 fix3 (commit 8f9f88d) moved the Idle-state Generate button INTO
 *    [InlinePromptEditor]'s Column, appended after the prompt block. With
 *    v25's RAW 2f / Clean 1f weight split the Clean Box is ~33% of remaining
 *    height; CleanTabContent's parent Column does not scroll, so for the two
 *    inline-prompt axes (EXPRESSIVE / JUDGES) the editor's prompt header +
 *    preview consumed the Box and the appended button was clipped off the
 *    bottom — `assertIsDisplayed()` would FAIL on v25's code for those axes.
 *  - RECEPTIVE has no inlinePrompt; on v25 its only button lived inside the
 *    verticalScroll'd CleanTabBody, pushed below the fold in the tiny pane.
 *
 * The v26 fix renders the button via [CleanTabActionButton] in a fixed,
 * non-scrolling slot at the top of CleanTabContent's Column — so it is on
 * screen for all three axes. This test runs once per axis.
 *
 * Robolectric (not androidTest) so it runs under `./gradlew :app:test` with
 * no device — the gate must be cheap enough to never be skipped again.
 *
 * sdk=34 keeps it on a Compose-supported, Robolectric-shipped Android level.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CleanTabTestApplication::class)
class CleanTabGenerateButtonTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun previewInlinePrompt() = InlinePromptState(
        currentPrompt = "Rewrite this brain dump cleanly. Keep every fact.",
        defaultPrompt = "Rewrite this brain dump cleanly. Keep every fact.",
        onSavePrompt = {},
    )

    private fun assertIdleShowsGenerate(
        axis: SupportAxis,
        inlinePrompt: InlinePromptState?,
    ) {
        composeTestRule.setContent {
            GalleryTheme {
                CleanTabContent(
                    axis = axis,
                    state = TabState.Idle,
                    onGenerate = {},
                    inlinePrompt = inlinePrompt,
                )
            }
        }
        // The button label is the bidet_generate_button string resource ("Generate").
        //
        // EXACTLY ONE node, and it must be displayed. This is deliberately strict:
        // v25's regression rendered TWO "Generate" buttons on EXPRESSIVE / JUDGES
        // (one in the collapsible InlinePromptEditor, one buried in the scrolling
        // CleanTabBody) — duplicated, conflicting affordances. onNodeWithText
        // throws on >1 match, so this single call FAILS on v25 and PASSES on v26
        // (one authoritative button from CleanTabActionButton). The all-nodes
        // count assertion documents the intent explicitly.
        composeTestRule
            .onAllNodesWithText("Generate")
            .assertCountEquals(1)
        composeTestRule
            .onNodeWithText("Generate")
            .assertExists(
                "Clean tab ($axis) in Idle state must render exactly one 'Generate' " +
                    "button. On v25 it was clipped inside the collapsible prompt " +
                    "editor and/or duplicated in the scroll body.",
            )
        composeTestRule
            .onNodeWithText("Generate")
            .assertIsDisplayed()
    }

    /** RECEPTIVE — Clean for Me. No inline prompt; button must NOT be in the scroller. */
    @Test
    fun cleanTab_idleState_showsGenerateButton_receptive() {
        assertIdleShowsGenerate(SupportAxis.RECEPTIVE, inlinePrompt = null)
    }

    /** EXPRESSIVE — Clean for Others. Inline prompt present (collapsed). */
    @Test
    fun cleanTab_idleState_showsGenerateButton_expressive() {
        assertIdleShowsGenerate(SupportAxis.EXPRESSIVE, inlinePrompt = previewInlinePrompt())
    }

    /** JUDGES — Clean for Judges. Inline prompt present (collapsed). */
    @Test
    fun cleanTab_idleState_showsGenerateButton_judges() {
        assertIdleShowsGenerate(SupportAxis.JUDGES, inlinePrompt = previewInlinePrompt())
    }
}
