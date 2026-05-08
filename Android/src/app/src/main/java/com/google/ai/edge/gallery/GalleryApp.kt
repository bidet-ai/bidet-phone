/*
 * Copyright 2025 Google LLC
 * Modifications Copyright 2026 bidet-ai contributors. Changed: replace GalleryNavHost entry with BidetMainScreen — Phase 2 wires the four-tab brain-dump UX as the app's start destination.
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

package com.google.ai.edge.gallery

import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.bidet.ui.BidetMainScreen
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

/**
 * Top level composable representing the main screen of the application.
 *
 * Phase 2: bidet-phone routes directly to [BidetMainScreen], replacing the upstream Gallery
 * task picker / nav graph entry. The legacy GalleryNavHost / HomeScreen / model picker are
 * deferred — the contest brief locks bidet-phone to a single brain-dump UX.
 *
 * The [modelManagerViewModel] parameter is retained for binary compatibility with
 * [MainActivity]'s call site (and so the upstream model-download lifecycle observers in
 * [ModelManagerViewModel] still construct, e.g. allowlist load, foreground tracking). The
 * bidet UI itself accesses the LLM via the [com.google.ai.edge.gallery.bidet.ui.BidetGemmaClient]
 * binding from [com.google.ai.edge.gallery.bidet.di.BidetModule], not via this view-model.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun GalleryApp(modelManagerViewModel: ModelManagerViewModel) {
    BidetMainScreen()
}
