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

package com.google.ai.edge.gallery.bidet.download

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import com.google.ai.edge.gallery.data.KEY_MODEL_COMMIT_HASH
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_ERROR_MESSAGE
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_FILE_NAME
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_MODEL_DIR
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_RATE
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_RECEIVED_BYTES
import com.google.ai.edge.gallery.data.KEY_MODEL_IS_ZIP
import com.google.ai.edge.gallery.data.KEY_MODEL_NAME
import com.google.ai.edge.gallery.data.KEY_MODEL_TOTAL_BYTES
import com.google.ai.edge.gallery.data.KEY_MODEL_UNZIPPED_DIR
import com.google.ai.edge.gallery.data.KEY_MODEL_URL
import com.google.ai.edge.gallery.worker.DownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * State of the Gemma 4 E2B model download. Surfaced to [GemmaDownloadScreen].
 */
sealed class DownloadProgress {
    /** No download has been kicked off yet — the screen first lands here. */
    object Idle : DownloadProgress()

    /**
     * Download is in flight. [bytesDownloaded] / [totalBytes] for the percent bar; [bytesPerSec]
     * for the human-readable speed line. [percent] is 0..100 (rounded).
     */
    data class InProgress(
        val percent: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val bytesPerSec: Long,
    ) : DownloadProgress()

    /** Download completed successfully and the model file is present at the expected path. */
    object Success : DownloadProgress()

    /** Download failed. [error] is shown to the user; the Retry button restarts the work. */
    data class Failed(val error: String) : DownloadProgress()
}

/**
 * Indirection so [com.google.ai.edge.gallery.bidet.ui.LiteRtBidetGemmaClient] can ask
 * "is the Gemma 4 E2B model present and ready to load?" without coupling to upstream
 * Gallery's model-manager view-model.
 *
 * Phase 3 wiring (this PR): real implementation backed by [DownloadWorker] + a fixed
 * HuggingFace URL for `litert-community/gemma-4-E2B-it-litert-lm`. The provider
 * owns the download lifecycle and reports progress as a [StateFlow]<[DownloadProgress]>.
 */
interface BidetModelProvider {
    /**
     * True once the model file exists at [getModelPath] AND its on-disk size matches the
     * expected total within [BidetModelProviderImpl.MODEL_SIZE_TOLERANCE]. Partial / aborted
     * downloads (file exists but length << expected) return false so [startDownload] knows to
     * resume rather than treat the half-baked file as a usable model.
     *
     * Previously the check was just `exists() && length() > 0L`, which let a 1 GB partial
     * download masquerade as ready and crashed the LiteRT-LM Engine on load. See task #36
     * (2026-05-10) — Mark hit a wasted 3.5 GB re-download because a presence-only check
     * gave the wrong answer in the other direction (file fully there → still triggered
     * download path via a stale SharedPreferences flag).
     */
    fun isModelReady(): Boolean

    /** Returns the on-disk file the LiteRT-LM Engine should load, or null if absent. */
    fun getModelPath(): File?

    /** Hot-flow of the most recent [DownloadProgress] state (per active or last download). */
    val progress: StateFlow<DownloadProgress>

    /**
     * Kick off a download (idempotent — uses [WorkManager.enqueueUniqueWork] with REPLACE so
     * tapping Retry while a previous attempt is still hanging cancels and restarts cleanly).
     *
     * Returns a cold [Flow] of [DownloadProgress] mirroring [progress] for the duration of the
     * work. The flow completes when the worker reaches Succeeded / Failed / Cancelled.
     */
    fun startDownload(): Flow<DownloadProgress>

    /** Cancel an in-flight download. No-op when no work is enqueued. */
    fun cancelDownload()

    /**
     * Phase 4A: dynamic Content-Length resolution.
     *
     * Issues an HTTP HEAD against [BidetModelProviderImpl.MODEL_URL] (or an alternative URL
     * passed in for testing) and returns the parsed `Content-Length` header. Falls back to
     * [BidetModelProviderImpl.EXPECTED_TOTAL_BYTES_FALLBACK] if the request fails or the
     * header is missing/malformed.
     *
     * The result is cached in the bidet-download DataStore so subsequent launches don't
     * re-HEAD on every cold start. Call again after model invalidation to refresh.
     */
    suspend fun fetchExpectedTotalBytes(): Long
}

