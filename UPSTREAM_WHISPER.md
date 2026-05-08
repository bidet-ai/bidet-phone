# Whisper Engine choice

We use **whisper-jni 1.7.1** by [@givimad](https://github.com/GiviMAD/whisper-jni)
(MIT-licensed Java/Kotlin JNI bindings around upstream `ggerganov/whisper.cpp`)
as the on-device ASR engine.

| Field             | Value                                                          |
| ----------------- | -------------------------------------------------------------- |
| Library           | `io.github.givimad:whisper-jni:1.7.1`                          |
| Underlying repo   | https://github.com/ggerganov/whisper.cpp                       |
| License           | MIT (whisper-jni) + MIT (whisper.cpp)                          |
| Model             | `ggml-tiny.en.bin` (~75 MB, English-only quantised)            |
| Model URL         | https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin |
| Bundle location   | `Android/src/app/src/main/assets/whisper/ggml-tiny.en.bin`     |

## Why this library

- **Official upstream backbone.** whisper.cpp is the de-facto on-device Whisper
  port; whisper-jni is a thin, well-tested JNI shim that ships native AAR
  binaries for arm64-v8a (Pixel 8 Pro = Tensor G3, ARM64).
- **MIT permissive.** Compatible with Apache 2.0; we just need to retain
  copyright + permission notices in the bundled NOTICE.
- **Kotlin-friendly API.** The JNI binding exposes `WhisperJNI.full(ctx,
  params, samples, samplesLength)` returning the transcribed text — no
  hand-rolled JNI required.

## Alternatives evaluated, deferred

- **TFLite Whisper** (community ports): may be faster on Tensor TPU long-term
  but the Android packaging story is fragmented and the available `.tflite`
  files don't all match the `tiny.en` quality bar. Revisit in v0.2 if Pixel
  8 Pro real-device latency exceeds the 2-4 second per-30s-chunk budget.
- **Direct whisper.cpp Android sample** (`whisper.cpp/examples/whisper.android`):
  works but requires us to maintain our own JNI bindings + native build. Net
  win is small; library route reduces surface area.

## Asset bundling note

The `ggml-tiny.en.bin` file (~75 MB) is **not committed to git.** It is fetched
on first build via a Gradle `prebuild` hook (or by Mark / CI manually placing
it into `Android/src/app/src/main/assets/whisper/`). The exact fetch hook is
deferred to Phase 2 — for this PR, the file is expected to be supplied
out-of-band when the APK is assembled. Acceptance criteria reflect this.

Checksum (verify after download):
```
sha256: be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21
```

(SHA from huggingface.co/ggerganov/whisper.cpp model card; verify against the
mirror at download time.)
