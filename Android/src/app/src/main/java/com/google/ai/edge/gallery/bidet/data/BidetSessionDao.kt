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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [BidetSession]. Surfaces the SessionsListScreen + SessionDetailScreen.
 *
 * Read paths return [Flow] so Compose can `collectAsStateWithLifecycle` the list and react to
 * inserts during a live recording (the live row appears in the list immediately, then updates
 * as the aggregator emits + the WAV concat runs).
 */
@Dao
interface BidetSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: BidetSession)

    @Update
    suspend fun update(session: BidetSession)

    @Query("SELECT * FROM bidet_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getById(sessionId: String): BidetSession?

    /** Snapshot variant for code paths that don't want to subscribe to a flow. */
    @Query("SELECT * FROM bidet_sessions WHERE sessionId = :sessionId LIMIT 1")
    fun observeById(sessionId: String): Flow<BidetSession?>

    @Query("SELECT * FROM bidet_sessions ORDER BY startedAtMs DESC")
    fun getAllOrderedByStartedDesc(): Flow<List<BidetSession>>

    @Query("DELETE FROM bidet_sessions WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String)
}