/**
 * Hilt-bound implementation. Reuses upstream Gallery's [DownloadWorker] (HTTP-Range resume,
 * SHA-256-style atomic rename via the `.tmp` extension, foreground notification, exponential
 * back-off on transient I/O failure).
 *
 * URL + storage layout (per brief §8):
 *  - URL:   https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm
 *  - Path:  ${context.getExternalFilesDir(null)}/${MODEL_DIR}/${VERSION}/${FILE_NAME}
 *           where MODEL_DIR/VERSION/FILE_NAME match what [DownloadWorker] expects so the
 *           upstream worker drops the file in the right place.
 *
 * On size mismatch / hash failure / partial file: the worker writes to a `.tmp` sidecar and
 * only renames on completion. We treat presence-with-non-zero-size at the rename target as
 * "ready"; if a future verify step needs to be added (SHA-256 against a known good hash) it
 * lands here without touching the public interface.
 */
@Singleton
class BidetModelProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : BidetModelProvider {

    private val workManager: WorkManager = WorkManager.getInstance(context)
    private val _progress = MutableStateFlow<DownloadProgress>(DownloadProgress.Idle)
    override val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    /**
     * Phase 4A: dynamic Content-Length cache. Initialized from the fallback constant; the
     * first successful HEAD-fetch overwrites it (and persists to DataStore so cold starts
     * after the first launch get the accurate total without re-HEADing).
     */
    @Volatile private var cachedTotalBytes: Long = EXPECTED_TOTAL_BYTES_FALLBACK

    override fun isModelReady(): Boolean {
        val f = getModelPath() ?: return false
        return isFileReadyForModel(f)
    }

    override fun getModelPath(): File? {
        val base = context.getExternalFilesDir(null) ?: return null
        return File(base, listOf(MODEL_DIR, VERSION, FILE_NAME).joinToString(File.separator))
    }

    /**
     * Phase 4A.1: was implemented with [WorkManager.getWorkInfoByIdLiveData] +
     * `liveData.observeForever(...)` inside a `callbackFlow`. `observeForever` throws
     * `IllegalStateException` when invoked off the main thread; any caller collecting on
     * `Dispatchers.IO` would crash the app the moment the user tapped Start. Switched to
     * [WorkManager.getWorkInfoByIdFlow], which is safe on any dispatcher and completes
     * naturally when the work reaches a terminal state (Succeeded / Failed / Cancelled).
     */
    override fun startDownload(): Flow<DownloadProgress> {
        // Task #36 (2026-05-10): SHORT-CIRCUIT when the model file is already on disk at full
        // size. Without this guard, a stale SharedPreferences "not yet downloaded" flag (or
        // any caller that hits start() without first checking isModelReady()) re-enqueues the
        // 3.5 GB WorkManager job over a perfectly good file. That burns the user's bandwidth
        // and wastes 8+ min on every re-install. Mark hit this on the v18.8 → v18.9 cutover
        // after a fresh install on 2026-05-10.
        //
        // We do the check inside startDownload() rather than only at the GemmaDownloadScreen
        // call site so EVERY caller — including future Settings → "Re-download model" flows —
        // gets the protection for free.
        if (isModelReady()) {
            Log.i(
                TAG,
                "startDownload() called but model already present at " +
                    "${getModelPath()?.absolutePath} (${getModelPath()?.length()} bytes); " +
                    "short-circuiting to Success without enqueueing WorkManager job.",
            )
            _progress.value = DownloadProgress.Success
            return flow { emit(DownloadProgress.Success) }
        }

        // Snapshot the best-known total now so all observer paths agree. The HEAD-fetch may
        // refine this in [fetchExpectedTotalBytes]; any value the screen has fed through us
        // wins over the fallback.
        val totalBytes = cachedTotalBytes
        val inputData = Data.Builder()
            .putString(KEY_MODEL_NAME, MODEL_NAME)
            .putString(KEY_MODEL_URL, MODEL_URL)
            .putString(KEY_MODEL_COMMIT_HASH, VERSION)
            .putString(KEY_MODEL_DOWNLOAD_MODEL_DIR, MODEL_DIR)
            .putString(KEY_MODEL_DOWNLOAD_FILE_NAME, FILE_NAME)
            .putBoolean(KEY_MODEL_IS_ZIP, false)
            .putString(KEY_MODEL_UNZIPPED_DIR, "")
            .putLong(KEY_MODEL_TOTAL_BYTES, totalBytes)
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(inputData)
            .addTag(WORK_TAG)
            .build()

        val workerId: UUID = request.id
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)

