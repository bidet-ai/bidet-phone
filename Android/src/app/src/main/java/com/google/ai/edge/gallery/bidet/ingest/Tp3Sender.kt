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

package com.google.ai.edge.gallery.bidet.ingest

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Optional debug-only TP3 webhook sender. Brief §11 / §13.
 *
 * Posts the current four-tab state to a user-configured URL with body:
 * ```
 * {
 *   "session_id": "...",
 *   "raw": "...",
 *   "clean": "...",
 *   "analysis": "...",
 *   "forai": "...",
 *   "model": "gemma-4-E4B-it",
 *   "duration_seconds": 123,
 *   "timestamp": 1714939200
 * }
 * ```
 * with header `X-AG-KEY: <user-configured>`.
 *
 * Returns a short status string for the UI ("ok 200", "fail 401: ...", or "error: ..."). The
 * UI surfaces it via toast or status line.
 */
class Tp3Sender(private val context: Context) {

    /**
     * Synchronous (blocking) POST. Caller must invoke from a coroutine on a non-main
     * dispatcher. Returns a short result string, never throws.
     */
    fun send(
        url: String,
        agKey: String,
        sessionId: String,
        raw: String,
        clean: String,
        analysis: String,
        forai: String,
        durationSeconds: Long,
        modelName: String = "gemma-4-E4B-it",
    ): String {
        if (url.isBlank()) return "error: blank url"
        return try {
            val body = JSONObject().apply {
                put("session_id", sessionId)
                put("raw", raw)
                put("clean", clean)
                put("analysis", analysis)
                put("forai", forai)
                put("model", modelName)
                put("duration_seconds", durationSeconds)
                put("timestamp", System.currentTimeMillis() / 1000L)
            }.toString()

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val req = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .also { if (agKey.isNotBlank()) it.header("X-AG-KEY", agKey) }
                .post(body.toRequestBody(JSON))
                .build()

            client.newCall(req).execute().use { resp ->
                val code = resp.code
                when {
                    resp.isSuccessful -> "ok $code"
                    code == 401 -> "fail 401: unauthorized (check X-AG-KEY)"
                    code in 400..499 -> "fail $code: ${resp.message}"
                    code in 500..599 -> "server error $code: ${resp.message}"
                    else -> "unexpected $code"
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "send failed: ${t.message}", t)
            "error: ${t.message}"
        }
    }

    companion object {
        private const val TAG = "BidetTp3Sender"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
