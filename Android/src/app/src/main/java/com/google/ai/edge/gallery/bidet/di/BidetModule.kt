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

package com.google.ai.edge.gallery.bidet.di

import com.google.ai.edge.gallery.bidet.download.BidetModelProvider
import com.google.ai.edge.gallery.bidet.download.BidetModelProviderImpl
import com.google.ai.edge.gallery.bidet.ui.BidetGemmaClient
import com.google.ai.edge.gallery.bidet.ui.LiteRtBidetGemmaClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt graph for the bidet brain-dump pipeline.
 *
 * Bindings:
 *  - [BidetGemmaClient] → [LiteRtBidetGemmaClient] (Singleton). Used by
 *    [com.google.ai.edge.gallery.bidet.ui.BidetTabsViewModel] to drive on-demand
 *    CLEAN / ANALYSIS / FORAI generation.
 *  - [BidetModelProvider] → [BidetModelProviderImpl] (Singleton). Phase 3 wires the real
 *    on-device download flow against the HuggingFace `litert-community/gemma-4-E4B-it-litert-lm`
 *    URL via upstream Gallery's [com.google.ai.edge.gallery.worker.DownloadWorker].
 *
 * Why a separate module: the Gallery [com.google.ai.edge.gallery.di.AppModule] is upstream
 * Apache 2.0 territory we shouldn't churn. Bidet's bindings live in this leaf module so the
 * upstream module remains diffable against future Gallery merges.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BidetModule {

    @Binds
    @Singleton
    abstract fun bindBidetGemmaClient(impl: LiteRtBidetGemmaClient): BidetGemmaClient

    @Binds
    @Singleton
    abstract fun bindBidetModelProvider(impl: BidetModelProviderImpl): BidetModelProvider
}