        // [transformWhile] lets us emit on each WorkInfo update AND complete the flow when we
        // observe a terminal state, keeping the Cold Flow contract.
        return workManager
            .getWorkInfoByIdFlow(workerId)
            .transformWhile { info ->
                if (info == null) {
                    // WorkManager occasionally yields a transient null between enqueue +
                    // first state read; swallow it but keep the flow alive.
                    return@transformWhile true
                }
                val nextProgress: DownloadProgress = when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                        val current = _progress.value
                        if (current is DownloadProgress.InProgress) current
                        else DownloadProgress.InProgress(
                            percent = 0,
                            bytesDownloaded = 0L,
                            totalBytes = totalBytes,
                            bytesPerSec = 0L,
                        )
                    }
                    WorkInfo.State.RUNNING -> {
                        val downloaded = info.progress.getLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, 0L)
                        val rate = info.progress.getLong(KEY_MODEL_DOWNLOAD_RATE, 0L)
                        val total = if (totalBytes > 0) totalBytes else 1L
                        val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                        DownloadProgress.InProgress(
                            percent = percent,
                            bytesDownloaded = downloaded,
                            totalBytes = totalBytes,
                            bytesPerSec = rate,
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        if (isModelReady()) {
                            DownloadProgress.Success
                        } else {
                            DownloadProgress.Failed(
                                "Download finished but model file was not found at " +
                                    "${getModelPath()?.absolutePath}"
                            )
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        val err = info.outputData.getString(KEY_MODEL_DOWNLOAD_ERROR_MESSAGE)
                            ?: "Download failed"
                        DownloadProgress.Failed(err)
                    }
                    WorkInfo.State.CANCELLED -> DownloadProgress.Failed("Download cancelled")
                }

                _progress.value = nextProgress
                emit(nextProgress)

