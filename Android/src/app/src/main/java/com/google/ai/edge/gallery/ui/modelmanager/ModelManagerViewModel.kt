/*
 * Copyright 2025 Google LLC
 * Modifications Copyright 2026 bidet-ai contributors. Replaced the upstream Gallery model-manager
 * view-model with a minimal Hilt-injected stub. Bidet's UI bypasses upstream Gallery entirely
 * (see GalleryApp.kt → BidetMainScreen), but MainActivity still constructs this view-model via
 * `by viewModels()` for legacy reasons; this stub keeps that hook compiling without dragging in
 * the deleted `customtasks/`, `ui/llmchat/`, etc. dependencies.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.ui.modelmanager

import androidx.lifecycle.ViewModel
import com.google.ai.edge.gallery.data.DataStoreRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Minimal stub of the upstream Gallery [ModelManagerViewModel].
 *
 * The original (~1500 LOC) walked a Hilt `Set<CustomTask>` and a JSON model allowlist to populate
 * the upstream Gallery home screen. Bidet does none of that — its UI is wired directly to
 * `BidetMainScreen` via [com.google.ai.edge.gallery.GalleryApp]. This stub exists solely so the
 * `MainActivity by viewModels()` site continues to compile.
 *
 * The [DataStoreRepository] dependency is retained because [com.google.ai.edge.gallery.GalleryApplication]
 * already injects it and several Bidet-internal call sites read theme/settings out of it.
 */
@HiltViewModel
open class ModelManagerViewModel
@Inject
constructor(val dataStoreRepository: DataStoreRepository) : ViewModel() {
  /** No-op; retained for binary compatibility with the [MainActivity] call site. */
  fun loadModelAllowlist() {}
}
