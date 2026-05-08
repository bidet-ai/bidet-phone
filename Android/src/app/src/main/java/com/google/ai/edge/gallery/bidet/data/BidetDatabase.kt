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

package com.google.ai.edge.gallery.bidet.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for the bidet brain-dump pipeline.
 *
 * v0.1 / Phase 4A schema (version 1): a single [BidetSession] entity. Schema export is
 * intentionally disabled — the entity is small and we'll bump the version with a proper
 * Migration in Phase 5+ when the data layout stabilizes (notes, tags, search index).
 *
 * The instance is provided as a Hilt @Singleton via
 * [com.google.ai.edge.gallery.bidet.data.BidetDatabaseModule].
 */
@Database(
    entities = [BidetSession::class],
    version = 1,
    exportSchema = false,
)
abstract class BidetDatabase : RoomDatabase() {
    abstract fun bidetSessionDao(): BidetSessionDao

    companion object {
        const val DATABASE_NAME = "bidet.db"
    }
}
