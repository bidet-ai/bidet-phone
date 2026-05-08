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
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow

/**
 * State of the Gemma 4 E4B model download. Surfaced to [GemmaDownloadScreen].
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
 * "is the Gemma 4 E4B model present and ready to load?" without coupling to upstream
 * Gallery's model-manager view-model.
 *
 * Phase 3 wiring (this PR): real implementation backed by [DownloadWorker] + a fixed
 * HuggingFace URL for `litert-community/gemma-4-E4B-it-litert-lm`. The provider
 * owns the download lifecycle and reports progress as a [StateFlow]<[DownloadProgress]>.
 */
interface BidetModelProvider {
    /** True once the model file exists at [getModelPath] (size > 0). */
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
}

/**
 * Hilt-bound implementation. Reuses upstream Gallery's [DownloadWorker] (HTTP-Range resume,
 * SHA-256-style atomic rename via the `.tmp` extension, foreground notification, exponential
 * back-off on transient I/O failure).
 *
 * URL + storage layout (per brief §8):
 *  - URL:   https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm
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

    override fun isModelReady(): Boolean {
        val f = getModelPath() ?: return false
        return f.exists() && f.length() > 0L
    }

    override fun getModelPath(): File? {
        val base = context.getExternalFilesDir(null) ?: return null
        return File(base, listOf(MODEL_DIR, VERSION, FILE_NAME).joinToString(File.separator))
    }

    override fun startDownload(): Flow<DownloadProgress> = callbackFlow {
        val inputData = Data.Builder()
            .putString(KEY_MODEL_NAME, MODEL_NAME)
            .putString(KEY_MODEL_URL, MODEL_URL)
            .putString(KEY_MODEL_COMMIT_HASH, VERSION)
            .putString(KEY_MODEL_DOWNLOAD_MODEL_DIR, MODEL_DIR)
            .putString(KEY_MODEL_DOWNLOAD_FILE_NAME, FILE_NAME)
            .putBoolean(KEY_MODEL_IS_ZIP, false)
            .putString(KEY_MODEL_UNZIPPED_DIR, "")
            .putLong(KEY_MODEL_TOTAL_BYTES, EXPECTED_TOTAL_BYTES)
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(inputData)
            .addTag(WORK_TAG)
            .build()

        val workerId: UUID = request.id
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)

        val liveData = workManager.getWorkInfoByIdLiveData(workerId)
        val observer = androidx.lifecycle.Observer<WorkInfo?> { info ->
            if (info == null) return@Observer
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                    val current = _progress.value
                    if (current !is DownloadProgress.InProgress) {
                        _progress.value = DownloadProgress.InProgress(
                            percent = 0,
                            bytesDownloaded = 0L,
                            totalBytes = EXPECTED_TOTAL_BYTES,
                            bytesPerSec = 0L,
                        )
                        trySend(_progress.value)
                    }
                }
                WorkInfo.State.RUNNING -> {
                    val downloaded = info.progress.getLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, 0L)
                    val rate = info.progress.getLong(KEY_MODEL_DOWNLOAD_RATE, 0L)
                    val total = if (EXPECTED_TOTAL_BYTES > 0) EXPECTED_TOTAL_BYTES else 1L
                    val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                    _progress.value = DownloadProgress.InProgress(
                        percent = percent,
                        bytesDownloaded = downloaded,
                        totalBytes = EXPECTED_TOTAL_BYTES,
                        bytesPerSec = rate,
                    )
                    trySend(_progress.value)
                }
                WorkInfo.State.SUCCEEDED -> {
                    if (isModelReady()) {
                        _progress.value = DownloadProgress.Success
                    } else {
                        // Worker reported success but the file isn't where we expect — surface
                        // as failure so the user can retry.
                        _progress.value = DownloadProgress.Failed(
                            "Download finished but model file was not found at " +
                                "${getModelPath()?.absolutePath}"
                        )
                    }
                    trySend(_progress.value)
                    close()
                }
                WorkInfo.State.FAILED -> {
                    val err = info.outputData.getString(KEY_MODEL_DOWNLOAD_ERROR_MESSAGE)
                        ?: "Download failed"
                    _progress.value = DownloadProgress.Failed(err)
                    trySend(_progress.value)
                    close()
                }
                WorkInfo.State.CANCELLED -> {
                    _progress.value = DownloadProgress.Failed("Download cancelled")
                    trySend(_progress.value)
                    close()
                }
            }
        }
        liveData.observeForever(observer)
        awaitClose {
            try {
                liveData.removeObserver(observer)
            } catch (t: Throwable) {
                Log.w(TAG, "removeObserver threw on awaitClose", t)
            }
        }
    }

    override fun cancelDownload() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    companion object {
        private const val TAG = "BidetModelProvider"

        // The HuggingFace URL is verified ungated 2026-05-07 (302 redirect to CDN, no auth).
        const val MODEL_NAME: String = "gemma-4-E4B-it-litert-lm"
        const val MODEL_URL: String =
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm"
        const val FILE_NAME: String = "gemma-4-E4B-it.litertlm"

        // Layout matches DownloadWorker: ${externalFilesDir}/$MODEL_DIR/$VERSION/$FILE_NAME.
        // Picked stable strings (NOT the upstream `normalizedName` of a Model object so we
        // don't depend on the Gallery model graph just to reach the file).
        const val MODEL_DIR: String = "models"
        const val VERSION: String = "v1"

        // 3.66 GB per HuggingFace metadata 2026-05-07. Used for percent calculation;
        // DownloadWorker also pulls Content-Length from the server.
        const val EXPECTED_TOTAL_BYTES: Long = 3_927_823_312L

        const val WORK_NAME: String = "bidet_gemma4_e4b_download"
        const val WORK_TAG: String = "bidet_model_download"
    }
}
