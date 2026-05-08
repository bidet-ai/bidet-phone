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

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.google.ai.edge.gallery.R

/**
 * FORAI tab — structured markdown for AI consumption. The anchor of the four tabs (per brief
 * Design Center). Demos and marketing copy lead with this output. The content is a structured
 * markdown brain-dump cleaned of filler and reorganized into a format the user pastes into
 * Claude / ChatGPT / Gemini as input.
 */
@Composable
fun ForaiTabContent(state: TabState, onGenerate: () -> Unit) {
    GeneratableTabBody(
        state = state,
        generateLabel = stringResource(R.string.bidet_generate_button),
        idleHint = "Tap Generate to produce structured markdown ready to paste into your AI of choice.",
        onGenerate = onGenerate,
    )
}
