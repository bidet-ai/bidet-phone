
# Cursor brief — bidet-phone v0.1 (v4 LOCKED)

> **NOTE for Cursor:** This brief references private memory files (`feedback_*.md`, etc.) for context. The rules they encode are restated inline below — you do not need to fetch them. The four-tab prompts in `Android/src/app/src/main/assets/prompts/` are LOCKED v1, validated on local Gemma 4 E4B against 5 holdout transcripts. Treat this brief as authoritative.


**Repo:** https://github.com/bidet-ai/bidet-phone (created 2026-05-07, Apache 2.0)
**Upstream:** https://github.com/google-ai-edge/gallery (Apache 2.0)
**Contest:** Google Gemma 2026 Challenge — submission deadline **2026-05-24 23:59 PDT**
**Submission portal:** https://dev.to/challenges/google-gemma-2026-05-06

## TL;DR
Fork google-ai-edge/gallery into bidet-ai/bidet-phone. Replace its single-clip 30-second Audio Scribe with a **chunked-pipelined-rolling capture pipeline driven by a foreground service**. Replace the chat-tab UI with a **four-tab brain-dump UX (RAW / CLEAN / ANALYSIS / FORAI)** that turns the user's verbal thinking-aloud into AI-ready input. **Transcription** runs on a small bundled Whisper variant (Whisper-tiny via whisper.cpp or Whisper-tflite). **Cleanup, organization, and structured output** run on **Gemma 4 E4B locally via LiteRT-LM**. Both inference paths on-device, no cloud fallback, no telemetry.

## Why Whisper for ASR, Gemma 4 for everything else
Empirical: we tested Gemma 4 E4B audio path on 3 of Mark's real OMI wavs via Apex Ollama 2026-05-07 — the model returned "Please provide the speech segment" on all three (audio bytes didn't pass through). Independent benchmarks (twango.dev, MindStudio): E4B WER 41% on noisy real-world meeting audio vs Whisper-large-v3 16%; sub-1-second clips hallucinate at 220% WER. Brain-dumps happen in noisy environments (kitchens, walking, ambient). Gemma 4's LM-prior fills in unclear acoustic signal with hallucinated content — not what we want for verbatim transcript.

Decision: ASR is a commodity layer; we use Whisper for it. Gemma 4 E4B does what it's strongest at — restructuring transcript into clean / analyzed / AI-ready forms. This use of Gemma 4 IS deliberate and considered — applied where it's strongest, not naively pipelined through everything.

## Design center — BRAIN-DUMP → AI INPUT
Bidet-phone is a **brain-dump cleaner**: a tool for single-speaker verbal thinking-aloud (typically 5-15 min) that produces clean, structured text the user can paste into Claude / ChatGPT / Gemini.

**Tab roles** (each serves a different audience):
- **FORAI (anchor)** — structured markdown for AI consumption. The primary value. Demo + marketing lead with this.
- **CLEAN** — clean transcript readable by humans AND pasteable into AI as a less-structured alternative.
- **ANALYSIS** — for the SPEAKER themselves. ADHD-friendly tangent organizer — extracts threads from chaos-brain, surfaces action items + open questions. Mark's words: *"To make logical sense of my ADD brain-dump, tangent-driven, chaos mind."*
- **RAW** — verification only. Just the auto-transcribed text.

The chunked-rolling-pipeline architecture also handles long-form single-speaker recordings (hour-long sessions, lectures, podcasts) as a capability that emerged for free. **Mention this as ONE LINE in the description**, NOT in the headline, README intro, or demo voiceover. Per Mark: *"Don't even throw in the classroom stuff. We can let it somewhere in the description know that it can go for an hour."* (See `project_bidet_phone_design_assumption_2026-05-07.md`.)

### Single-speaker, NOT meetings
Single voice in front of a mic. NOT multi-speaker meetings, interviews, conversations. No diarization, no turn-taking, no per-speaker UI.

## Why this is "significant work beyond base" (contest compliance)
Upstream Audio Scribe is locked to a single 30-second clip (`MAX_AUDIO_CLIP_DURATION_SEC = 30` in `Consts.kt`) AND uses Gemma 4 multimodal directly for audio. We extend it into:
1. **Foreground service** drives a continuous `AudioRecord` capture (NEW, extracted from `AudioRecorderPanel`)
2. **30-second sliding window** with 2-second chunk overlap to prevent mid-word splits
3. **Pipelined transcription** — chunk N transcribed by bundled Whisper while chunk N+1 records
4. **Transcript aggregator** with deterministic fuzzy de-dup at chunk boundaries
5. **On-demand tab generation** — CLEAN/ANALYSIS/FORAI run only when user taps the tab, on accumulated RAW, via Gemma 4 E4B
6. **Four-tab single-speaker UX** replacing upstream chat-tab UI
7. **Settings screen** with debug-only prompt-template editor
8. **Two-engine architecture** — Whisper for ASR, Gemma 4 for restructuring. Deliberate split based on benchmark evidence; documented in README.

