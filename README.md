# Bidet AI for Android

<p align="center">
  <img src="branding/bidet-ai_icon.png" alt="Bidet AI logo" width="180" />
</p>

**Bidet AI is for people whose brains move faster than their fingers.**

It's an offline voice brain-dump cleaner for Android, designed for the
students who lose ideas to the mechanical friction of typing — readers
and writers whose thinking outruns the keyboard. Talk a thought out
loud; get back a clean transcript, an organized analysis, and an
AI-ready structured prompt — all generated locally on your phone.
No cloud. No telemetry.

> **Status:** v0.2, contest build for the Google Gemma 2026 Challenge
> (submission deadline 2026-05-24). Primary target device: Pixel 8 Pro
> (Android 15+). First-run Gemma 4 model download is ~3.7 GB.
>
> **Verified contest-fit.** The peer-reviewed research supporting this
> use-case lives in two judges-friendly dossiers:
> [brain-dump research](https://reports.thebarnetts.info/r/2026-05-09-brain-dump-research)
> · [deep-research dossier](https://reports.thebarnetts.info/r/2026-05-09-bidet-deep-research).

---

## Who Bidet AI is for

There are four overlapping groups whose lives get noticeably easier when
the keyboard stops being the bottleneck. The peer-reviewed evidence for
voice-first capture and AI-assisted reformatting in these populations
is robust:

- **College students with attention-related learning differences** who
  can hold a thought long enough to say it but lose it the moment they
  stop to type.
- **Students with phonological / decoding barriers to written input** —
  readers for whom a wall of text is a wall.
- **Students with graphomotor / orthographic barriers to written
  output** — writers for whom typing or handwriting is the friction,
  not the thinking.
- **Associative thinkers who think in tangents** — people whose ideas
  arrive in branching threads rather than linear sentences, and who
  need a tool that can collect the branches and hand them back as a
  trunk.

The four-tab UX (RAW / CLEAN / ANALYSIS / FORAI) is shaped around
tangent-driven thinking. The model does the reorganizing so the
speaker doesn't have to. The research dossiers above lay out the
specific peer-reviewed evidence base for each group.

## Why this exists

<!-- Mark fills this in: 1-2 paragraphs about why brain dumps matter to you.
The video script will use this. Do not generate marketing prose on his behalf.
-->

## Project description

<!-- Mark fills this in: one to two sentences in his voice. -->

Bidet AI for Android captures a single-speaker brain-dump on the phone's
microphone, transcribes it on-device with a bundled Whisper-tiny model,
and then restructures the transcript into four tabs using **Gemma 4 E4B
running locally via LiteRT-LM**:

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

## Per-speaker adaptation roadmap (v0.3+)

> **Status: experimental, in progress, results forthcoming.** Not a v0.2
> contest claim. Prototyped on Mark's own voice; per-user pipeline is the
> v0.3+ deliverable.

The shipping v0.2 build uses Whisper-tiny as its base ASR engine — small,
fast, and good enough for clear single-speaker capture on a Pixel 8 Pro.
But Whisper was trained on web-scraped audio. The "average web speaker"
isn't every speaker — and for users whose voice doesn't match Whisper's
training distribution (heavy accents, English-language-learner cadence,
deaf-speaker English, voices the open web underrepresents), word-error
rates jump.

The durable answer is per-speaker fine-tuning. The model that works for
*you*, not for the average web-scraped speaker.

The recipe being prototyped right now:

- Roughly 30 minutes of clean labeled audio from the user
- An hour of LoRA training on a consumer GPU (RTX 4070-class works)
- Output: a small (~60 MB) personalized adapter that loads on top of
  Whisper-large-v3 and meaningfully reduces WER for that user's voice

The dogfood: Mark's own adapter. A 5-chunk LoRA chain trained on a
22.5-hour corpus of his voice on an RTX 4070-class GPU produced a clean
loss curve from 0.81 down to 0.12 over five training rounds — the
textbook "you're done training" plateau. Held-out testing on real audio
is the next step before the personal-adapter pipeline gets exposed to
users; technical details and results will be linked from the
research dossier as they land.

For accessibility populations whose speech doesn't match Whisper's
training distribution, this per-speaker pathway is the durable
direction the project is heading. v0.2 ships a strong default;
v0.3+ adds the option to make it yours.

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
