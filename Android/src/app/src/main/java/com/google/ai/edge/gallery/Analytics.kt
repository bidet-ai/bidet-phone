/*
 * Copyright 2025 Google LLC
 * Modifications Copyright 2026 bidet-ai contributors. Changed: gut Firebase Analytics integration to satisfy bidet's zero-telemetry hard rule. The `firebaseAnalytics` symbol still exists but always returns null, so every `firebaseAnalytics?.logEvent(...)` call site upstream becomes a compile-clean no-op without a 50-file cascade. The `GalleryEvent` enum is preserved verbatim so its `.id` references stay valid.
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

import android.os.Bundle

/**
 * bidet-ai: stand-in for `FirebaseAnalytics` with the same shape every call site uses.
 * Defines `logEvent(String, Bundle?)` so the upstream Gallery code's
 * `firebaseAnalytics?.logEvent(...)` invocations type-check without pulling in Firebase.
 *
 * This is intentionally a class with a no-op method (rather than `Nothing?`) so we don't have
 * to litter every call site with `@Suppress("UNREACHABLE_CODE")` casts.
 */
class NoOpAnalytics internal constructor() {
    fun logEvent(@Suppress("UNUSED_PARAMETER") name: String, @Suppress("UNUSED_PARAMETER") params: Bundle?) {
        // Intentional no-op. bidet ships zero telemetry.
    }
}

/**
 * Always null. Every upstream call site uses `firebaseAnalytics?.logEvent(...)` so a null
 * receiver short-circuits cleanly. We keep the symbol (rather than deleting it and chasing
 * 50+ call sites) to minimize the diff against upstream Gallery.
 */
val firebaseAnalytics: NoOpAnalytics? = null

enum class GalleryEvent(val id: String) {
  CAPABILITY_SELECT(id = "capability_select"),
  MODEL_DOWNLOAD(id = "model_download"),
  GENERATE_ACTION(id = "generate_action"),
  BUTTON_CLICKED(id = "button_clicked"),
  SKILL_MANAGEMENT(id = "skill_management"),
  SKILL_EXECUTION(id = "skill_execution"),
}
