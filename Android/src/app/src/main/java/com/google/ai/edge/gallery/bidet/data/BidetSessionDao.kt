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

    /*
     * Phase 4A.1: column-targeted updates replace the read-modify-write pattern of
     * `getById() → entity.copy(field = ...) → update(entity)`. With the old shape, two
     * concurrent generators (e.g. user taps CLEAN and ANALYSIS at the same time) could
     * both read the row before either wrote, then second-to-finish would copy from the
     * stale snapshot and clobber first-to-finish's column. Each targeted UPDATE is a
     * single atomic SQL statement; concurrent calls to different columns can no longer
     * interfere.
     */

    @Query("UPDATE bidet_sessions SET cleanCached = :text WHERE sessionId = :sessionId")
    suspend fun updateCleanCached(sessionId: String, text: String)

    @Query("UPDATE bidet_sessions SET analysisCached = :text WHERE sessionId = :sessionId")
    suspend fun updateAnalysisCached(sessionId: String, text: String)

    @Query("UPDATE bidet_sessions SET foraiCached = :text WHERE sessionId = :sessionId")
    suspend fun updateForaiCached(sessionId: String, text: String)

    /**
     * v20 (2026-05-11): atomic update for the Clean-for-judges contest-pitch cache. Mirrors
     * the other axis-specific writers — single SQL UPDATE so two concurrent generators on
     * different tabs can't clobber each other's columns via a read-modify-write race.
     */
    @Query("UPDATE bidet_sessions SET judgesCached = :text WHERE sessionId = :sessionId")
    suspend fun updateJudgesCached(sessionId: String, text: String)

    @Query("UPDATE bidet_sessions SET rawText = :text WHERE sessionId = :sessionId")
    suspend fun updateRawText(sessionId: String, text: String)

    /**
     * Bug-1 fix (2026-05-10): atomic per-merge update called from
     * [com.google.ai.edge.gallery.bidet.transcript.TranscriptAggregator]'s `onMutation`
     * callback inside the merge mutex. One UPDATE writes both the rawText snapshot and the
     * merged-chunk count so a process death between the two writes can't leave the row in
     * a state where the count says "all chunks merged" but the text is from before the
     * latest merge.
     */
    @Query(
        "UPDATE bidet_sessions SET rawText = :text, mergedChunkCount = :mergedChunkCount " +
            "WHERE sessionId = :sessionId"
    )
    suspend fun updateRawTextAndMergedChunkCount(
        sessionId: String,
        text: String,
        mergedChunkCount: Int,
    )

    /**
     * Bug-3 fix (2026-05-10): bump the produced-chunk count as the audio capture engine
     * emits chunks. This is the denominator in the History "Transcribing N of M…" indicator.
     * Called from RecordingService whenever AudioCaptureEngine's nextChunkIdx advances.
     */
    @Query("UPDATE bidet_sessions SET chunkCount = :chunkCount WHERE sessionId = :sessionId")
    suspend fun updateChunkCount(sessionId: String, chunkCount: Int)

    /**
     * Phase 4A.1: terminal write at end-of-recording. One atomic UPDATE replaces the
     * read/copy/update dance in [com.google.ai.edge.gallery.bidet.service.RecordingService.finalizeSessionRow]
     * so it doesn't race the in-flight rawText updates from the persist job.
     */
    @Query(
        "UPDATE bidet_sessions SET endedAtMs = :endedAtMs, durationSeconds = :durationSeconds, " +
            "rawText = :rawText, chunkCount = :chunkCount, audioWavPath = :audioWavPath, " +
            "mergedChunkCount = :mergedChunkCount, " +
            "notes = COALESCE(:notes, notes) WHERE sessionId = :sessionId"
    )
    suspend fun finalizeSession(
        sessionId: String,
        endedAtMs: Long,
        durationSeconds: Int,
        rawText: String,
        chunkCount: Int,
        audioWavPath: String?,
        mergedChunkCount: Int,
        notes: String?,
    )

    /**
     * Phase 4A.1 — orphan recovery (see fix #7). Backfills `endedAtMs` (best-effort: derived
     * from `startedAtMs + chunkCount * 30 sec`) and stamps `notes='recovered'` on rows that
     * were left with `endedAtMs IS NULL` because the service died before finalize ran.
     */
    @Query(
        "UPDATE bidet_sessions " +
            "SET endedAtMs = startedAtMs + (chunkCount * 30000), " +
            "    notes = COALESCE(notes, 'recovered') " +
            "WHERE endedAtMs IS NULL"
    )
    suspend fun recoverOrphans()
}
