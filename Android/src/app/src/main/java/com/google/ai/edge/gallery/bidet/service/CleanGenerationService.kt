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

package com.google.ai.edge.gallery.bidet.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.bidet.cleaning.RawChunker
import com.google.ai.edge.gallery.bidet.ui.BidetGemmaClient
import com.google.ai.edge.gallery.bidet.ui.BidetModelNotReadyException
import com.google.ai.edge.gallery.bidet.ui.SupportAxis
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that owns a single Clean-tab generation (Receptive or Expressive). The
 * service exists so on-device Gemma inference (~10 tk/s on a Pixel 8 Pro for our token cap →
 * 3-4 minutes wall-clock) survives screen-sleep without Doze killing the model mid-decode.
 *
 * Why a service rather than just a long-lived viewModelScope coroutine: a 30-min brain dump
 * cleaned at the [com.google.ai.edge.gallery.bidet.ui.BidetTabsViewModel.CLEAN_TAB_OUTPUT_TOKEN_CAP]
 * cap will run for 3-4 minutes. On Android 14+, a non-foreground process loses the GPU as
 * soon as the screen blanks, and the LiteRT-LM Engine dies with it. A user-visible foreground
 * notification keeps the process alive for the duration. Type = `dataSync` because the
 * generation is best modelled as an off-device-equivalent compute job; the manifest already
 * declares FOREGROUND_SERVICE_DATA_SYNC for the WorkManager system service that ships in
 * androidx.work.
 *
 * Bound shape: the [BidetTabsViewModel] binds for live progress, but the service ALSO runs
 * unbound while the user navigates away. The result lands in [resultFlow] regardless of
 * whether anyone is bound at completion time, and the bound client (when it returns) reads
 * the latest snapshot from the StateFlow.
 *
 * Concurrency model: only one in-flight generation per process. A second start request while
 * a generation is in flight silently no-ops (logged); the current generation's StateFlow
 * keeps streaming. Two simultaneous Clean tabs would contend on the single LiteRT-LM Engine
 * anyway — sequencing them at the service boundary keeps the contract clear.
 */
@AndroidEntryPoint
class CleanGenerationService : Service() {

    @Inject lateinit var gemma: BidetGemmaClient

    inner class LocalBinder : Binder() {
        fun service(): CleanGenerationService = this@CleanGenerationService
    }

    private val binder = LocalBinder()
    // SupervisorJob so a failure in one launch doesn't kill the service scope itself.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var generationJob: Job? = null

    /**
     * Public progress + result surface. Whoever is bound at any moment can collect this; the
     * StateFlow's replay-1 semantics mean a late binder still sees the most recent state.
     */
    sealed class GenerationState {
        object Idle : GenerationState()
        data class Streaming(
            val sessionId: String,
            val axis: SupportAxis,
            val partialText: String,
            val tokenCount: Int,
            val tokenCap: Int,
        ) : GenerationState()
        data class Done(
            val sessionId: String,
            val axis: SupportAxis,
            val text: String,
            val finishedAtMs: Long,
        ) : GenerationState()
        data class Failed(
            val sessionId: String,
            val axis: SupportAxis,
            val message: String,
            val modelMissing: Boolean,
        ) : GenerationState()
    }

