/*
 * Copyright 2025 Google LLC
 * Modifications Copyright 2026 bidet-ai contributors. Changed: drop Firebase init (bidet zero-telemetry hard rule); the firebase-bom + firebase-analytics + firebase-messaging deps are stripped from app/build.gradle.kts.
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

import android.app.Application
import com.google.ai.edge.gallery.bidet.llm.BidetSharedLiteRtEngineProvider
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
// v24 (2026-05-14): the prewarm coroutine + Dispatchers.IO scope were dropped along with
// the eager Gemma engine load. Imports kept minimal; the Hilt-injected
// sharedEngineProvider field is retained for graph-cohesion reasons (it's still in scope
// if a future v25 wants to add an opt-in advanced "warm Gemma in background" toggle).

@HiltAndroidApp
class GalleryApplication : Application() {

  @Inject lateinit var dataStoreRepository: DataStoreRepository

  /**
   * 2026-05-09: shared engine provider for the gemma flavor. We only USE this on the gemma
   * build (the moonshine flavor uses sherpa-onnx + Moonshine-Tiny directly), but Hilt still
   * injects it on both flavors — that's harmless since the singleton is just an idle handle
   * until something acquires the engine.
   */
  @Suppress("unused") @Inject lateinit var sharedEngineProvider: BidetSharedLiteRtEngineProvider

  override fun onCreate() {
    super.onCreate()

    // Load saved theme.
    ThemeSettings.themeOverride.value = dataStoreRepository.readTheme()

    // bidet-ai: FirebaseApp.initializeApp() removed (zero-telemetry hard rule).

    // v24 (2026-05-14): STT-first runtime ordering. The previous design prewarmed the
    // Gemma 4 E2B LiteRT-LM engine on app launch (gemma flavor only) so the Record button
    // could fire instantly. Mark's v23 test showed the symptom: a 3-min brain dump → only
    // the first chunk transcribed, the rest "[transcription failed]". Root cause: BOTH
    // the LiteRT-LM Gemma audio engine (~2.4 GB resident on E2B) and the per-chunk
    // ChunkCleaner (which acquires the same engine) competed for memory + serial access
    // to the shared engine during recording. Real-time STT lost the race; the user got
    // empty raw text after chunk 0.
    //
    // Fix: pivot the gemma flavor to use Moonshine STT for live transcription (the same
    // small sherpa-onnx ONNX engine the moonshine flavor uses; assets are already packaged
    // in both flavor APKs). Gemma is now ONLY the cleaning/analysis/judges LLM, loaded
    // LAZILY on first generate-tap after Stop. Mark's verbatim approval (2026-05-14):
    // "I would love if right at the end you can see that the moonshine had done his thing
    //  as quickly as possible. And then ... the Gemma, the one that's taken a few minutes
    //  to get its work done. That's OK. I'm good with that if that's the trade-off."
    //
    // Net effect:
    //  - App launch: no Gemma load. Fast cold start.
    //  - Record tap: Moonshine inits in <1 sec, transcription runs live as chunks arrive.
    //  - Stop tap: auto-flip to SessionDetail. RAW is already complete. User taps a clean
    //    tab → Gemma loads (10-30 s on Pixel 8 Pro / Tensor G3 CPU) → cleaning streams.
    //  - The TranscriptionEngine.create() factory now returns MoonshineEngine on both
    //    flavors; the gemma flavor's distinguishing feature is the cleaning LLM, not the
    //    STT.
    //
    // sharedEngineProvider is still injected (used by LiteRtBidetGemmaClient for the
    // cleaning path); the prewarm is just removed.
  }
}
