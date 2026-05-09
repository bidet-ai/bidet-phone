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

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.bidet.audio.AudioCaptureEngine
import com.google.ai.edge.gallery.bidet.audio.WavConcatenator
import com.google.ai.edge.gallery.bidet.chunk.ChunkQueue
import com.google.ai.edge.gallery.bidet.data.BidetSession
import com.google.ai.edge.gallery.bidet.data.BidetSessionDao
import com.google.ai.edge.gallery.bidet.transcript.TranscriptAggregator
import com.google.ai.edge.gallery.bidet.transcription.TranscriptionEngine
import com.google.ai.edge.gallery.bidet.transcription.TranscriptionWorker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
@AndroidEntryPoint
class RecordingService : Service() {

    @Inject lateinit var sessionDao: BidetSessionDao

    /**
     * Holder of pipeline objects. Exposed via the [LocalBinder] so the UI can collect from
     * the aggregator's flow + observe queue backpressure.
     */
    class Pipeline internal constructor(
        val sessionId: String,
        val captureEngine: AudioCaptureEngine,
        val chunkQueue: ChunkQueue,
        val aggregator: TranscriptAggregator,
        val transcriptionEngine: TranscriptionEngine,
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

    /**
     * 2026-05-09 (Bug B fix): observable status for the UI.
     *
     * Replaces the prior pattern where the Composable read `pipeline()` once via
     * ServiceConnection.onServiceConnected — that callback only fires at first bind
     * (before any pipeline exists), so a tap-Record after the bind never propagated
     * back to the UI and the recording UI never appeared.
     *
     * `statusFlow` emits whenever startRecording / stopRecording flips state. The
     * `startedAtMs` field drives the live timer in the recording header.
     */
    data class Status(
        val isRecording: Boolean,
        val sessionId: String?,
        val startedAtMs: Long,
        val pipeline: Pipeline?,
        /**
         * F2.1 fix (2026-05-09): non-null when the most recent startRecording() attempt
         * failed because the transcription engine could not initialize (e.g. Whisper model
         * file missing from APK assets, Gemma model not yet downloaded). The UI binds this
         * to a banner so the user sees an actionable message instead of the silent
         * "[chunk N transcription failed]" cascade that bit Mark on the flavor APKs.
         */
        val engineInitError: EngineInitError? = null,
    )

    /**
     * Why the engine failed to initialize, with the engine type so the UI can render a
     * targeted message ("Whisper model missing — reinstall APK" vs "Gemma model not
     * downloaded — finish first-run download"). The reason string is whatever the engine
     * surfaced in its own initialize() catch block — included for the developer log only;
     * the UI uses [engine] to pick the user-visible copy.
     */
    data class EngineInitError(
        val engine: EngineType,
        val reason: String,
    )

    enum class EngineType { Whisper, Gemma }

    private val _statusFlow = MutableStateFlow(Status(false, null, 0L, null, null))
    val statusFlow: StateFlow<Status> = _statusFlow.asStateFlow()

    /**
     * Phase 4A: tracks per-recording persistence state. We:
     *  - create a BidetSession row on startRecording (rawText empty)
     *  - tail aggregator.rawFlow and `update()` the row as text accrues (so a process kill
     *    leaves a partial-but-readable row rather than nothing)
     *  - on stopRecording: concat chunk PCMs into audio.wav, finalize the row with
     *    endedAtMs / durationSeconds / chunkCount / audioWavPath.
     */
    private var sessionStartedAtMs: Long = 0L
    private var sessionPersistJob: Job? = null

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
        // Phase 4A.1: do NOT call stopRecording() here. If the service is being torn down
        // mid-finalize (e.g. OS shutting us down for memory pressure), stopRecording()'s
        // launch{} will get cancelled the moment we hit `scope.cancel()` below. The
        // refactored stopRecording() now handles its own teardown via the launch{} block;
        // by the time onDestroy() fires there should be no pipeline in flight. Keep
        // scope.cancel() so any straggler coroutines don't leak past process death.
        scope.cancel()
        super.onDestroy()
    }

    /** Begin a new recording session. Safe to call when already recording (no-op). */
    fun startRecording() {
        if (pipeline != null) return

        // Phase 4A.1: gate on RECORD_AUDIO. The launcher UI also requests this permission via
        // rememberLauncherForActivityResult, but the user can revoke from Settings while
        // the service is alive (or before a recovery start after process death). Without
        // this guard the AudioRecord ctor would silently fail, the aggregator would never
        // emit, and the session row would stay with `endedAtMs=null` forever.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted — refusing to start recording.")
            handlePermissionDenied()
            return
        }

