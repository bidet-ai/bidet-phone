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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class GalleryApplication : Application() {

  @Inject lateinit var dataStoreRepository: DataStoreRepository

  /**
   * 2026-05-09: shared engine provider for the gemma flavor. We only USE this on the gemma
   * build (the whisper flavor uses whisper.cpp directly), but Hilt still injects it on both
   * flavors — that's harmless since the singleton is just an idle handle until something
   * acquires the engine.
   */
  @Inject lateinit var sharedEngineProvider: BidetSharedLiteRtEngineProvider

  /**
   * Process-wide scope for app-launch warm-up tasks that must outlive any single Activity.
   * SupervisorJob so a crash in the prewarm coroutine doesn't poison future launches; IO
   * dispatcher because the LiteRT-LM Engine ctor does big disk + native work.
   */
  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onCreate() {
    super.onCreate()

    // Load saved theme.
    ThemeSettings.themeOverride.value = dataStoreRepository.readTheme()

    // bidet-ai: FirebaseApp.initializeApp() removed (zero-telemetry hard rule).

    // 2026-05-09: pre-warm the LiteRT-LM Gemma 4 E4B engine on app launch (gemma flavor
    // only). The 3.6 GB model load takes 10-30s of native work on the Pixel 8 Pro; doing
    // it lazily on the first Record tap blew past Android's 5-second
    // startForegroundService→startForeground deadline (logcat showed
    // startForegroundDelayMs:68453 from AOSP ActiveServices.java). Now: kick off the load
    // here on a background coroutine, gate the welcome-screen Record button on
    // sharedEngineProvider.state, and by the time the user finds the Record button the
    // engine is warm. ensureReady() is idempotent + state-flow driven so the UI just
    // observes; no runBlocking here.
    if (BidetSharedLiteRtEngineProvider.shouldPrewarmOnAppLaunch()) {
      applicationScope.launch {
        sharedEngineProvider.ensureReady()
      }
    }
  }
}
