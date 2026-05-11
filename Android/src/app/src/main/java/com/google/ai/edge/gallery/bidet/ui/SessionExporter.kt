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

package com.google.ai.edge.gallery.bidet.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.google.ai.edge.gallery.bidet.audio.WavConcatenator
import com.google.ai.edge.gallery.bidet.data.BidetSession
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Session-export (v19, 2026-05-11) — read-only "one tap, share everything" path.
 *
 * Problem: a finished brain dump produces three useful artifacts (RAW transcript,
 * Clean-for-me output, Clean-for-others output) plus the source audio, all of which
 * live under `${getExternalFilesDir(null)}/sessions/<sid>/`. That directory is in
 * scoped storage and unreachable from `adb pull` even with `run-as` on production
 * builds. Mark's morning workflow is "talk for 10-20 minutes, then forward the
 * cleaned brief to Captain's-log / Drive / Gmail"; without an export path the only
 * way out is screenshot copy-paste.
 *
 * Solution: build a Markdown summary in-memory, write it to a session-scoped file
 * the FileProvider already exposes ([file_paths.xml] declares `sessions/` as
 * `external-files-path`), concat the per-chunk PCM into one WAV using the
 * existing [WavConcatenator] (idempotent — past sessions where the WAV already
 * exists skip the re-concat), and fire ACTION_SEND_MULTIPLE so the standard share
 * sheet can route to Drive, Gmail, ntfy, etc.
 *
 * Pure helper [toMarkdownSummary] is split out for JVM unit-testing; see
 * `SessionExporterMarkdownTest`. The IO side ([exportSession]) is not unit-tested
 * here because FileProvider + ContentResolver require an instrumented test
 * harness which the project hasn't wired up yet.
 *
 * v0.3 build target: Tensor G3 CPU. Nothing here touches GPU or media muxers —
 * the WAV concat is a hand-rolled header + raw PCM append from [WavConcatenator],
 * per the v19 brief.
 */

private const val TAG = "BidetSessionExport"

/** Subdir under `sessions/<sid>/` where the .md export is written. */
private const val EXPORT_DIR_NAME = "export"

/** Filename pattern for the exported markdown summary. Stable so re-exports overwrite. */
private const val EXPORT_MD_FILENAME = "session.md"

private val EXPORT_TS_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.US)

/**
 * Build a Markdown summary of the session.
 *
 * Sections (in order, always emitted; missing fields render as "_(empty)_" so
 * the structure is stable for downstream parsing):
 *  1. **Session metadata** — id, started, ended (or "in progress"), duration,
 *     chunk count
 *  2. **RAW transcript** — the merged sherpa-onnx output, fenced as a code block
 *     for plain-text fidelity in Drive/Gmail render
 *  3. **Clean for me** — RECEPTIVE axis output (`cleanCached`)
 *  4. **Clean for others** — EXPRESSIVE axis output (`foraiCached`)
 *
 * Pure: no Android dependencies, no filesystem access, no `System.currentTimeMillis()`.
 * That's the contract the unit test enforces — given the same `BidetSession` you
 * always get the same bytes out.
 *
 * Note on timezone: we render timestamps in the system default zone at the time of
 * export. That's a deliberate caller-side choice — the function reads `ZoneId`
 * lazily so a test can force UTC by `-Duser.timezone=UTC` if needed. We don't
 * accept a `ZoneId` parameter because every call site in the app should use the
 * same convention and dragging a ZoneId through the call chain adds noise.
 */
fun BidetSession.toMarkdownSummary(): String {
    val zone = ZoneId.systemDefault()
    val startedStr = EXPORT_TS_FORMATTER.format(Instant.ofEpochMilli(startedAtMs).atZone(zone))
    val endedStr = endedAtMs?.let {
        EXPORT_TS_FORMATTER.format(Instant.ofEpochMilli(it).atZone(zone))
    } ?: "_(in progress)_"
    val durationStr = if (durationSeconds > 0) {
        val mins = durationSeconds / 60
        val secs = durationSeconds % 60
        if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
    } else "_(unknown)_"

    val sb = StringBuilder(rawText.length + 512)
    sb.append("# Bidet brain dump\n\n")
    sb.append("## Session metadata\n\n")
    sb.append("- **Session id:** `").append(sessionId).append("`\n")
    sb.append("- **Started:** ").append(startedStr).append('\n')
    sb.append("- **Ended:** ").append(endedStr).append('\n')
    sb.append("- **Duration:** ").append(durationStr).append('\n')
    sb.append("- **Chunks produced:** ").append(chunkCount).append('\n')
    sb.append("- **Chunks merged:** ").append(mergedChunkCount).append('\n')
    sb.append('\n')

    sb.append("## RAW transcript\n\n")
    if (rawText.isBlank()) {
        sb.append("_(empty)_\n\n")
    } else {
        // Fenced as plain text — Drive/Gmail preserve newlines, and a downstream
        // parser can grab the block between ```text … ``` markers verbatim.
        sb.append("```text\n").append(rawText.trim()).append("\n```\n\n")
    }

    sb.append("## Clean for me\n\n")
    appendCleanedSection(sb, cleanCached ?: analysisCached)

    sb.append("## Clean for others\n\n")
    appendCleanedSection(sb, foraiCached)

    return sb.toString()
}