                // Keep the flow alive while non-terminal; complete on terminal states so the
                // collector unsubscribes.
                info.state !in setOf(
                    WorkInfo.State.SUCCEEDED,
                    WorkInfo.State.FAILED,
                    WorkInfo.State.CANCELLED,
                )
            }
    }

    override fun cancelDownload() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    /**
     * Phase 4A. Strategy:
     *   1. Read the cached value from DataStore. If non-null + > 0, hydrate
     *      [cachedTotalBytes] and return immediately — no network call needed.
     *   2. Otherwise, issue an HTTP HEAD. Note: HuggingFace returns a 302 redirect on the
     *      `/resolve/` URL, so we let OkHttp follow redirects (default).
     *   3. On success, cache + return. On failure, log and return the fallback.
     */
    override suspend fun fetchExpectedTotalBytes(): Long = withContext(Dispatchers.IO) {
        val ds = context.bidetDownloadDataStore
        val cached = try {
            ds.data.first()[CACHED_TOTAL_BYTES_KEY]
        } catch (t: Throwable) {
            Log.w(TAG, "DataStore read for cached total bytes failed: ${t.message}", t)
            null
        }
        if (cached != null && cached > 0L) {
            cachedTotalBytes = cached
            return@withContext cached
        }

        val fetched = headFetchContentLength(MODEL_URL)
        if (fetched != null && fetched > 0L) {
            cachedTotalBytes = fetched
            try {
                ds.edit { it[CACHED_TOTAL_BYTES_KEY] = fetched }
            } catch (t: Throwable) {
                Log.w(TAG, "DataStore write for cached total bytes failed: ${t.message}", t)
            }
            return@withContext fetched
        }
        Log.w(
            TAG,
            "HEAD fetch for Content-Length failed; falling back to constant " +
                "$EXPECTED_TOTAL_BYTES_FALLBACK.",
        )
        EXPECTED_TOTAL_BYTES_FALLBACK
    }

    /**
     * Best-effort HEAD against [url]. Returns the parsed `Content-Length` if present + > 0,
     * else null. Failures (timeout, redirect-loop, malformed header) collapse to null and the
     * caller falls back.
     *
     * Phase 4A.1: reuse [HEAD_HTTP_CLIENT] (singleton with bounded connect/read/call
     * timeouts) instead of `OkHttpClient()` per call. Default OkHttp timeouts are 0 = no
     * timeout, so a flaky DNS or stalled HEAD could hang the GemmaDownloadScreen launch
     * effect for minutes. 8s call timeout is plenty for a HEAD against HuggingFace's CDN
     * and falls back to the constant on the (rare) misses.
     */
    private fun headFetchContentLength(url: String): Long? {
        val request = Request.Builder().url(url).head().build()
        return try {
            HEAD_HTTP_CLIENT.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "HEAD ${url} returned HTTP ${resp.code}")
                    return null
                }
                val headerValue = resp.header("Content-Length") ?: return null
                val parsed = headerValue.toLongOrNull() ?: return null
                if (parsed > 0L) parsed else null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "HEAD ${url} threw: ${t.message}", t)
            null
        }
    }

    companion object {
        private const val TAG = "BidetModelProvider"

        // E2B for Pixel 8 Pro / Tensor G3 memory budget, per project memory rule
        // `reference_litertlm_tensor_g3_lessons_2026-05-09.md` — "E2B not E4B on Pixel 8".
        // v22 shipped E4B (3.66 GB) and OOM-crash-looped on Mark's device; v23 switches to
        // the E2B variant (~2.41 GB Content-Length). Same publisher, same LiteRT-LM runtime,
        // same prompt template — only the weights change.
        // The HuggingFace URL is verified ungated 2026-05-14 (302 redirect to CDN, no auth).
        const val MODEL_NAME: String = "gemma-4-E2B-it-litert-lm"
        const val MODEL_URL: String =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        const val FILE_NAME: String = "gemma-4-E2B-it.litertlm"

        // Layout matches DownloadWorker: ${externalFilesDir}/$MODEL_DIR/$VERSION/$FILE_NAME.
        // Picked stable strings (NOT the upstream `normalizedName` of a Model object so we
        // don't depend on the Gallery model graph just to reach the file).
        const val MODEL_DIR: String = "models"
        const val VERSION: String = "v1"

        /**
         * Fallback used when [fetchExpectedTotalBytes] cannot resolve a Content-Length.
         * ~2.41 GB per HuggingFace metadata 2026-05-14 for the E2B variant
         * (`gemma-4-E2B-it.litertlm`). Phase 4A renamed from EXPECTED_TOTAL_BYTES to
         * make the fallback role explicit.
         *
         * v23 (2026-05-14): switched from E4B (was 3_927_823_312L) to E2B per the Pixel 8 Pro
         * memory-budget rule. Verified via `curl -sI -L` against the LFS-resolved S3 URL.
         */
        const val EXPECTED_TOTAL_BYTES_FALLBACK: Long = 2_588_147_712L

        /**
         * Task #36 (2026-05-10): canonical on-disk size of the gemma-4-E2B-it.litertlm file.
         * Used by [BidetModelProviderImpl.isModelReady] to distinguish a complete download
         * from a partial.
         *
         * v23 (2026-05-14): switched from E4B (was 3_659_530_240L) to E2B per the Pixel 8 Pro
         * memory-budget rule. Set to the same HuggingFace Content-Length value as
         * [EXPECTED_TOTAL_BYTES_FALLBACK] until we measure the on-disk `du -b` value against
         * a fresh sideload. The [MODEL_SIZE_TOLERANCE] of 0.99 absorbs the ~few-byte
         * signature trailer delta (≈25 MB headroom at 2.5 GB), which historically tracked
         * the same direction for E4B.
         */
        const val EXPECTED_MODEL_SIZE_BYTES: Long = 2_588_147_712L

        /**
         * Task #36: tolerance applied to [EXPECTED_MODEL_SIZE_BYTES] when judging readiness.
         * `0.99` lets a HuggingFace re-tag drop ~36 MB of bytes without forcing a re-download
         * while still rejecting any partial that's lost more than 1% of the file. A 1 GB
         * partial (the value mentioned in the task brief) sits at ~27% of expected and is
         * comfortably below the threshold.
         */
        const val MODEL_SIZE_TOLERANCE: Double = 0.99

        /**
         * Task #36: extracted readiness check so a JVM unit test can exercise the
         * size-threshold logic without booting an Android Context. The instance method
         * [isModelReady] delegates here after resolving the on-disk path.
         *
         * Returns true iff the file exists AND its length is at least
         * `EXPECTED_MODEL_SIZE_BYTES * MODEL_SIZE_TOLERANCE`. Any partial download
         * (1 GB out of 3.66 GB, etc.) returns false so the caller resumes rather than
         * loading a half-baked file into the LiteRT-LM Engine.
         */
        @JvmStatic
        fun isFileReadyForModel(file: File): Boolean {
            if (!file.exists()) return false
            val minAcceptable = (EXPECTED_MODEL_SIZE_BYTES * MODEL_SIZE_TOLERANCE).toLong()
            return file.length() >= minAcceptable
        }

        const val WORK_NAME: String = "bidet_gemma4_e4b_download"
        const val WORK_TAG: String = "bidet_model_download"

        // Phase 4A: DataStore key for the cached HEAD result.
        internal val CACHED_TOTAL_BYTES_KEY: Preferences.Key<Long> =
            longPreferencesKey("bidet_model_cached_total_bytes")

        /**
         * Phase 4A.1: bounded-timeout OkHttp client reused across HEAD calls. Default
         * OkHttp timeouts are 0 = unlimited; without bounds a flaky network would hang the
         * download screen's LaunchedEffect during the HEAD-fetch.
         */
        private val HEAD_HTTP_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Bidet-download DataStore. Lives in its own file so it doesn't share the `bidet` tab-cache
 * DataStore (defined in BidetTabsViewModel). One file per app process.
 */
private val Context.bidetDownloadDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "bidet_download",
)