    private val _stateFlow = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val stateFlow: StateFlow<GenerationState> = _stateFlow.asStateFlow()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_GENERATION -> handleStart(intent)
            ACTION_CANCEL_GENERATION -> handleCancel()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun handleStart(intent: Intent) {
        if (generationJob?.isActive == true) {
            Log.w(TAG, "handleStart called while a generation is already in flight; ignoring.")
            return
        }
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: run {
            Log.e(TAG, "handleStart: missing EXTRA_SESSION_ID")
            stopSelf()
            return
        }
        val axisName = intent.getStringExtra(EXTRA_AXIS) ?: run {
            Log.e(TAG, "handleStart: missing EXTRA_AXIS")
            stopSelf()
            return
        }
        val axis = SupportAxis.valueOf(axisName)
        val systemPrompt = intent.getStringExtra(EXTRA_SYSTEM_PROMPT).orEmpty()
        val userPrompt = intent.getStringExtra(EXTRA_USER_PROMPT).orEmpty()
        val tokenCap = intent.getIntExtra(EXTRA_TOKEN_CAP, DEFAULT_TOKEN_CAP)
        val temperature = intent.getFloatExtra(EXTRA_TEMPERATURE, DEFAULT_TEMPERATURE)

        startForegroundCompat(axis)

        _stateFlow.value = GenerationState.Streaming(
            sessionId = sessionId,
            axis = axis,
            partialText = "",
            tokenCount = 0,
            tokenCap = tokenCap,
        )

        // The foreground service notification keeps this scope alive across screen-sleep —
        // Android won't let Doze kill a foreground-typed service. So we don't need
        // NonCancellable; we let the user's Cancel action propagate cancellation through
        // suspendCancellableCoroutine → Conversation.cancelProcess.
        generationJob = scope.launch(Dispatchers.Default) {
            try {
                // Gemma 4 E2B has a hard 2048-token context cap. With tokenCap reserved for
                // output and the system prompt budget, the per-call input ceiling is ~700
                // tokens (≈2400 chars of English). Split longer RAW dumps into windows,
                // clean each on-device, and stitch. Short dumps still take the single-call
                // path so behaviour for sub-7-minute sessions is byte-identical.
                val windows = RawChunker.chunk(userPrompt)
                val finalText = if (windows.size <= 1) {
                    gemma.runInferenceStreaming(
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        maxOutputTokens = tokenCap,
                        temperature = temperature,
                        onChunk = { cumulative, chunkIndex ->
                            _stateFlow.value = GenerationState.Streaming(
                                sessionId = sessionId,
                                axis = axis,
                                partialText = cumulative,
                                tokenCount = chunkIndex,
                                tokenCap = tokenCap,
                            )
                        },
                    )
                } else {
                    // Long-dump chunking: drop per-window cap so wall-clock stays bearable
                    // on Tensor G3 CPU (1024 → 512 tokens per window cuts decode time ~half).
                    val perChunkCap = CHUNKED_PER_WINDOW_OUTPUT_TOKEN_CAP
                    val totalCap = windows.size * perChunkCap
                    val parts = mutableListOf<String>()
                    var streamCounter = 0
                    windows.forEachIndexed { index, window ->
                        val partSystemPrompt = buildString {
                            append(systemPrompt)
                            append("\n\n(You are cleaning part ")
                            append(index + 1)
                            append(" of ")
                            append(windows.size)
                            append(" of a long brain dump. Stay faithful to THIS segment only — ")
                            append("do not re-introduce content from earlier parts and do not preface ")
                            append("your output with meta-commentary about parts.)")
                        }
                        val previouslyComposed = if (parts.isEmpty()) "" else parts.joinToString("\n\n") + "\n\n"
                        val header = "Cleaning part ${index + 1} of ${windows.size}…\n\n"
                        val partText = gemma.runInferenceStreaming(
                            systemPrompt = partSystemPrompt,
                            userPrompt = window,
                            maxOutputTokens = perChunkCap,
                            temperature = temperature,
                            onChunk = { cumulative, _ ->
                                streamCounter += 1
                                _stateFlow.value = GenerationState.Streaming(
                                    sessionId = sessionId,
                                    axis = axis,
                                    partialText = header + previouslyComposed + cumulative,
                                    tokenCount = streamCounter,
                                    tokenCap = totalCap,
                                )
                            },
                        )
                        parts.add(partText.trim())
                    }
                    parts.joinToString("\n\n")
                }
                _stateFlow.value = GenerationState.Done(
                    sessionId = sessionId,
                    axis = axis,
                    text = finalText,
                    finishedAtMs = System.currentTimeMillis(),
                )
            } catch (ce: CancellationException) {
                _stateFlow.value = GenerationState.Failed(
                    sessionId = sessionId,
                    axis = axis,
                    message = "Cancelled",
                    modelMissing = false,
                )
                throw ce
            } catch (t: Throwable) {
                val modelMissing = t is BidetModelNotReadyException
                _stateFlow.value = GenerationState.Failed(
                    sessionId = sessionId,
                    axis = axis,
                    message = t.message ?: "Generation failed",
                    modelMissing = modelMissing,
                )
            } finally {
                // After completion (success / failure / cancel), clear foreground status.
                // The terminal state stays in _stateFlow so a re-binding ViewModel reads it.
                try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) { /* ignore */ }
                stopSelf()
            }
        }
    }

    /**
     * User-driven cancel from the notification action. Cancellation propagates through
     * LiteRT-LM via the suspendCancellableCoroutine.invokeOnCancellation wiring in
     * [com.google.ai.edge.gallery.bidet.ui.LiteRtBidetGemmaClient] (which calls
     * Conversation.cancelProcess on the live conversation). The generation launch's finally
     * block updates state + tears down foreground.
     */
    private fun handleCancel() {
        generationJob?.cancel()
        generationJob = null
    }

    private fun startForegroundCompat(axis: SupportAxis) {
        val cancelIntent = Intent(this, CleanGenerationService::class.java)
            .setAction(ACTION_CANCEL_GENERATION)
        val cancelPi = PendingIntent.getService(
            this, 0, cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = getString(R.string.bidet_clean_gen_notification_title)
        val text = when (axis) {
            SupportAxis.RECEPTIVE -> getString(R.string.bidet_clean_gen_notification_text_receptive)
            SupportAxis.EXPRESSIVE -> getString(R.string.bidet_clean_gen_notification_text_expressive)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(
                NotificationCompat.Action.Builder(
                    /* icon */ 0,
                    getString(R.string.bidet_clean_gen_cancel_action),
                    cancelPi,
                ).build()
            )
            .build()

        // FOREGROUND_SERVICE_TYPE_DATA_SYNC: this is on-device compute (the Gemma decode
        // loop) running with no microphone or media output. dataSync is the closest match
        // in Android 14's typed-foreground-service taxonomy and is already declared in the
        // manifest for the WorkManager system service.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.bidet_clean_gen_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.bidet_clean_gen_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "CleanGenerationService"
        const val CHANNEL_ID = "bidet_clean_generation"
        const val NOTIFICATION_ID = 1102
        const val ACTION_START_GENERATION = "ai.bidet.phone.action.START_CLEAN_GENERATION"
        const val ACTION_CANCEL_GENERATION = "ai.bidet.phone.action.CANCEL_CLEAN_GENERATION"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_AXIS = "extra_axis"
        const val EXTRA_SYSTEM_PROMPT = "extra_system_prompt"
        const val EXTRA_USER_PROMPT = "extra_user_prompt"
        const val EXTRA_TOKEN_CAP = "extra_token_cap"
        const val EXTRA_TEMPERATURE = "extra_temperature"

        const val DEFAULT_TOKEN_CAP: Int = 2048
        const val DEFAULT_TEMPERATURE: Float = 0.4f

        /**
         * Per-window output cap when [com.google.ai.edge.gallery.bidet.cleaning.RawChunker]
         * splits the RAW. Lower than the single-shot cap so wall-clock stays bearable on
         * Tensor G3 CPU (a 6-window 18-min dump at 2048 tokens/window = ~30 min decode;
         * at 512 tokens/window = ~7 min decode).
         */
        const val CHUNKED_PER_WINDOW_OUTPUT_TOKEN_CAP: Int = 512

        fun startIntent(
            context: Context,
            sessionId: String,
            axis: SupportAxis,
            systemPrompt: String,
            userPrompt: String,
            tokenCap: Int,
            temperature: Float,
        ): Intent = Intent(context, CleanGenerationService::class.java)
            .setAction(ACTION_START_GENERATION)
            .putExtra(EXTRA_SESSION_ID, sessionId)
            .putExtra(EXTRA_AXIS, axis.name)
            .putExtra(EXTRA_SYSTEM_PROMPT, systemPrompt)
            .putExtra(EXTRA_USER_PROMPT, userPrompt)
            .putExtra(EXTRA_TOKEN_CAP, tokenCap)
            .putExtra(EXTRA_TEMPERATURE, temperature)

        fun cancelIntent(context: Context): Intent =
            Intent(context, CleanGenerationService::class.java)
                .setAction(ACTION_CANCEL_GENERATION)
    }
}