private fun appendCleanedSection(sb: StringBuilder, text: String?) {
    if (text.isNullOrBlank()) {
        sb.append("_(not generated)_\n\n")
    } else {
        sb.append(text.trim()).append("\n\n")
    }
}

/**
 * Result of running an export — surfaced so the caller can show a Toast/snackbar on
 * the rare failure modes (FileProvider misconfig, no sessions/ subtree yet, etc.).
 */
internal sealed class ExportResult {
    object Started : ExportResult()
    data class Failed(val message: String) : ExportResult()
}

/**
 * One-tap export. Behavior:
 *
 *  1. Build the Markdown summary via [toMarkdownSummary].
 *  2. Write it to `${externalFilesDir}/sessions/<sid>/export/session.md`.
 *  3. If the session has any audio chunks, run [WavConcatenator.concatenateChunksToWav]
 *     — idempotent, so past sessions that already produced `audio.wav` just return
 *     the existing file in O(1).
 *  4. Fire an ACTION_SEND_MULTIPLE chooser with:
 *     - EXTRA_TEXT = the Markdown body (for plain-text targets like Gmail's compose
 *       window, ntfy, Slack DM — anywhere that ignores attachment URIs)
 *     - EXTRA_STREAM = ArrayList<Uri> of [md_uri] or [md_uri, wav_uri]
 *     - type = "audio/wav" when audio is attached, otherwise "text/markdown"
 *
 * Errors are logged + swallowed. The caller passes a `onError` lambda if it wants
 * a Toast; the live SessionDetailScreen does pop a Toast on failure so the user
 * doesn't see a silent no-op.
 *
 * Live + past sessions both work: nothing here reads recording state. The only
 * "live" hazard is that `audioWavPath` may still be null while recording — in that
 * case [WavConcatenator.concatenateChunksToWav] reads what's been flushed so far
 * and produces a partial WAV. That's acceptable for the v19 export contract
 * ("share what you've got"); we surface the in-progress marker via the metadata
 * section so the recipient knows.
 */
internal fun exportSession(
    context: Context,
    session: BidetSession,
    onError: ((String) -> Unit)? = null,
): ExportResult {
    return try {
        val externalRoot = context.getExternalFilesDir(null)
            ?: return failed(onError, "External files dir unavailable")
        val sessionDir = File(externalRoot, "sessions/${session.sessionId}")
        if (!sessionDir.exists()) sessionDir.mkdirs()

        // 1 + 2: write the markdown summary into the session's own subtree so the
        // FileProvider (`sessions/` path config) can grant transient read access.
        val exportDir = File(sessionDir, EXPORT_DIR_NAME)
        if (!exportDir.exists()) exportDir.mkdirs()
        val mdFile = File(exportDir, EXPORT_MD_FILENAME)
        mdFile.writeText(session.toMarkdownSummary())

        // 3: concat audio if there are any chunks. The concatenator is idempotent
        // so this is cheap on re-export of a past session. A null return means
        // either no chunks on disk OR the recording busted the 32-bit WAV-size
        // ceiling — in either case we just skip the audio attachment.
        val wavFile: File? = try {
            if (session.chunkCount > 0) {
                WavConcatenator.concatenateChunksToWav(context, session.sessionId)
            } else null
        } catch (t: Throwable) {
            Log.w(TAG, "WAV concat failed during export: ${t.message}", t)
            null
        }

        val authority = "${context.packageName}.provider"
        val mdUri: Uri = FileProvider.getUriForFile(context, authority, mdFile)
        val wavUri: Uri? = wavFile?.let { FileProvider.getUriForFile(context, authority, it) }

        val streams = ArrayList<Uri>().apply {
            add(mdUri)
            if (wavUri != null) add(wavUri)
        }

        // ACTION_SEND_MULTIPLE is the right verb whenever EXTRA_STREAM is a list.
        // We attach EXTRA_TEXT in parallel so text-only receivers (compose windows,
        // ntfy publish endpoints, Slack DM) still get the summary body even if they
        // ignore the file URIs. The mime type is chosen by the heavier attachment:
        // audio/wav when audio present, text/markdown otherwise. Some receivers
        // filter strictly on mime, but in practice Gmail/Drive/Files all accept
        // "audio/wav" with a non-audio companion attachment.
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (wavUri != null) "audio/wav" else "text/markdown"
            putExtra(Intent.EXTRA_SUBJECT, "Bidet brain dump · ${formatStartedAt(session.startedAtMs)}")
            putExtra(Intent.EXTRA_TEXT, session.toMarkdownSummary())
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Export session")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        ExportResult.Started
    } catch (t: Throwable) {
        Log.w(TAG, "exportSession failed: ${t.message}", t)
        failed(onError, t.message ?: "Export failed")
    }
}

private fun failed(onError: ((String) -> Unit)?, msg: String): ExportResult {
    onError?.invoke(msg)
    return ExportResult.Failed(msg)
}
