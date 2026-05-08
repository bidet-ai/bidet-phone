# Bidet AI for Android

<p align="center">
  <img src="branding/bidet-ai_icon.png" alt="Bidet AI logo" width="180" />
</p>

Offline voice brain-dump cleaner for Android. Talk a thought out loud; get back
a clean transcript, an organized analysis, and an AI-ready structured prompt —
all generated locally on your phone. No cloud. No telemetry.

> **Status:** v0.1, contest build for the Google Gemma 2026 Challenge
> (submission deadline 2026-05-24). Primary target device: Pixel 8 Pro
> (Android 15+). First-run Gemma 4 model download is ~3.7 GB.

---

## Project description

<!-- Mark fills this in: one to two sentences in his voice. -->

Bidet AI for Android captures a single-speaker brain-dump on the phone's
microphone, transcribes it on-device with a bundled Whisper-tiny model, and
then restructures the transcript into four tabs using **Gemma 4 E4B running
locally via LiteRT-LM**:

- **RAW** — the verbatim transcript, streamed live as you talk.
- **CLEAN** — readable cleanup: filler removed, punctuation restored, paragraphs grouped.
- **ANALYSIS** — for the speaker themselves: a headline, the threads pulled out of the chaos, action items, open questions.
- **FORAI** — structured markdown built for pasting into Claude / ChatGPT / Gemini as input.

## Why I built this

<!-- Mark fills this in. This is his origin story; do not generate marketing
prose on his behalf. Bullet points fine, paragraph fine, whichever feels right.
-->

## What's new vs. upstream Gallery

This repo forks
[google-ai-edge/gallery](https://github.com/google-ai-edge/gallery) (Apache
2.0). The upstream "Audio Scribe" path is locked to a single 30-second clip
(`MAX_AUDIO_CLIP_DURATION_SEC = 30` in `Consts.kt`) and runs ASR through Gemma
4's multimodal audio path. Bidet AI for Android extends that into:

1. **Foreground microphone service** (`bidet/service/RecordingService.kt`) —
   `foregroundServiceType="microphone"` per Android 14+ rules, ongoing
   notification with Stop action, AudioFocus pause/resume on phone-call
   interruption.
2. **Continuous capture** (`bidet/audio/AudioCaptureEngine.kt`) — a single
   `AudioRecord` running 16 kHz mono PCM 16-bit, sliced into 30-second windows
   with a 2-second rolling backbuffer to prevent mid-word splits. Each chunk
   is persisted atomically to the session directory for crash recovery.
3. **Pipelined transcription** (`bidet/transcription/`) — chunk N is
   transcribed by bundled Whisper-tiny (whisper.cpp via JNI) while chunk N+1
   records, fed through a bounded `ChunkQueue` with backpressure surfaced to
   the UI.
4. **Deterministic fuzzy de-dup** (`bidet/transcript/DedupAlgorithm.kt`) — at
   chunk boundaries the aggregator merges by suffix-prefix overlap with
   ≤2 mismatches per 50 characters. Five fixture-tests in
   `Android/src/app/src/test/.../DedupAlgorithmTest.kt`.
5. **Four-tab single-speaker UX** (`bidet/ui/BidetTabsScreen.kt`) replacing
   the upstream chat-tab UI. RAW streams live; CLEAN / ANALYSIS / FORAI are
   on-demand, cached by SHA-256 of `(rawSha + promptVersion + temperature)`.
6. **Two-engine architecture, by design.** Whisper-tiny does ASR; Gemma 4 E4B
   does the language-model restructuring. We tested Gemma 4 E4B's audio path
   on real-world wavs (twango.dev / MindStudio benchmarks confirm ~41% WER on
   noisy meeting audio vs. Whisper-large-v3 16%); using Gemma for ASR would
   hallucinate. Using Whisper for restructuring would be using the wrong tool.
7. **Build-time `banWordCheck` Gradle task** prevents banned tokens from
   leaking into user-visible code paths.
8. **First-run Gemma Terms consent screen**
   (`bidet/consent/GemmaTermsConsentScreen.kt`), per Gemma Terms of Use.

The chunked-rolling pipeline also handles long-form single-speaker recordings
(hour-long sessions, podcasts, lectures) as a capability that emerged for free.

## How it works

```
┌──── Pixel 8 Pro (LLM inference is local; INTERNET only for first-run model download) ────┐
│                                                                                            │
│  RecordingService (foreground, type=microphone)                                            │
│      └─> AudioCaptureEngine (continuous AudioRecord 16 kHz mono)                           │
│              └─> ChunkQueue (bounded Channel, capacity=4, DROP_OLDEST + MarkerLost)        │
│                      └─> TranscriptionWorker (single-threaded consumer)                   │
│                              └─> WhisperEngine (whisper.cpp JNI, bundled tiny.en)          │
│                                      └─> TranscriptAggregator (DedupAlgorithm)             │
│                                              └─> StateFlow<String> RAW                     │
│                                                                                            │
│  BidetTabsScreen (TabRow + HorizontalPager: RAW | CLEAN | ANALYSIS | FORAI)                │
│      └─> on-tap -> LlmChatModelHelper.runInference(systemPrompt, currentRaw)               │
│                          (Gemma 4 E4B via LiteRT-LM, local on-device)                      │
└────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Privacy

All LLM inference runs locally on Pixel hardware. Microphone audio never
leaves the device. The Internet permission is used only for:

- the one-time Gemma 4 E4B model download from HuggingFace
  (`litert-community/gemma-4-E4B-it-litert-lm`, ~3.7 GB, no auth required), and
- the optional debug "Send to TP3" feature (off by default, only available in
  debug builds, sends a manually triggered POST to a user-configured webhook).

After the model is downloaded, the app works fully offline. There is no
telemetry, no analytics, no phone-home, no cloud LLM fallback.

## Install via Obtainium

<!-- Mark verifies these steps on his Pixel 8 Pro after the first signed
release. -->

1. Install [Obtainium](https://obtainium.imranr.dev/) on your Android phone.
2. In Obtainium, **Add App** with URL `https://github.com/bidet-ai/bidet-phone`.
3. Pick the latest `v*` release. Obtainium downloads and installs the signed APK.
4. On first launch, accept the Gemma Terms of Use prompt. The model download
   begins (~3.7 GB; resumable via HTTP-Range).
5. Grant the microphone permission when prompted.

Subsequent updates flow through Obtainium without reinstall as long as the
keystore is stable.

## License

This project is released under the **Apache License, Version 2.0**. See
[`LICENSE`](LICENSE) for the full text and [`NOTICE`](NOTICE) for the
attribution to upstream Gallery.

The Gemma 4 model is provided under and subject to the Gemma Terms of Use
found at [ai.google.dev/gemma/terms](https://ai.google.dev/gemma/terms). See
[`GEMMA_TERMS.md`](GEMMA_TERMS.md). First-run consent gate enforces this in-app.

## Upstream attribution

Forked from [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)
at commit `ad7aad854ba34bf205922ddc12cca6cc7fb4f0ae` (2026-05-07). See
[`UPSTREAM_GALLERY_SHA.md`](UPSTREAM_GALLERY_SHA.md) for resync protocol. All
upstream files are kept under the original Apache 2.0 license; modified files
carry a `Modifications Copyright 2026 bidet-ai contributors. Changed: ...`
line directly below the upstream copyright header (per Apache 2.0 §4(b)).
