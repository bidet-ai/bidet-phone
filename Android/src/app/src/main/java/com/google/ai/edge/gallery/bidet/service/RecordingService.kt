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
import android.media.ToneGenerator
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.bidet.audio.AudioCaptureEngine
import com.google.ai.edge.gallery.bidet.audio.WavConcatenator
import com.google.ai.edge.gallery.bidet.chunk.ChunkQueue
import com.google.ai.edge.gallery.bidet.cleaning.ChunkCleaner
// v24 (2026-05-14): Glossary + SupportAxis + TabPref imports were dropped along with the
// per-chunk pre-cleaning code path during recording. They remain referenced by the
// SessionDetail-driven on-tap cleaning, which is now the only cleaning path.
import com.google.ai.edge.gallery.bidet.data.BidetSession
import com.google.ai.edge.gallery.bidet.data.BidetSessionDao
import com.google.ai.edge.gallery.bidet.transcript.TranscriptAggregator
import com.google.ai.edge.gallery.bidet.ui.BidetGemmaClient
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
import kotlinx.coroutines.flow.collect
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
     * v24 (2026-05-14): formerly used by the per-chunk [ChunkCleaner] (now disabled
     * during recording per the STT-first ordering pivot — see [performAsyncStartup]).
     * Kept on the @Inject graph because the Hilt module's BidetGemmaClient binding is
     * still required by the SessionDetail on-tap cleaning path; removing this field
     * doesn't shrink the graph. If a future v25 reinstates Path B, this is where the
     * cleaner gets its LLM handle from.
     */
    @Suppress("unused")
    @Inject lateinit var gemmaClient: BidetGemmaClient

    /** Path B (2026-05-10): per-session pre-cleaner, lifecycle scoped to one recording. */
    private var chunkCleaner: ChunkCleaner? = null

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
    private var capWatcherJob: Job? = null
    /**
     * 2026-05-09: synchronous "startup in flight" flag. Set the moment startRecording() runs
     * (before the scope.launch that initializes the engine), cleared at the end of
     * performAsyncStartup. Prevents a rapid double-tap from spawning two concurrent
     * performAsyncStartup runs while [pipeline] is still null because async init is in flight.
     */
    private var startupInFlight: Boolean = false

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
     *
     * Bug-2 fix (2026-05-10): adds [drainProgress]. After [stopRecording] the FGS keeps
     * running until the worker drains; during that window `isRecording` is false (so the
     * UI flips back to the welcome screen) but [drainProgress] is non-null so the
     * notification + History indicator can still show "Transcribing N of M chunks…".
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
        /**
         * Bug-2 fix (2026-05-10): non-null while the FGS is in the post-stop drain phase
         * (worker still chewing through queued chunks after the user tapped Stop). Cleared
         * when the worker queue is empty and finalize has run. Drives the notification
         * "Transcribing N of M chunks remaining…" + the History row + SessionDetail banner.
         */
        val drainProgress: DrainProgress? = null,
    )

    /**
     * Bug-2 fix (2026-05-10): current state of the post-stop drain. [sessionId] is the
     * session being drained so the History UI can correlate. [merged] / [produced] feed
     * the "N of M" text. [completed] flips true once finalize has run; the FGS reads it
     * to decide when to tear down + post the completion notification.
     */
    data class DrainProgress(
        val sessionId: String,
        val merged: Int,
        val produced: Int,
        val completed: Boolean,
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

    private val _statusFlow = MutableStateFlow(Status(false, null, 0L, null, null, null))
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

    /**
     * Bug-3 fix (2026-05-10): mirrors AudioCaptureEngine.producedChunkCountFlow onto
     * BidetSession.chunkCount so the History UI's "Transcribing N of M…" indicator has the
     * denominator. Lifecycle is identical to [sessionPersistJob].
     */
    private var chunkProducedMirrorJob: Job? = null

    /**
     * Bug-2 fix (2026-05-10): the post-stop drain coroutine. Spawned by [stopRecording],
     * keeps the foreground service alive until the worker has processed every queued chunk
     * AND finalize has run. Cancelling it (e.g. during onDestroy under memory pressure) is
     * fine — the FGS will tear down on the same path; the only loss is the on-disk audio
     * chunks that were never transcribed get lost. The session row's mergedChunkCount /
     * chunkCount tells you exactly how much was salvaged.
     */
    private var drainJob: Job? = null

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
        if (pipeline != null || startupInFlight) return

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

        // startForeground must be called within 5s of startForegroundService on Android 12+ (per onStartCommand contract). Gemma engine init exceeds this — start foreground FIRST.
        startupInFlight = true
        startupRecorder.recordStartForeground()
        startForegroundWithStartingPlaceholder()

        scope.launch {
            try {
                performAsyncStartup()
            } finally {
                startupInFlight = false
            }
        }
    }

    /**
     * Async tail of [startRecording]. Runs on `scope` so the slow Gemma `initialize()` does
     * not block the synchronous startForegroundService → startForeground window. On init
     * failure we tear down the foreground we already promoted; on success we swap the
     * placeholder notification for the real "Bidet AI is recording" notification and start
     * the capture pipeline.
     */
    private suspend fun performAsyncStartup() {
        val sessionId = UUID.randomUUID().toString()
        val chunkQueue = ChunkQueue()
        // v24 (2026-05-14): STT-first runtime ordering — the per-chunk ChunkCleaner is
        // DISABLED during recording. Path A (on-tap cleaning at the SessionDetail screen)
        // is the production path. Rationale: Path B's ChunkCleaner acquires the shared
        // LiteRT-LM Gemma engine (2.4 GB resident on E2B) during recording, which fights
        // Moonshine STT for memory + CPU. Mark's v23 symptom: a 3-min brain dump only
        // transcribed the first chunk because the engine mutex was held by a cleaner run
        // when Moonshine tried to transcribe chunk 1. With cleaner disabled, Moonshine has
        // the device to itself during recording and the live RAW transcript appears
        // chunk-by-chunk exactly as the contest narrative promises.
        //
        // Cleaning still happens — just lazily, after Stop, when the user taps the Clean /
        // Analysis / Judges tab on SessionDetail. That's exactly Mark's approved trade-off:
        // "moonshine had done his thing as quickly as possible. And then... the Gemma, the
        //  one that's taken a few minutes."
        //
        // Path B can be reinstated by a future opt-in setting; the code path stays
        // exercised by [ChunkCleaner]'s unit tests + [SessionDetailViewModel.generate]'s
        // pre-cleaned-on-disk short-circuit (which gracefully no-ops if no cleanings
        // file is present, falling through to the on-tap clean).
        val cleaner: ChunkCleaner? = null
        chunkCleaner = cleaner

        val aggregator = TranscriptAggregator(
            onMutation = { text, mergedCount ->
                sessionDao.updateRawTextAndMergedChunkCount(
                    sessionId = sessionId,
                    text = text,
                    mergedChunkCount = mergedCount,
                )
            },
            onChunkAppended = { idx, chunkText ->
                cleaner?.enqueue(idx, chunkText)
            },
        )
        // Pick engine per Gradle product flavor (BuildConfig.USE_GEMMA_AUDIO).
        // whisper flavor → WhisperEngine. gemma flavor → GemmaAudioEngine.
        startupRecorder.recordEngineInit()
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
                try {
                    sessionDao.insert(outcome.failureRow)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to insert engine_init_failed row: ${t.message}", t)
                }
                _statusFlow.value = Status(
                    isRecording = false,
                    sessionId = null,
                    startedAtMs = 0L,
                    pipeline = null,
                    engineInitError = EngineInitError(
                        engine = outcome.engine,
                        reason = ENGINE_INIT_FAILED_NOTE,
                    ),
                    drainProgress = null,
                )
                tearDownForeground()
                return
            }
            EngineInitGate.Outcome.Ready -> { /* fall through to start the pipeline */ }
        }
        // Engine is up — swap the placeholder notification for the real one without
        // re-promoting (we're already foregrounded). NotificationManager.notify against the
        // same NOTIFICATION_ID rebinds the live foreground notification.
        swapToRecordingNotification()
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
        if (!worker.start()) {
            Log.e(TAG, "TranscriptionWorker refused to start — engine reported not ready post-init.")
            try { transcriptionEngine.close() } catch (_: Throwable) {}
            abandonAudioFocus()
            _statusFlow.value = Status(
                isRecording = false,
                sessionId = null,
                startedAtMs = 0L,
                pipeline = null,
                engineInitError = EngineInitError(
                    engine = engineTypeOf(transcriptionEngine),
                    reason = ENGINE_INIT_FAILED_NOTE,
                ),
                drainProgress = null,
            )
            tearDownForeground()
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

        sessionStartedAtMs = System.currentTimeMillis()

        _statusFlow.value = Status(
            isRecording = true,
            sessionId = sessionId,
            startedAtMs = sessionStartedAtMs,
            pipeline = pipeline,
            engineInitError = null,
            drainProgress = null,
        )
        try {
            sessionDao.insert(
                BidetSession(
                    sessionId = sessionId,
                    startedAtMs = sessionStartedAtMs,
                    endedAtMs = null,
                    durationSeconds = 0,
                    rawText = "",
                    chunkCount = 0,
                    mergedChunkCount = 0,
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "BidetSession insert failed: ${t.message}", t)
        }
        // Bug-1 fix (2026-05-10): per-merge persistence is owned by TranscriptAggregator's
        // onMutation callback (see aggregator construction above). The previous
        // `aggregator.rawFlow.collectLatest { dao.updateRawText(...) }` lived on a separate
        // scope and was cancelled at stopRecording before the worker drained, losing all
        // post-stop chunk merges. We keep `sessionPersistJob` as defense-in-depth: if the
        // synchronous callback ever throws (Room migration error, low disk), the periodic
        // collector at least keeps the rawText snapshot up to date.
        sessionPersistJob = scope.launch {
            aggregator.rawFlow.collectLatest { text ->
                try {
                    sessionDao.updateRawText(sessionId, text)
                } catch (t: Throwable) {
                    Log.w(TAG, "BidetSession rawText update failed: ${t.message}", t)
                }
            }
        }
        // Bug-3 fix (2026-05-10): mirror the produced-chunk count onto the session row so
        // History rows can render "Transcribing N of M chunks…". Cancelled in stopRecording
        // alongside sessionPersistJob.
        chunkProducedMirrorJob = scope.launch {
            captureEngine.producedChunkCountFlow.collectLatest { produced ->
                if (produced <= 0) return@collectLatest
                try {
                    sessionDao.updateChunkCount(sessionId, produced)
                } catch (t: Throwable) {
                    Log.w(TAG, "BidetSession chunkCount update failed: ${t.message}", t)
                }
            }
        }

        capWatcherJob = capWatcherFactory(this).start(scope, sessionStartedAtMs)
    }

    /**
     * Stop and tear down the pipeline. Safe to call multiple times.
     *
     * Phase 4A.1: previous shape called stopForeground + stopSelf eagerly, then launched
     * finalizeSessionRow on `scope`. If Android tore the service down (long sessions, slow
     * eMMC, OOM-killer) before WAV concat completed, onDestroy()'s `scope.cancel()` would
     * abort finalize, leaving `audio.wav.tmp` orphaned and the row's `audioWavPath` null →
     * Share/Export silently broken. Fix: keep the foreground service alive until finalize
     * completes, then tear down on the Main dispatcher inside the same launch{}.
     *
     * F1.1 fix (2026-05-09): the previous teardown sequence was
     *     captureEngine.stop()
     *     worker.stop()              ← only CANCELS the worker's Job
     *     chunkQueue.close()
     *     transcriptionEngine.close()
     * `worker.stop()` does not JOIN the in-flight transcribe — and because the worker's
     * `handleAudio` wraps its transcribe call in
     * `withContext(NonCancellable + Dispatchers.Default)`, a transcribe that started
     * before Stop keeps running. Then we immediately `transcriptionEngine.close()` and
     * pull the native pointer out from under whisper.cpp / LiteRT-LM. Classic
     * use-after-free; on a real device the failure mode is "user taps Stop mid-transcribe
     * → app dies + ANR". Bad demo crash.
     *
     * Now: the synchronous half (stop capture, drop pipeline ref, flip the StateFlow,
     * abandon audio focus) still runs up front so the UI flips back to the welcome
     * screen the instant the user taps Stop. The async half — `worker.stopAndJoin()` →
     * `chunkQueue.close()` → `transcriptionEngine.close()` → finalize — runs on `scope`.
     * The join lets the in-flight transcribe write its result to the aggregator + DB
     * before we tear down native resources, so the user gets the last chunk's text in
     * their saved session instead of a crash.
     */
    fun stopRecording() {
        val activePipeline = pipeline
        val sessionId = activePipeline?.sessionId
        val recordingStartedAt = sessionStartedAtMs

        // Bug-1 fix (2026-05-10): the persist job is now defense-in-depth — the aggregator
        // writes through to the DB synchronously inside its mutex. We still cancel it here
        // because the worker keeps running through the drain phase (Bug-2) and re-emits to
        // rawFlow on every merge; the synchronous onMutation has already written that
        // text. Letting collectLatest race the mutation callback wastes a Room write per
        // merge for no benefit.
        sessionPersistJob?.cancel()
        sessionPersistJob = null
        chunkProducedMirrorJob?.cancel()
        chunkProducedMirrorJob = null

        // Path B (2026-05-10): close the pre-cleaner queue so the worker drains its current
        // task and exits cleanly. The cleaner owns its own SupervisorJob scope so the
        // worker continues running even after RecordingService.scope.cancel() — pending
        // cleanings finish writing to disk; the next App-process death is the only thing
        // that can interrupt them. The on-tap path handles missing files gracefully.
        chunkCleaner?.drainAndStop()
        chunkCleaner = null

        // Cap-watcher cancellation MUST run before we clear `pipeline` — the watcher's
        // beep coroutine is what stops mid-cadence when the user taps STOP early
        // (spec contract: "if the user taps STOP early, the beeps STOP").
        capWatcherJob?.cancel()
        capWatcherJob = null

        // Synchronous half: stop capture (so no NEW chunks land), drop the pipeline ref,
        // flip the UI status flow, abandon audio focus. Everything that needs the
        // user-visible state to flip RIGHT NOW happens here.
        activePipeline?.captureEngine?.stop()
        pipeline = null
        abandonAudioFocus()

        if (activePipeline == null || sessionId == null || recordingStartedAt <= 0L) {
            // No active session — flip to idle and tear down immediately. Nothing to drain.
            _statusFlow.value = Status(false, null, 0L, null, null, null)
            tearDownForeground()
            return
        }

        // Bug-2 fix (2026-05-10): we DO NOT tear down the foreground service here. We keep
        // it alive while the worker drains the queue. The UI-visible "isRecording" flips
        // to false immediately (welcome screen returns) but `drainProgress` carries the
        // ongoing transcription state for the notification + the History progress
        // indicator. The FGS only stops once the worker's job has completed naturally
        // AND finalize has written the terminal row.
        val initialMerged = activePipeline.aggregator.mergedCountFlow.value
        val initialProduced = activePipeline.captureEngine.producedChunkCountFlow.value
        _statusFlow.value = Status(
            isRecording = false,
            sessionId = null,
            startedAtMs = 0L,
            pipeline = null,
            engineInitError = null,
            drainProgress = DrainProgress(
                sessionId = sessionId,
                merged = initialMerged,
                produced = initialProduced,
                completed = false,
            ),
        )

        // Swap the recording notification for the draining one immediately so the user
        // sees "Transcribing N of M chunks remaining…" the moment they tap Stop. The
        // notification will re-emit as merged advances (see drainJob below).
        swapToDrainingNotification(merged = initialMerged, produced = initialProduced)

        drainJob = scope.launch {
            try {
                // Closing the queue here is the trigger that lets the worker's
                // consumeAsFlow() complete after draining. Buffered chunks remain readable;
                // close() only prevents new offers (and the producer is already stopped).
                activePipeline.chunkQueue.close()

                // Track drain progress in the notification + statusFlow. The aggregator's
                // mergedCountFlow advances every time a chunk merges; we re-issue the
                // notification with the latest counts. We launch this as a child of the
                // drain job so it cancels when the drain finishes.
                val notifJob = launch {
                    activePipeline.aggregator.mergedCountFlow.collect { merged ->
                        val produced = activePipeline.captureEngine.producedChunkCountFlow.value
                        _statusFlow.value = _statusFlow.value.copy(
                            drainProgress = DrainProgress(
                                sessionId = sessionId,
                                merged = merged,
                                produced = produced,
                                completed = false,
                            ),
                        )
                        swapToDrainingNotification(merged = merged, produced = produced)
                    }
                }

                // Wait for the worker to drain the queue naturally. NOT a cancel — we want
                // every queued chunk transcribed before we tear down native resources.
                activePipeline.worker.awaitDrainCompletion()

                // Drain complete. Stop tracking + close native resources before finalize
                // so the WAV concat and Room writes don't compete with whisper.cpp for
                // CPU.
                notifJob.cancel()
                try {
                    activePipeline.transcriptionEngine.close()
                } catch (t: Throwable) {
                    Log.w(TAG, "transcriptionEngine.close threw: ${t.message}", t)
                }

                val finalRawText = activePipeline.aggregator.currentText()
                val finalMerged = activePipeline.aggregator.mergedCountFlow.value
                val finalProduced = activePipeline.captureEngine.producedChunkCountFlow.value
                try {
                    finalizeSessionRow(sessionId, recordingStartedAt, finalRawText, finalMerged)
                } catch (t: Throwable) {
                    Log.w(TAG, "finalizeSessionRow threw: ${t.message}", t)
                }

                _statusFlow.value = Status(
                    isRecording = false,
                    sessionId = null,
                    startedAtMs = 0L,
                    pipeline = null,
                    engineInitError = null,
                    drainProgress = DrainProgress(
                        sessionId = sessionId,
                        merged = finalMerged,
                        produced = finalProduced,
                        completed = true,
                    ),
                )
                postCompletionNotification(
                    sessionId = sessionId,
                    rawText = finalRawText,
                    mergedCount = finalMerged,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "drainJob threw: ${t.message}", t)
            } finally {
                drainJob = null
                // Final clean state — drainProgress cleared, FGS gone. The completion
                // notification posted above lives on its own ID so it survives this
                // teardown.
                _statusFlow.value = Status(false, null, 0L, null, null, null)
                withContext(Dispatchers.Main) { tearDownForeground() }
            }
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
     * Test seam for the startForeground-before-engine-init ordering contract. Production
     * sets a no-op recorder; unit tests inject a recorder that captures the order so a JVM
     * test can assert startForeground happened before engineInit without booting Android.
     */
    @VisibleForTesting
    internal var startupRecorder: StartupOrderRecorder = StartupOrderRecorder.NoOp

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

    /**
     * 2026-05-09: pluggable factory for the recording-time-cap watcher. Production builds the
     * default impl wired to Vibrator + ToneGenerator + this.stopRecording(). Tests inject a
     * fake watcher with a controllable clock + a [RecordingCapWatcher.Sink] that records calls.
     */
    @VisibleForTesting
    internal var capWatcherFactory: (RecordingService) -> RecordingCapWatcher = { svc ->
        RecordingCapWatcher(
            sink = svc.defaultCapSink(),
            clock = System::currentTimeMillis,
        )
    }

    /**
     * Production [RecordingCapWatcher.Sink]. Owns the Vibrator + ToneGenerator handles +
     * the auto-stop hook. Kept private so the only public extension point is
     * [capWatcherFactory] above.
     */
    private fun defaultCapSink(): RecordingCapWatcher.Sink = object : RecordingCapWatcher.Sink {
        // ToneGenerator allocates a small native AudioTrack; instantiate lazily on first
        // beep so we don't pay the cost on sub-44:50 sessions (the common case). Released
        // when the watcher is cancelled or onHardCapReached fires — see release() inside.
        private var tone: ToneGenerator? = null

        override fun vibrateOnce() {
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val mgr = getSystemService(VibratorManager::class.java)
                    mgr?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Vibrator::class.java)
                }
                if (vibrator?.hasVibrator() != true) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(
                            RecordingCaps.VISUAL_WARN_VIBRATE_MS,
                            VibrationEffect.DEFAULT_AMPLITUDE,
                        ),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(RecordingCaps.VISUAL_WARN_VIBRATE_MS)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "vibrateOnce threw: ${t.message}", t)
            }
        }

        override fun beepOnce() {
            try {
                val gen = tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, BEEP_VOLUME)
                    .also { tone = it }
                gen.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS)
            } catch (t: Throwable) {
                Log.w(TAG, "beepOnce threw: ${t.message}", t)
            }
        }

        override fun onHardCapReached() {
            Log.i(TAG, "Recording hit HARD_CAP_MS=${RecordingCaps.HARD_CAP_MS} — auto-stopping.")
            // Same code path as a user STOP tap. stopRecording() handles the rest of the
            // teardown including capWatcherJob cancellation (which triggers release() on
            // this sink via the watcher's finally{}).
            stopRecording()
        }

        override fun release() {
            try { tone?.release() } catch (_: Throwable) {}
            tone = null
        }
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
     *
     * Bug-3 fix (2026-05-10): the [mergedChunkCount] arg matches the produced [chunkCount]
     * after a successful drain. They differ only in failure cases (a chunk transcribed-as-
     * marker still counts as merged; a chunk lost to DROP_OLDEST in ChunkQueue surfaces a
     * MarkerLost which the worker also merges). Persisting both lets the History UI
     * distinguish "fully transcribed" from "partial recovery".
     */
    private suspend fun finalizeSessionRow(
        sessionId: String,
        startedAtMs: Long,
        finalRawText: String,
        mergedChunkCount: Int,
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
                mergedChunkCount = mergedChunkCount,
                notes = null, // preserve existing notes (e.g. "permission_denied")
            )
        } catch (t: Throwable) {
            Log.w(TAG, "BidetSession finalize update failed: ${t.message}", t)
        }
    }

    // ---------- foreground notification ----------

    private fun startForegroundWithStartingPlaceholder() {
        val notification = buildStartingNotification()
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

    private fun swapToRecordingNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFICATION_ID, buildRecordingNotification())
        } catch (t: Throwable) {
            Log.w(TAG, "swapToRecordingNotification threw: ${t.message}", t)
        }
    }

    /**
     * Bug-2 fix (2026-05-10): swap the live-recording notification for a "Transcribing N of
     * M chunks remaining…" notification. Re-issued every time the merged count advances.
     * Uses the same NOTIFICATION_ID so the FGS stays bound to one notification slot
     * (Android won't promote a new ID without re-calling startForeground).
     *
     * The notification has NO Stop action — by this point the user already tapped Stop;
     * presenting a second Stop would be confusing. Tapping the notification still opens
     * the app (back to the History list, since isRecording is false).
     */
    private fun swapToDrainingNotification(merged: Int, produced: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFICATION_ID, buildDrainingNotification(merged = merged, produced = produced))
        } catch (t: Throwable) {
            Log.w(TAG, "swapToDrainingNotification threw: ${t.message}", t)
        }
    }

    @VisibleForTesting
    internal fun buildDrainingNotification(merged: Int, produced: Int): android.app.Notification {
        // Clamp the displayed numbers — `produced` is the denominator and we promise N ≤ M
        // even if the flows raced. A produced=0 case shouldn't happen on this path (we
        // only enter drain when at least one chunk was offered), but guard anyway.
        val safeProduced = produced.coerceAtLeast(merged.coerceAtLeast(1))
        val text = getString(
            R.string.bidet_recording_draining_text_format,
            merged,
            safeProduced,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.bidet_recording_draining_title))
            .setContentText(text)
            .setProgress(safeProduced, merged, false)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openMainActivityPendingIntent())
            .build()
    }

    /**
     * Bug-2 fix (2026-05-10): once the drain finishes + finalize runs, post a
     * dismissable completion notification on a NEW id so the user gets a clear "done"
     * signal even after the FGS has stopped. Word count gives them a rough sense of the
     * length without forcing them into the app.
     */
    private fun postCompletionNotification(sessionId: String, rawText: String, mergedCount: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            // Approximate word count: a brain dump's whitespace tokenisation is good
            // enough; we don't need linguistic accuracy.
            val wordCount = rawText.trim().split(Regex("\\s+")).count { it.isNotBlank() }
            val text = getString(
                R.string.bidet_recording_complete_text_format,
                wordCount,
                mergedCount,
            )
            val notif = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.bidet_recording_complete_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setAutoCancel(true)
                .setContentIntent(openMainActivityPendingIntent())
                .build()
            nm.notify(COMPLETION_NOTIFICATION_ID, notif)
        } catch (t: Throwable) {
            Log.w(TAG, "postCompletionNotification threw: ${t.message}", t)
        }
    }

    @VisibleForTesting
    internal fun buildStartingNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.bidet_recording_starting_title))
        .setContentText(getString(R.string.bidet_recording_starting_text))
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setOngoing(true)
        .setContentIntent(openMainActivityPendingIntent())
        .build()

    @VisibleForTesting
    internal fun buildRecordingNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.bidet_recording_notification_title))
        .setContentText(getString(R.string.bidet_recording_notification_text))
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setOngoing(true)
        .setContentIntent(openMainActivityPendingIntent())
        .addAction(
            NotificationCompat.Action.Builder(
                /* icon */ 0,
                getString(R.string.bidet_recording_stop_action),
                stopRecordingPendingIntent(),
            ).build()
        )
        .build()

    private fun stopRecordingPendingIntent(): PendingIntent {
        val stopIntent = Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun openMainActivityPendingIntent(): PendingIntent {
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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
        /**
         * Bug-2 fix (2026-05-10): completion notification posts on a separate id so the
         * dismissable "Brain dump ready" survives the FGS teardown. Distinct from
         * NOTIFICATION_ID, which is owned by startForeground/stopForeground.
         */
        const val COMPLETION_NOTIFICATION_ID = 1102
        const val ACTION_START = "ai.bidet.phone.action.START_RECORDING"
        const val ACTION_STOP = "ai.bidet.phone.action.STOP_RECORDING"

        /**
         * F2.1 (2026-05-09): persisted on the [BidetSession.notes] column when a recording
         * attempt aborts because the transcription engine could not initialize. Surfaced
         * by SessionsListScreen as "engine init failed" instead of an empty in-progress
         * row that never finalizes.
         */
        const val ENGINE_INIT_FAILED_NOTE: String = "engine_init_failed"

        /**
         * ToneGenerator volume + duration for the audible-warn beep. 80/100 was loud enough
         * on the Pixel 8 Pro speaker without being alarming; 200 ms is short enough to leave
         * audible silence between the 1 s cadence ticks.
         */
        private const val BEEP_VOLUME: Int = 80
        private const val BEEP_DURATION_MS: Int = 200

        fun startIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
    }
}