Net: indefinite-length recording (vs. upstream 30-sec cap), reliable transcription on noisy real-world audio (which Gemma 4 multimodal struggles with), tab outputs in 4-8s each. Upstream cannot do this.

## ⚠️ Pre-fire verifications (DO BEFORE starting work)
1. **(Resolved 2026-05-07)** ~~Verify Gemma 4 E4B audio-input path actually transcribes verbatim~~ — empirical test on Apex Ollama gemma4:e4b on 3 OMI wavs returned "Please provide the speech segment" on all three. Combined with twango.dev/MindStudio benchmarks (E4B 41% WER on noisy AMI vs Whisper 16%), we use **Whisper-tiny via whisper.cpp Android** for ASR. Decision documented above.
2. **(Resolved 2026-05-07)** ~~Verify `litert-community/gemma-4-E4B-it-litert-lm` ungated~~ — `curl -I` returned 302 to CDN, no auth, file size 3.66 GB confirmed.
3. **Pin upstream commit SHA** at start of work. Record in `UPSTREAM_GALLERY_SHA.md` at repo root.
4. **Pin LiteRT-LM ≥0.10.1.** Pixel 8 Pro on 0.10.0 has a documented GPU decode crash (`Failed to clEnqueueNDRangeKernel - Invalid command queue`); fixed in 0.10.1. See [LiteRT-LM#1850](https://github.com/google-ai-edge/LiteRT-LM/issues/1850).
5. **Pick the Whisper Android library before starting.** Two options to evaluate:
   - **whisper.cpp Android JNI** (https://github.com/ggerganov/whisper.cpp/tree/master/examples/whisper.android) — official C++ port, ~75 MB tiny.en model, well-supported.
   - **Whisper-tflite** (community Android ports) — TFLite native, may be faster on Tensor TPU.
   Cursor: clone whisper.cpp, build the Android sample, confirm it runs on Pixel 8 Pro before integrating. Document choice in `UPSTREAM_WHISPER.md`.

## Architecture

```
┌──── Pixel 8 Pro (LLM inference is local; INTERNET used only for model download + opt-in webhook) ────┐
│                                                                                                       │
│  Foreground Service (RecordingService.kt — NEW)                                                       │
│      ├─ Notification: ongoing, non-dismissible (setOngoing(true))                                     │
│      ├─ foregroundServiceType="microphone" (Android 14+ required)                                     │
│      ├─ AudioFocus listener: pause-resume on phone calls / other mic apps                             │
│      ▼                                                                                                │
│  AudioCaptureEngine (NEW — extracted from upstream AudioRecorderPanel.kt)                             │
│      ├─ Single continuous android.media.AudioRecord, 16kHz mono PCM 16-bit                            │
│      ├─ Buffer size = max(getMinBufferSize() × 4, 32000) bytes                                        │
│      ├─ Slices into 30-sec windows with 2-sec rolling backbuffer                                      │
│      ├─ Persists each chunk to internal storage (atomic write) for crash recovery                     │
│      ▼                                                                                                │
│  ChunkQueue (NEW) — bounded Channel<Chunk>(capacity=4, BufferOverflow.DROP_OLDEST + MarkerLost emit)  │
│      ├─ Backpressure surfaced to UI ("Transcription falling behind by Nx")                            │
│      ▼                                                                                                │
│  TranscriptionWorker (NEW) — single-threaded consumer of ChunkQueue                                   │
│      ├─ For each chunk: WhisperEngine.transcribe(audioBytes) — bundled Whisper-tiny via JNI           │
│      ├─ Whisper model bundled as APK asset (~75 MB) — no first-run download for ASR                   │
│      ├─ On chunk failure: log + insert "[chunk N transcription failed]" + continue                    │
│      ▼                                                                                                │
│  TranscriptAggregator (NEW) — fuzzy-dedup overlap, assembles full RAW                                 │
│      ▼                                                                                                │
│  BidetTabsScreen (NEW, replaces upstream chat-tab UI)                                                 │
│      ├─ HorizontalPager + TabRow                                                                      │
│      ├─ RAW tab: live, collectAsStateWithLifecycle on Aggregator's StateFlow                          │
│      ├─ CLEAN tab: on-tap → Gemma 4 E4B with prompts/clean.txt against current RAW                    │
│      ├─ ANALYSIS tab: on-tap → Gemma 4 E4B with prompts/analysis.txt                                  │
│      ├─ FORAI tab: on-tap → Gemma 4 E4B with prompts/forai.txt (anchor)                               │
│      ├─ Tab cache: hash(rawSha + promptVersion + temperature) → cached result, persist via DataStore  │
│      ├─ Manual "Generate" button — NEVER auto-trigger on tab swipe                                    │
│      │                                                                                                │
│  BidetSettingsScreen (NEW, debug-only)                                                                │
│      ├─ Prompt-template editor (writes to DataStore, not bundled assets)                              │
│      ├─ TP3 webhook URL + X-AG-KEY (DataStore)                                                        │
│      └─ "Send to TP3" button (debug-only)                                                             │
└───────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

## File layout

### Files to keep from upstream (unchanged or with modification headers)
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt` — Gemma 4 LiteRT-LM loader (modified — add `cacheDir = context.getExternalFilesDir(null)?.absolutePath` unconditionally to avoid recompile-on-every-launch)
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatTaskModule.kt` — keep `LlmChatTask` for our Gemma 4 text-prompt calls. `LlmAskAudioTask` is **NOT used** in v0.1 (transcription goes through Whisper, not Gemma audio). Don't delete it — leave for v0.2 reuse if Gemma 4 audio path matures.
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/worker/DownloadWorker.kt` — already supports HTTP-Range resume; reuse as-is
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Consts.kt` — keep but DON'T inherit `MAX_AUDIO_CLIP_DURATION_SEC` cap on the service path
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/AudioRecorderPanel.kt` — kept ONLY as a thin Compose UI wrapper. Recording logic moves to AudioCaptureEngine.
- `model_allowlists/` — keep, ensures Gemma 4 E4B is selectable
- `.github/workflows/build_android.yaml` — adapt to attach signed release APK to GitHub Releases on tag push
- `LICENSE` (Apache 2.0) — preserve unchanged

### Files to remove
- All non-LLM-chat tabs: image generation, classification, samples
- `ProjectConfig.kt` HF OAuth client config — STRIP for v0.1 (Gemma 4 E4B is public/ungated, no auth needed)
- `appAuthRedirectScheme` from `app/build.gradle.kts` manifestPlaceholder
- `openid-appauth` dependency from `libs.versions.toml`
- Promotional / demo strings in `res/values/strings.xml` that mention non-bidet features
- Sample / demo data folders

### Files to ADD

```
Android/src/app/src/main/java/com/google/ai/edge/gallery/bidet/
├── service/
│   └── RecordingService.kt                    -- foreground service, type=microphone
├── audio/
│   └── AudioCaptureEngine.kt                  -- NEW. Continuous AudioRecord, 30s windows, 2s overlap
├── chunk/
│   └── ChunkQueue.kt                          -- bounded Channel<Chunk>, backpressure
├── transcription/
│   ├── TranscriptionWorker.kt                 -- consumes ChunkQueue, calls WhisperEngine
│   └── WhisperEngine.kt                       -- JNI wrapper around bundled whisper.cpp (or TFLite)
├── transcript/
│   ├── TranscriptAggregator.kt                -- fuzzy-dedup, StateFlow<String>
│   └── DedupAlgorithm.kt                      -- the one defined dedup function (see spec below)
├── ui/
│   ├── BidetTabsScreen.kt                     -- HorizontalPager + TabRow
│   ├── RawTabContent.kt                       -- collectAsStateWithLifecycle
│   ├── CleanTabContent.kt                     -- on-tap, with cache
│   ├── AnalysisTabContent.kt                  -- on-tap, with cache
│   ├── ForaiTabContent.kt                     -- on-tap, with cache
│   └── BidetSettingsScreen.kt                 -- debug-only prompt editor + TP3 sender
├── ingest/
│   └── Tp3Sender.kt                           -- POST to user-configured webhook URL
└── consent/
    └── GemmaTermsConsentScreen.kt             -- shown ONCE before model download

Android/src/app/src/main/assets/prompts/
├── clean.txt
├── analysis.txt
└── forai.txt

Android/src/app/src/main/assets/whisper/
└── ggml-tiny.en.bin                           -- bundled ~75 MB Whisper-tiny English model
                                                  (or equivalent TFLite file if that path picked)

Android/src/app/src/test/java/com/google/ai/edge/gallery/bidet/transcript/
├── DedupAlgorithmTest.kt                      -- unit tests with fixtures
└── TranscriptAggregatorTest.kt                -- ASR-variance simulated fixtures

tests/dedup_fixtures/                          -- 5 paired fixtures (chunk_a.txt, chunk_b.txt, expected.txt)
```

(prompts/*.txt locked-in at end of prompt iteration phase — TBD)

## Specific implementation specs

### 1. Foreground service (Android 14+ compliance)
- `AndroidManifest.xml` adds:
  - `<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />`
  - `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />`
  - `<uses-permission android:name="android.permission.RECORD_AUDIO" />`
  - `<service android:name=".bidet.service.RecordingService" android:foregroundServiceType="microphone" android:exported="false" />`
- `RecordingService.startForeground(id, notification, FOREGROUND_SERVICE_TYPE_MICROPHONE)` — 3-arg overload, required on API 30+, enforced on 34+
- Request `RECORD_AUDIO` runtime permission **before** `startForegroundService` (Android 15 denies mic from background-launched FGS without runtime grant)
- Notification: `setOngoing(true)`, `setStyle(NotificationCompat.MediaStyle())`, includes "Stop" action
- AudioFocus listener: pause on phone call (`AUDIOFOCUS_LOSS_TRANSIENT`), resume on `AUDIOFOCUS_GAIN`

### 2. AudioCaptureEngine
- Single continuous `AudioRecord(MIC, 16000, MONO, PCM_16BIT, bufferSize)`
- Buffer size: `max(AudioRecord.getMinBufferSize(16000, MONO, PCM_16BIT) * 4, 32_000)` bytes
- Reads in a tight loop on a dedicated thread, writes raw PCM bytes to a circular ring buffer sized for 32 sec (covers 30 sec window + 2 sec overlap)
- Every 30 seconds (minus 2-sec overlap): emit a `Chunk(startMs, endMs, bytes)` to ChunkQueue
- Per-chunk PCM bytes also written to `${getExternalFilesDir(null)}/sessions/<session_id>/chunks/<idx>.pcm` (atomic via tmp+rename) for crash recovery
- Format: raw PCM bytes (NOT WAV-with-header) — Whisper.cpp's transcribe API accepts this directly via `whisper_pcm_to_mel` after a float32 normalization

### 3. ChunkQueue
- `Channel<Chunk>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)`
- On overflow drop: insert `Chunk.MarkerLost(idx, reason="queue full")` and surface to UI as a banner
- Capacity 4 covers ~2 minutes of audio buffered while transcription catches up

### 4. TranscriptionWorker (single-threaded consumer) — Whisper, NOT Gemma
- One coroutine reads from `chunkQueue.consumeAsFlow()`
- WhisperEngine is initialized once at app start with the bundled `assets/whisper/ggml-tiny.en.bin`
- For each chunk:
  ```kotlin
  // Convert int16 PCM to float32 normalized
  val floatPcm = FloatArray(chunk.bytes.size / 2) { i ->
      val sample = (chunk.bytes[i*2].toInt() and 0xFF) or (chunk.bytes[i*2+1].toInt() shl 8)
      sample.toShort().toFloat() / 32768f
  }
  val transcript = whisperEngine.transcribe(floatPcm, sampleRateHz = 16000)
  aggregator.append(chunk.idx, chunk.startMs, transcript)
  ```
- On exception: catch, log, emit `aggregator.appendFailureMarker(chunk.idx)`, continue
- Whisper-tiny is single-threaded for inference; serialize chunks (one at a time) — bounded by ChunkQueue
- The Gemma 4 E4B engine is loaded separately and used ONLY by the on-demand tab generation (CLEAN/ANALYSIS/FORAI), not by transcription

### 5. TranscriptAggregator + DedupAlgorithm (THE ONE DEFINED ALGORITHM)
- Aggregator holds `MutableStateFlow<String>` representing the running RAW transcript
- DedupAlgorithm spec (the only one to implement; ignore prep doc's earlier LCS reference — it's superseded):

```kotlin
fun mergeWithDedup(prev: String, next: String): String {
    if (prev.isEmpty()) return next
    if (next.isEmpty()) return prev

    // Compare last 200 chars of prev with first 250 chars of next
    val tail = prev.takeLast(200).lowercase().filterNot { it.isWhitespace() }
    val head = next.take(250).lowercase().filterNot { it.isWhitespace() }

    // Find longest tail-suffix that matches a prefix of head with ≤2 mismatches per 50 chars
    var bestMatchLen = 0
    var bestNextOffset = 0
    for (matchLen in tail.length downTo 10) {
        val tailSuffix = tail.takeLast(matchLen)
        // Search head for tailSuffix with fuzzy match
        for (start in 0 .. (head.length - matchLen)) {
            val candidate = head.substring(start, start + matchLen)
            val edits = levenshtein(tailSuffix, candidate)
            if (edits <= matchLen / 25) { // ≤2 per 50 chars
                bestMatchLen = matchLen
                bestNextOffset = start + matchLen
                break
            }
        }
        if (bestMatchLen > 0) break
    }

    if (bestMatchLen == 0) return prev + " " + next  // no overlap found, just concat

    // Cut next at bestNextOffset (mapped back to original-cased next)
    val nextOriginalOffset = mapStrippedOffsetToOriginal(next, bestNextOffset)
    return prev + next.substring(nextOriginalOffset)
}

// Helper: maps an offset in the stripped (no-whitespace, lowercased) string back to the original
fun mapStrippedOffsetToOriginal(original: String, strippedOffset: Int): Int {
    var strippedCounter = 0
    for ((i, ch) in original.withIndex()) {
        if (!ch.isWhitespace()) {
            if (strippedCounter == strippedOffset) return i
            strippedCounter++
        }
    }
    return original.length
}

// Helper: classic iterative Levenshtein. Inline; do NOT add a dependency.
fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    var curr = IntArray(b.length + 1)
    for (i in 1 .. a.length) {
        curr[0] = i
        for (j in 1 .. b.length) {
            val cost = if (a[i-1] == b[j-1]) 0 else 1
            curr[j] = minOf(curr[j-1] + 1, prev[j] + 1, prev[j-1] + cost)
        }
        val tmp = prev; prev = curr; curr = tmp
    }
    return prev[b.length]
}
```
- Fixture file format: `tests/dedup_fixtures/N.json`:
  ```json
  {
    "prev": "...the speaker said something about Roman currency.",
    "next": "currency. The Roman coins were accepted everywhere.",
    "expected": "...the speaker said something about Roman currency. The Roman coins were accepted everywhere."
  }
  ```
- Ship 5 fixtures: clean overlap, fuzzy ASR variance, no overlap, all-overlap, partial-overlap-mid-sentence

### 6. Compose UI pattern
- Top-level: `Scaffold` with `TopAppBar` (record/stop button) + `BidetTabsScreen` body
- `BidetTabsScreen`: `Column { TabRow(...) ; HorizontalPager(...) }`
- `TabRow` indices ↔ `PagerState` synced bidirectionally
- RAW tab: streaming text, autoscroll-to-bottom while recording
- Other three tabs: each has `MutableStateFlow<TabState>` where `TabState = Idle | Generating | Cached(text, generatedAt)`
- "Generate" button on each non-RAW tab — runs only on tap

### 7. Tab caching
- Cache key: SHA-256 of `(raw_text + prompt_version + temperature_setting)`
- Cache value: `(generated_text, generated_at_unix)`
- Storage: `DataStore<Preferences>` keyed by `tab_cache_<sha>` → JSON-serialized value
- Invalidation: on next recording session start, clear cache for that session_id

### 8. Model download (no HF OAuth)
- URL: `https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm`
- Use upstream `DownloadWorker.kt` (already supports HTTP-Range resume + redirect-following + SHA256 verify)
- Storage path: `${context.getExternalFilesDir(null)}/models/gemma-4-E4B-it.litertlm`
- Show download progress UI (% + downloaded/total + speed). Resume on app restart if partial.
- On hash mismatch: delete + restart download once, then show error

### 9. Compliance: Apache 2.0 + Gemma Terms
- **Repo files (root):**
  - `LICENSE` — preserve upstream Apache 2.0 unchanged
  - `NOTICE` — NEW. Content:
    ```
    Bidet-Phone
    Copyright 2026 Mark Barnett (bidet-ai)

    This product includes software developed by Google LLC
    (https://github.com/google-ai-edge/gallery), licensed under the
    Apache License, Version 2.0.

    Gemma is provided under and subject to the Gemma Terms of Use
    found at ai.google.dev/gemma/terms.
    ```
  - `GEMMA_TERMS.md` — NEW. Single line: "Gemma is provided under and subject to the Gemma Terms of Use found at ai.google.dev/gemma/terms."
  - `UPSTREAM_GALLERY_SHA.md` — NEW. Records the upstream commit SHA forked from + date.
- **Per-file headers (Apache 2.0 §4(b)(c)):**
  - Every file MODIFIED from upstream: add line directly below upstream's copyright: `* Modifications Copyright 2026 bidet-ai contributors. Changed: <one-line description>`
  - Every NEW file under `com/google/ai/edge/gallery/bidet/...`: prepend a fresh Apache 2.0 header with `Copyright 2026 bidet-ai contributors`
- **Gemma Terms first-run consent screen:**
  - Shown ONCE on first launch BEFORE model download
  - Title: "About Gemma 4"
  - Body: "This app uses Google's Gemma 4 model running locally on your device. By continuing, you agree to the Gemma Terms of Use and Prohibited Use Policy."
  - Two links: `https://ai.google.dev/gemma/terms` and `https://ai.google.dev/gemma/prohibited_use_policy`
  - Buttons: [Decline] (closes app) | [Accept & Download]
  - Persist consent in DataStore. Re-show only if Terms URL changes (don't re-prompt on every reinstall).
- **About / Licenses screen:** standard `OssLicensesMenuActivity` plus a static link to Gemma Terms

### 10. Build configuration
- **JDK runtime:** Temurin 21 (CI uses `actions/setup-java@v4` with `java-version: 21, distribution: temurin`)
- **Source/target compatibility:** keep upstream's `JavaVersion.VERSION_11`
- **Kotlin `jvmTarget`:** keep upstream's `"11"`
- **AGP / Kotlin / Compose Compiler versions:** keep upstream's pinned versions in `gradle/libs.versions.toml`
- **No version bumps in v0.1.** Reduces risk of build break.
- **LiteRT-LM ≥ 0.10.1 PINNED.** Pixel 8 Pro on 0.10.0 hits a documented GPU decode crash (`Failed to clEnqueueNDRangeKernel - Invalid command queue`). Bump if upstream Gallery is on an older version. Reference: [LiteRT-LM#1850](https://github.com/google-ai-edge/LiteRT-LM/issues/1850).

### 11. APK signing strategy
- Generate stable release keystore once (during initial build): `bidetphone-release.jks`
- Store in GitHub Secrets:
  - `RELEASE_KEYSTORE_B64` (base64 of the .jks)
  - `RELEASE_KEYSTORE_PASS`
  - `RELEASE_KEY_ALIAS`
  - `RELEASE_KEY_PASS`
- CI workflow base64-decodes secret → `${RUNNER_TEMP}/release.jks`, exports env vars, gradle picks them up via `signingConfigs { release { ... } }`
- `release.signingConfig = signingConfigs.getByName("release")` in `app/build.gradle.kts`
- First release: Mark sideloads via Obtainium once. Subsequent updates flow through Obtainium without reinstall (signature stable).
- "Unknown developer" warning is unavoidable for non-Play APKs — Obtainium handles it.

### 12. Build-time banned-word check
- New Gradle task `banWordCheck` runs on `assembleDebug` and `assembleRelease`
- Scans: `app/src/main/res/values/strings.xml`, `app/src/main/assets/`, all `.kt` literal strings under `app/src/main/`
- Banned tokens scan rules:
  - Case-insensitive whole-word: `St. Francis`, `sfschools`, `K-12`, `K12`, `middle school`, `ADHD`, `adult ADD`
  - Case-sensitive whole-word: `ADD` (avoids tripping on common verb usage like "add to list" — only flags the all-caps clinical use)
- Use word-boundary regex (`\bTOKEN\b`) so substring matches don't trip the build
- Build fails if any token found in user-visible code paths
- Implementation: a simple Kotlin script under `buildSrc/` reading files, grepping, and `throw GradleException(...)` on hit

### 13. INTERNET permission and offline framing
- `AndroidManifest.xml` declares `<uses-permission android:name="android.permission.INTERNET" />` — required for first-run model download and optional debug TP3 webhook
- Marketing copy clarification: "All LLM inference runs locally on-device. INTERNET is used only for the one-time Gemma 4 model download and the optional 'Send to TP3' debug feature. After model download, the app works fully offline (verified by airplane-mode test)."

## Acceptance criteria (v0.1)

### Build correctness
- [ ] `./gradlew assembleDebug` builds clean (Temurin JDK 21, source/target = 11)
- [ ] `./gradlew assembleRelease` builds clean and produces a release-signed APK using the GitHub-Secrets-provided keystore
- [ ] `./gradlew banWordCheck` passes (no banned tokens in user-visible code paths)
- [ ] All unit tests pass: `./gradlew test`
- [ ] CI workflow `.github/workflows/build_android.yaml` on push to main attaches APK to GitHub Releases (on tags matching `v*`)

### App behavior — happy path
- [ ] App launches on Pixel 8 Pro (Android 15+). First-run shows Gemma Terms consent screen.
- [ ] After consent, model download begins with progress UI. ~3.7 GB.
- [ ] Recording starts when user taps the record button on the top app bar.
- [ ] Foreground notification appears, includes Stop action.
- [ ] Recording survives screen-off for 5+ minutes.
- [ ] Recording goes 5+ minutes without crash.
- [ ] After Stop, RAW transcript appears within 15 seconds (acknowledging CPU audio encoding latency on Tensor TPU).
- [ ] CLEAN/ANALYSIS/FORAI tabs each generate within 12 seconds on tap (acknowledging same CPU latency reality).
- [ ] Chunk overlap dedup produces no duplicated phrases at boundaries (verified by `DedupAlgorithmTest` with 5 fixtures).
- [ ] Tab cache: re-tapping a tab shows previous result instantly without re-running.

### App behavior — failure modes
- [ ] Model download fails partway → app retries with HTTP-Range resume on next launch
- [ ] User dismisses notification → recording continues (notification is `setOngoing(true)` non-dismissible)
- [ ] Phone call interrupts → recording pauses on AUDIOFOCUS_LOSS_TRANSIENT, resumes on AUDIOFOCUS_GAIN
- [ ] Storage fills mid-session → graceful stop with toast "Storage full — recording stopped at <time>"; partial transcript preserved
- [ ] Chunk N transcription fails → log + insert "[chunk N transcription failed]" marker in RAW + recording continues
- [ ] App force-killed mid-session → on next launch, "Recover unfinished session?" prompt restores transcript up to last successful chunk

### Compliance
- [ ] `LICENSE` (Apache 2.0), `NOTICE`, `GEMMA_TERMS.md`, `UPSTREAM_GALLERY_SHA.md` all present at repo root
- [ ] All modified upstream files carry the modification-copyright line directly below upstream's
- [ ] All new files under `com/google/ai/edge/gallery/bidet/...` carry fresh Apache 2.0 headers
- [ ] First-run Gemma Terms consent screen appears and gates model download
- [ ] About / Licenses screen lists Apache 2.0 (Gallery) + Gemma Terms (link)
- [ ] No mention of `St. Francis`, `sfschools`, `K-12`, `K12`, `middle school` in any user-visible code path (banWordCheck enforces)
- [ ] Public CLEAN prompt is generic single-speaker brain-dump cleanup (no school/student/lecture-specific examples or proper nouns)
- [ ] On a 30-sec brain-dump fixture, ANALYSIS output structurally contains a Headline + ≥2 Threads + (Action items XOR Open questions XOR both, where applicable). Tests structure-following, not subjective quality. (Per v3 review: `DedupAlgorithmTest`-style fixture in `Android/src/app/src/test/.../AnalysisStructureTest.kt`.)
- [ ] On a <60-sec brain-dump fixture, ANALYSIS output OMITS Threads section (per the short-input clause — does NOT fabricate threads when content is sparse)

### Repository hygiene
- [ ] First commit dated ≥ 2026-05-06 (Entry Period start)
- [ ] No uncredited code from sources other than upstream Gallery and standard libraries
- [ ] README sections present: Project description, Upstream attribution, Apache 2.0 notice, Gemma Terms notice, "What's new vs. upstream Gallery" (with quantitative diff stats), How to install via Obtainium, License

## Latency budget

Updated for Whisper-tiny ASR + Gemma 4 E4B tab generation split.

**Per chunk during recording (background, no user impact):**
- Whisper-tiny on 30-sec PCM: 2-4 seconds on Pixel 8 Pro Tensor G3 (community benchmarks)
- Pipelined with next chunk's recording — user feels nothing

**After user taps Stop:**
- Last chunk Whisper transcription: 2-4 seconds
- Aggregator dedup: <100 ms
- **RAW transcript ready: ~3-5 seconds after Stop**

**On-demand tab generation (Gemma 4 E4B, text-only):**
- CLEAN tab: 3-5 seconds (LiteRT-LM Gemma 4 E4B at ~30 tok/s on Tensor TPU, ~150 output tokens for typical brain-dump)
- ANALYSIS tab: 4-7 seconds (~200 output tokens including bullets + threads)
- FORAI tab: 5-8 seconds (~250 output tokens for structured markdown)

If real-device measurement shows worse, options:
- (a) Accept it, document honestly
- (b) Switch from Whisper-tiny to Whisper-base.en if quality not acceptable but latency budget allows (~120 MB asset, ~2x latency)
- (c) Pre-cache CLEAN tab on each chunk (always-on background generation)

**v0.1 default: (a). Re-evaluate after first APK on real device.**

## Narrative & README guidance
- Mark writes the public-facing prose. Cursor stub-in placeholders only.
- README sections (Cursor scaffolds, Mark fills the prose):
  1. **Project description** — one-paragraph stub
  2. **Why I built this** — Mark's brain-dump origin story (HE writes this — leave a `<!-- Mark fills this in -->` marker)
  3. **What's new vs. upstream Gallery** — Cursor populates with the engineering-delta bullet list (objective, fact-based)
  4. **How it works** — Cursor populates with the architecture diagram + brief explanation
  5. **Privacy** — Cursor scaffolds: "All LLM inference runs locally on Pixel hardware. Mic audio never leaves the device. INTERNET is used only for first-run model download from HuggingFace and the optional debug 'Send to TP3' webhook." Mark can edit.
  6. **Install via Obtainium** — Cursor scaffolds steps; Mark verifies on his Pixel
  7. **License** — Apache 2.0 + Gemma Terms references
  8. **Upstream attribution** — Apache 2.0 attribution to Google Gallery with link
- Submission post on dev.to: **Mark writes from scratch.** Cursor doesn't draft. Required sections per Build track (Cursor lists the requirements as a checklist for Mark, doesn't write content):
  - What I Built
  - Demo (video link or live demo URL)
  - Code (repo URL + GitHub Releases APK link)
  - How I Used Gemma 4 (E4B variant rationale)
  - What's New vs. Upstream Gallery (engineering delta — Mark can copy from README)
  - Development Timeline (started 2026-05-XX during Entry Period)
  - Attributions

## Constraints (non-negotiable per memory)
- Apache 2.0 license (matches upstream + contest-recommended + patent grant)
- No telemetry. No analytics. No phone-home.
- No cloud LLM fallback (per `feedback_correct_not_fast_2026-05-07.md`)
- Single-speaker design only (per `project_bidet_phone_design_assumption_2026-05-07.md`)
- Public-narrative framing: brain-dump → AI input is PRIMARY (lectures secondary). NEVER frame as K-12 / classroom (per `feedback_bidet_phone_narrative_for_college_lectures_2026-05-07.md` + `project_bidet_phone_design_assumption_2026-05-07.md`)
- **User-visible copy must avoid clinical terms ("ADD," "ADHD," "ADHD-friendly," "adult ADD," etc.).** Use descriptive language: "tangent-driven thinking," "scatterbrain mode," "chaotic thinking." Per `user_mark_has_ADD_not_ADHD_2026-05-07.md`. The `banWordCheck` Gradle task adds these to the banned-token list.
- No nephew names, no "St. Francis," no `sfschools.net`, no "K-12" anywhere user-visible (per `user_personal_facts.md` + banWordCheck)
- v0.2 is post-contest; do NOT scope-creep (per `feedback_correct_not_fast_2026-05-07.md`)

## What this brief is NOT
- Not the contest submission post — that's Mark's prose
- Not a v0.2 spec — Chrome ext, Play Store, F-Droid, landing page on bidetai.app, prompt customization for non-debug users — all DEFERRED
- Not a feature spec for the E2B variant — E4B-only in v0.1
- Not a multi-speaker recorder — see design-assumption memory

## Holding for prompt lock + audio-path verification
This brief fires when:
1. Prompt-iteration playground produces v1 versions of `assets/prompts/{clean,analysis,forai}.txt` validated on local Gemma 4 E4B
2. Gemma 4 E4B audio-path smoke-test confirms verbatim transcription is achievable with prompt prefix engineering
3. HuggingFace E4B URL `curl -I` confirms 200/302 with no auth

Then we replace placeholder prompt files in `assets/prompts/` with locked versions and ship to Cursor.

---

## App icon / branding

Mark provided the bidet-ai mascot icon at `branding/bidet-ai_icon.png` (351×411 PNG with alpha — a stylized toilet with water spraying, his "muppet" self-portrait re-styled for the Bidet AI brand). Use this as:

1. **App launcher icon** — Android adaptive icon. Generate the standard density buckets (mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi) from the source. The asset isn't square, so center it on a transparent square canvas before resizing; do not stretch or crop.
2. **App splash / about-screen** — full-resolution as the launch identity.
3. **README** — embed the PNG at the top.

The icon is part of Mark's Bidet AI brand (which also has desktop and Chrome counterparts). Do NOT generate a new logo. Use this asset.