        val sessionId = UUID.randomUUID().toString()
        val chunkQueue = ChunkQueue()
        val aggregator = TranscriptAggregator()
        // Pick engine per Gradle product flavor (BuildConfig.USE_GEMMA_AUDIO).
        // whisper flavor → WhisperEngine. gemma flavor → GemmaAudioEngine.
        //
        // F2.1 fix (2026-05-09): previously the `.also { it.initialize() }` discarded the
        // boolean return value. When init failed (e.g. the user's APK was assembled without
        // the bundled Whisper model — exactly the bug that bit Mark on the gemma flavor
        // build), the audio capture loop still started, every chunk fell through to the
        // failure-marker path, and the user saw "[chunk N transcription failed]" with no
        // explanation. The gate below runs initialize() and returns InitFailed if it
        // returned false; the failure-row insert + native-resource close happen inside
        // the gate so RecordingService stays focused on Service lifecycle. See
        // [EngineInitGate] for the testable kernel of this decision.
        val transcriptionEngine = engineFactory(applicationContext)
        when (val outcome = EngineInitGate.tryInit(
            engine = transcriptionEngine,
            sessionId = sessionId,
            now = System.currentTimeMillis(),
            classifyEngine = ::engineTypeOf,
        )) {
            is EngineInitGate.Outcome.InitFailed -> {
                Log.e(
                    TAG,
                    "Transcription engine init failed (engine=${outcome.engine}) — refusing to start capture pipeline.",
                )
                // Persist the failure row so it shows up in History as a terminal failure
                // rather than an empty in-progress session that never finalizes.
                scope.launch {
                    try {
                        sessionDao.insert(outcome.failureRow)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to insert engine_init_failed row: ${t.message}", t)
                    }
                }
                // Surface to the UI + tear ourselves down. Note we have NOT yet promoted
                // to foreground (deferred until after init succeeds), so all we need to
                // do is emit the failure status and stopSelf().
                _statusFlow.value = Status(
                    isRecording = false,
                    sessionId = null,
                    startedAtMs = 0L,
                    pipeline = null,
                    engineInitError = EngineInitError(
                        engine = outcome.engine,
                        reason = ENGINE_INIT_FAILED_NOTE,
                    ),
                )
                stopSelf()
                return
            }
            EngineInitGate.Outcome.Ready -> { /* fall through to start the pipeline */ }
        }
        // Defer foreground promotion until after the engine init succeeds. Promoting before
        // the bail-out path above would leave the user staring at the "Recording…" notification
        // for a session that immediately tore itself down. Acquire audio focus only when we're
        // committing to actually capture audio.
        startForegroundCompat()
        if (!requestAudioFocus()) {
            Log.w(TAG, "Audio focus denied — proceeding anyway.")
        }
        val worker = TranscriptionWorker(
            chunkQueue = chunkQueue,
            transcriptionEngine = transcriptionEngine,
            aggregator = aggregator,
            scope = scope,
        )
        val captureEngine = AudioCaptureEngine(
            context = applicationContext,
            chunkQueue = chunkQueue,
            sessionId = sessionId,
        )
        // F2.1 (2026-05-09): worker.start() now also returns false on a not-ready engine
        // (defence-in-depth — the gate above already gated on initialize(), but if the
        // worker is ever wired up directly elsewhere we don't want it to silently spin a
        // consumer that turns every chunk into a failure marker). On the happy path here
        // the engine is ready, so this is always true.
        if (!worker.start()) {
            Log.e(TAG, "TranscriptionWorker refused to start — engine reported not ready post-init.")
            try { transcriptionEngine.close() } catch (_: Throwable) {}
            tearDownForeground()
            abandonAudioFocus()
            // Don't re-insert a session row — the gate above already covered the
            // not-ready case. Just emit the banner state.
            _statusFlow.value = Status(
                isRecording = false,
                sessionId = null,
                startedAtMs = 0L,
                pipeline = null,
                engineInitError = EngineInitError(
                    engine = engineTypeOf(transcriptionEngine),
                    reason = ENGINE_INIT_FAILED_NOTE,
                ),
            )
            return
        }
        captureEngine.start()
        pipeline = Pipeline(
            sessionId = sessionId,
            captureEngine = captureEngine,
            chunkQueue = chunkQueue,
            aggregator = aggregator,
            transcriptionEngine = transcriptionEngine,
            worker = worker,
        )

