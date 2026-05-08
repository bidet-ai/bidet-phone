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

import com.google.ai.edge.gallery.bidet.ui.BidetGemmaClient
import com.google.ai.edge.gallery.bidet.ui.BidetModelProvider
import com.google.ai.edge.gallery.bidet.ui.LiteRtBidetGemmaClient
import com.google.ai.edge.gallery.data.Model
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt graph for the bidet brain-dump pipeline.
 *
 * Bindings:
 *  - [BidetGemmaClient] → [LiteRtBidetGemmaClient] (Singleton). Used by [BidetTabsViewModel] to
 *    drive on-demand CLEAN / ANALYSIS / FORAI generation.
 *  - [BidetModelProvider] → a Phase 2 stub that returns null (no model wired through the bidet
 *    flow yet). Phase 3 will replace this with a real provider that surfaces the
 *    auto-downloaded Gemma 4 E4B instance to the client.
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

    companion object {
        /**
         * Phase 2 stub provider. Returns null until the bidet flow owns model selection
         * (Phase 3). When null is returned, [LiteRtBidetGemmaClient.runInference] throws
         * [com.google.ai.edge.gallery.bidet.ui.BidetModelNotReadyException], which the tab UI
         * surfaces as a Failed state.
         */
        @Provides
        @Singleton
        fun provideBidetModelProvider(): BidetModelProvider = object : BidetModelProvider {
            override fun getReadyModel(): Model? = null
        }
    }
}
