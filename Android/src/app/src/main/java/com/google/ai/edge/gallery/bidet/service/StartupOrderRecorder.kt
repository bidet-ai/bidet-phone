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

/**
 * Pure-Kotlin test seam for the startForeground-before-engine-init ordering contract added
 * 2026-05-09 after the gemma-flavor RecordingService crash:
 *
 *   ActivityManager: Killing <pid>:ai.bidet.phone.gemma (adj 200):
 *     scheduleCrash for 'Context.startForegroundService() did not then call
 *     Service.startForeground(): ServiceRecord{...}'
 *
 * Android 12+ kills the process if startForeground is not called within 5 seconds of
 * startForegroundService. The whisper-flavor was within budget; the gemma flavor's
 * GemmaAudioEngine.initialize() pushed past the deadline because the LiteRT-LM Engine
 * factory call is slower. Fix: foreground promotion runs BEFORE any engine init.
 *
 * Exists as a separate type so a JVM unit test can assert ordering without booting an
 * Android Service. Production wires the [NoOp] impl; tests inject a recorder that captures
 * the order of recordStartForeground() vs recordEngineInit() calls.
 */
interface StartupOrderRecorder {
    fun recordStartForeground()
    fun recordEngineInit()

    object NoOp : StartupOrderRecorder {
        override fun recordStartForeground() = Unit
        override fun recordEngineInit() = Unit
    }
}