        // Phase 4A: persist the session so SessionsListScreen can show it immediately and
        // SessionDetailScreen can restore it later. We insert with empty rawText, then tail
        // the aggregator and rewrite the row with each new transcript snapshot.
        sessionStartedAtMs = System.currentTimeMillis()

        // Bug B (2026-05-09): emit on the status flow so the UI's collectAsState fires and
        // re-composes into the recording-active screen.
        _statusFlow.value = Status(
            isRecording = true,
            sessionId = sessionId,
            startedAtMs = sessionStartedAtMs,
            pipeline = pipeline,
            engineInitError = null,
        )
        scope.launch {
            try {
                sessionDao.insert(
                    BidetSession(
                        sessionId = sessionId,
                        startedAtMs = sessionStartedAtMs,
                        endedAtMs = null,
                        durationSeconds = 0,
                        rawText = "",
                        chunkCount = 0,
                    )
                )
            } catch (t: Throwable) {
                Log.w(TAG, "BidetSession insert failed: ${t.message}", t)
            }
        }
        sessionPersistJob = scope.launch {
            // Throttle: each emission triggers a single column-targeted UPDATE; aggregator
            // updates ~once per ~30 sec (chunk emit), so this is cheap. We catch + log;
            // transient I/O failure shouldn't tear down the recording pipeline.
            //
            // Phase 4A.1: previously did `getById → copy(rawText = text) → update(entity)`,
            // which would clobber any column written concurrently by SessionDetailViewModel
            // (tab-cache writes against the same sessionId during a re-open). The
            // column-targeted updateRawText is atomic.
            aggregator.rawFlow.collectLatest { text ->
                try {
                    sessionDao.updateRawText(sessionId, text)
                } catch (t: Throwable) {
                    Log.w(TAG, "BidetSession rawText update failed: ${t.message}", t)
                }
            }
        }
    }

    /**
     * Stop and tear down the pipeline. Safe to call multiple times.
     *
     * Phase 4A.1: previous shape called stopForeground + stopSelf eagerly, then launched
     * finalizeSessionRow on `scope`. If Android tore the service down (long sessions, slow
     * eMMC, OOM-killer) before WAV concat completed, onDestroy()'s `scope.cancel()` would
     * abort finalize, leaving `audio.wav.tmp` orphaned and the row's `audioWavPath` null →
     * Share/Export silently broken.
     *
     * Fix: keep the foreground service alive until finalize completes, then tear down on
     * the Main dispatcher inside the same launch{}. The notification stays visible during
     * concat so the OS won't reclaim us mid-flush.
     */
    fun stopRecording() {
        val activePipeline = pipeline
        val sessionId = activePipeline?.sessionId
        val finalRawText = activePipeline?.aggregator?.currentText().orEmpty()
        val recordingStartedAt = sessionStartedAtMs

        sessionPersistJob?.cancel()
        sessionPersistJob = null

        activePipeline?.let {
            it.captureEngine.stop()
            it.worker.stop()
            it.chunkQueue.close()
            it.transcriptionEngine.close()
        }
        pipeline = null
        abandonAudioFocus()

        // Bug B (2026-05-09): emit recorded-stopped state so the UI flips back to the
        // welcome screen. The session row finalize keeps running on `scope` below.
        // Clear any prior engine-init banner — a successful stop after a successful start
        // means the engine was fine.
        _statusFlow.value = Status(false, null, 0L, null, null)

        if (sessionId != null && recordingStartedAt > 0L) {
            scope.launch {
                try {
                    finalizeSessionRow(sessionId, recordingStartedAt, finalRawText)
                } catch (t: Throwable) {
                    Log.w(TAG, "finalizeSessionRow threw: ${t.message}", t)
                } finally {
                    withContext(Dispatchers.Main) { tearDownForeground() }
                }
            }
        } else {
            // No active session — tear down immediately.
            tearDownForeground()
        }
    }

    /** Phase 4A.1: split out so the finalize-then-stop coroutine has one teardown call. */
    private fun tearDownForeground() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
            // ignore
        }
        stopSelf()
    }

    /**
     * Phase 4A.1: when [startRecording] is invoked without RECORD_AUDIO, we still want a
     * record of the attempt in the sessions list (so the user understands why nothing
     * appears) and we want the service to clean itself up. We don't call startForeground
     * here — without RECORD_AUDIO Android won't let a microphone-type FGS run anyway.
     */
    private fun handlePermissionDenied() {
        val sessionId = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        scope.launch {
            try {
                sessionDao.insert(
                    BidetSession(
                        sessionId = sessionId,
                        startedAtMs = now,
                        endedAtMs = now,
                        durationSeconds = 0,
                        rawText = "",
                        chunkCount = 0,
                        notes = "permission_denied",
                    )
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to insert permission_denied row: ${t.message}", t)
            } finally {
                withContext(Dispatchers.Main) { stopSelf() }
            }
        }
    }

    /**
     * F2.1 (2026-05-09): pluggable factory + engine-type classifier. Production keeps the
     * default which delegates to [TranscriptionEngine.create] (flavor-driven). The
     * pure-Kotlin failure decision lives in [EngineInitGate] (unit-tested in
     * `EngineInitGateTest`); this var stays here so a future Robolectric integration test
     * can swap in a fake without touching production wiring. Kept as `internal var` rather
     * than a full dagger module rewire because this is a correctness fix, not a refactor.
     */
    @VisibleForTesting
    internal var engineFactory: (Context) -> TranscriptionEngine = { ctx ->
        TranscriptionEngine.create(ctx)
    }

    private fun engineTypeOf(engine: TranscriptionEngine): EngineType {
        // Use class simple name rather than `is` because referencing GemmaAudioEngine /
        // WhisperEngine directly would tie the service to both classes; the existing
        // codebase already keeps RecordingService engine-agnostic via the factory. The
        // simple-name check stays correct under R8 since both engine classes are kept by
        // proguard-rules (they're the only impls of the interface).
        return when (engine.javaClass.simpleName) {
            "GemmaAudioEngine" -> EngineType.Gemma
            else -> EngineType.Whisper
        }
    }

    /**
     * Phase 4A: concatenate per-chunk PCM files into one playable `audio.wav`, then update
     * the persisted [BidetSession] row with terminal fields.
     */
    private suspend fun finalizeSessionRow(
        sessionId: String,
        startedAtMs: Long,
        finalRawText: String,
    ) {
        val endedAtMs = System.currentTimeMillis()
        val durationSeconds = ((endedAtMs - startedAtMs) / 1000L).toInt().coerceAtLeast(0)

        val (wavPath, chunkCount) = withContext(Dispatchers.IO) {
            val wav: File? = try {
                WavConcatenator.concatenateChunksToWav(applicationContext, sessionId)
            } catch (t: Throwable) {
                Log.w(TAG, "concatenateChunksToWav threw: ${t.message}", t)
                null
            }
            val chunksDir = applicationContext.getExternalFilesDir(null)
                ?.let { File(it, "sessions/$sessionId/chunks") }
            val count = chunksDir?.listFiles { f ->
                f.isFile && f.name.endsWith(".pcm") && !f.name.endsWith(".tmp")
            }?.size ?: 0
            wav?.absolutePath to count
        }

        try {
            // Phase 4A.1: single column-targeted finalize UPDATE. Previously did
            // `getById → copy(...) → update(entity)`, which raced the in-flight
            // updateRawText emissions from sessionPersistJob (since stopRecording cancels
            // the persist job but a final emission may still be pending the DB write).
            // We resolve "what rawText to keep" against the latest persisted row before
            // dispatching the atomic UPDATE.
            val existing = sessionDao.getById(sessionId)
            val resolvedRawText = if (finalRawText.isNotBlank()) finalRawText
                else existing?.rawText.orEmpty()
            sessionDao.finalizeSession(
                sessionId = sessionId,
                endedAtMs = endedAtMs,
                durationSeconds = durationSeconds,
                rawText = resolvedRawText,
                chunkCount = chunkCount,
                audioWavPath = wavPath,
                notes = null, // preserve existing notes (e.g. "permission_denied")
            )
        } catch (t: Throwable) {
            Log.w(TAG, "BidetSession finalize update failed: ${t.message}", t)
        }
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
                    // Phase 4A.1: soft-pause. Previous code called captureEngine.stop()/start()
                    // which reset nextChunkIdx=0 + cleared the pending buffer + backbuffer,
                    // overwriting `0.pcm`/`1.pcm`/... in the same sessions/<id>/chunks/ dir.
                    pipeline?.captureEngine?.pause()
                    paused = true
                    Log.i(TAG, "AudioFocus transient loss — soft-pausing capture.")
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (paused) {
                    pipeline?.captureEngine?.resume()
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

        /**
         * F2.1 (2026-05-09): persisted on the [BidetSession.notes] column when a recording
         * attempt aborts because the transcription engine could not initialize. Surfaced
         * by SessionsListScreen as "engine init failed" instead of an empty in-progress
         * row that never finalizes.
         */
        const val ENGINE_INIT_FAILED_NOTE: String = "engine_init_failed"

        fun startIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
    }
}
