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

import android.content.Context
import androidx.room.Room
import com.google.ai.edge.gallery.bidet.data.BidetDatabase
import com.google.ai.edge.gallery.bidet.data.BidetSessionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt provider for the Phase 4A session-persistence layer.
 *
 *  - [provideBidetDatabase] — single Room instance built against the application context.
 *    Lives for the lifetime of the process; Room handles its own connection pooling.
 *  - [provideBidetSessionDao] — pulled out of the database for direct injection into
 *    view-models / repositories.
 *
 * Why a separate module from [BidetModule]: BidetModule is `abstract` (uses @Binds for
 * interface-impl wiring), whereas Room construction needs concrete @Provides methods.
 * Splitting keeps Hilt happy and the file small.
 */
@Module
@InstallIn(SingletonComponent::class)
object BidetDatabaseModule {

    @Provides
    @Singleton
    fun provideBidetDatabase(@ApplicationContext context: Context): BidetDatabase =
        Room.databaseBuilder(
            context,
            BidetDatabase::class.java,
            BidetDatabase.DATABASE_NAME,
        )
            // 2026-05-10 (Bug-3): explicit migration v1 → v2 adds the mergedChunkCount
            // column. fallbackToDestructiveMigration is still NOT added — losing a user's
            // brain-dump history because of a schema bump is unacceptable.
            // v20 (2026-05-11): explicit migration v2 → v3 adds the judgesCached column
            // for the Clean-for-judges contest-pitch tab.
            .addMigrations(BidetDatabase.MIGRATION_1_2, BidetDatabase.MIGRATION_2_3)
            .build()

    @Provides
    @Singleton
    fun provideBidetSessionDao(db: BidetDatabase): BidetSessionDao = db.bidetSessionDao()
}
