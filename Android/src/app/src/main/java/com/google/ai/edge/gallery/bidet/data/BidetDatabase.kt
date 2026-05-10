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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for the bidet brain-dump pipeline.
 *
 * Schema history:
 *  - v1 (Phase 4A): single [BidetSession] entity, columns described in the entity Kdoc.
 *  - v2 (2026-05-10, Bug-3 fix): adds `mergedChunkCount INTEGER NOT NULL DEFAULT 0` so the
 *    History UI can render "Transcribing N of M chunks…" while the worker is still draining
 *    the queue after the user tapped Stop. See [MIGRATION_1_2].
 *
 * Schema export is intentionally disabled — exhaustive Migration testing is in
 * `BidetMigrationTest`; we don't need to ship JSON schemas yet.
 *
 * The instance is provided as a Hilt @Singleton via
 * [com.google.ai.edge.gallery.bidet.data.BidetDatabaseModule].
 */
@Database(
    entities = [BidetSession::class],
    version = 2,
    exportSchema = false,
)
abstract class BidetDatabase : RoomDatabase() {
    abstract fun bidetSessionDao(): BidetSessionDao

    companion object {
        const val DATABASE_NAME = "bidet.db"

        /**
         * v1 → v2: ALTER TABLE to add the new column with a default of 0 so existing rows
         * (always pre-Bug-3, which means already finalized — endedAtMs is set, History
         * doesn't read mergedChunkCount for them) backfill cleanly.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE bidet_sessions ADD COLUMN mergedChunkCount INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}
