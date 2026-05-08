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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.bidet.audio.AudioCaptureEngine
import com.google.ai.edge.gallery.bidet.chunk.ChunkQueue
import com.google.ai.edge.gallery.bidet.transcript.TranscriptAggregator
import com.google.ai.edge.gallery.bidet.transcription.TranscriptionWorker
import com.google.ai.edge.gallery.bidet.transcription.WhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.UUID

/**
 * Foreground microphone service that owns the brain-dump capture pipeline.
 *
 * Brief §1:
 *  - `foregroundServiceType="microphone"` (declared in manifest); we call the 3-arg
 *    [startForeground] overload with [ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE].
 *  - Notification is `setOngoing(true)` non-dismissible with a "Stop" action.
 *  - AudioFocus listener pauses on transient loss (incoming call), resumes on gain.
 *  - Runtime [android.permission.RECORD_AUDIO] grant is the caller's responsibility — see
 *    `BidetTabsScreen` (it requests the permission with `rememberLauncherForActivityResult`
 *    before binding to this service).
 *
 * The service is bound by the UI for live state access; it also runs as foreground so it
 * survives screen-off and process death.
 */
class RecordingService : Service() {

    /**
     * Holder of pipeline objects. Exposed via the [LocalBinder] so the UI can collect from
     * the aggregator's flow + observe queue backpressure.
     */
    class Pipeline internal constructor(
        val sessionId: String,
        val captureEngine: AudioCaptureEngine,
        val chunkQueue: ChunkQueue,
        val aggregator: TranscriptAggregator,
        val whisperEngine: WhisperEngine,
        val worker: TranscriptionWorker,
    )

    inner class LocalBinder : Binder() {
        fun service(): RecordingService = this@RecordingService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var pipeline: Pipeline? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var paused: Boolean = false

    /** Visible to bound clients (the UI). May be null before [startRecording] is called. */
    fun pipeline(): Pipeline? = pipeline

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRecording()
        scope.cancel()
        super.onDestroy()
    }

    /** Begin a new recording session. Safe to call when already recording (no-op). */
    fun startRecording() {
        if (pipeline != null) return
        startForegroundCompat()
        if (!requestAudioFocus()) {
            Log.w(TAG, "Audio focus denied — proceeding anyway.")
        }
        val sessionId = UUID.randomUUID().toString()
        val chunkQueue = ChunkQueue()
        val aggregator = TranscriptAggregator()
        val whisperEngine = WhisperEngine(applicationContext).also { it.initialize() }
        val worker = TranscriptionWorker(
            chunkQueue = chunkQueue,
            whisperEngine = whisperEngine,
            aggregator = aggregator,
            scope = scope,
        )
        val captureEngine = AudioCaptureEngine(
            context = applicationContext,
            chunkQueue = chunkQueue,
            sessionId = sessionId,
        )
        worker.start()
        captureEngine.start()
        pipeline = Pipeline(
            sessionId = sessionId,
            captureEngine = captureEngine,
            chunkQueue = chunkQueue,
            aggregator = aggregator,
            whisperEngine = whisperEngine,
            worker = worker,
        )
    }

    /** Stop and tear down the pipeline. Safe to call multiple times. */
    fun stopRecording() {
        pipeline?.let {
            it.captureEngine.stop()
            it.worker.stop()
            it.chunkQueue.close()
            it.whisperEngine.close()
        }
        pipeline = null
        abandonAudioFocus()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
            // ignore
        }
        stopSelf()
    }

    // ---------- foreground notification ----------

    private fun startForegroundCompat() {
        val stopIntent = Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.bidet_recording_notification_title))
            .setContentText(getString(R.string.bidet_recording_notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(
                NotificationCompat.Action.Builder(
                    /* icon */ 0,
                    getString(R.string.bidet_recording_stop_action),
                    stopPi,
                ).build()
            )
            // MediaStyle omitted intentionally; would need an extra
            // androidx.media:media dep for a cosmetic chrome change. Phase 2.
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
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
            getString(R.string.bidet_recording_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.bidet_recording_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    // ---------- audio focus ----------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (!paused) {
                    pipeline?.captureEngine?.stop()
                    paused = true
                    Log.i(TAG, "AudioFocus transient loss — pausing capture.")
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (paused) {
                    pipeline?.captureEngine?.start()
                    paused = false
                    Log.i(TAG, "AudioFocus gain — resuming capture.")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "AudioFocus permanent loss — stopping recording.")
                stopRecording()
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        audioFocusRequest = req
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    companion object {
        private const val TAG = "BidetRecordingService"
        const val CHANNEL_ID = "bidet_recording"
        const val NOTIFICATION_ID = 1101
        const val ACTION_START = "ai.bidet.phone.action.START_RECORDING"
        const val ACTION_STOP = "ai.bidet.phone.action.STOP_RECORDING"

        fun startIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
    }
}
