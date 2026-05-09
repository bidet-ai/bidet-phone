# Whisper Engine choice

We use **whisper.cpp** built from source via the Android NDK as the on-device ASR engine.

| Field             | Value                                                          |
| ----------------- | -------------------------------------------------------------- |
| Library           | `ggerganov/whisper.cpp` (C/C++ source, MIT-licensed)           |
| Pinned version    | `v1.8.4` (git submodule under `Android/src/app/src/main/cpp/whisper.cpp`) |
| Build system      | NDK + CMake via `externalNativeBuild` (see `app/build.gradle.kts`) |
| Kotlin bindings   | Vendored from `whisper.cpp/examples/whisper.android/lib` (Apache + MIT) — packaged at `com.whispercpp.whisper.{WhisperContext,WhisperLib,WhisperCpuConfig}` |
| JNI shim          | Vendored verbatim from `whisper.cpp/examples/whisper.android/lib/src/main/jni/whisper/jni.c` at `Android/src/app/src/main/cpp/whisper_jni.c` |
| Model             | `ggml-tiny.en.bin` (~75 MB, English-only quantised)            |
| Model URL         | https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin |
| Bundle location   | `Android/src/app/src/main/assets/whisper/ggml-tiny.en.bin` (fetched at build time, sha-256 verified) |
| Supported ABI     | `arm64-v8a` only (Pixel 8 Pro target). Two variants produced: `libwhisper.so` (default) and `libwhisper_v8fp16_va.so` (ARMv8.2-A fp16). `LibWhisper.kt` picks at runtime via `/proc/cpuinfo` detection. |

## History — why this changed (2026-05-08)

Phase 3 originally pulled in `io.github.givimad:whisper-jni:1.7.1` from Maven Central, with this rationale: *"whisper-jni is a thin, well-tested JNI shim that ships native AAR binaries for arm64-v8a (Pixel 8 Pro = Tensor G3, ARM64)."*

**That claim was wrong.** First real-device test on a Pixel 8 Pro (2026-05-08 evening) surfaced a 100%-reproducible `UnsatisfiedLinkError`:

```
java.lang.UnsatisfiedLinkError: dlopen failed:
  library "libwhisper-jni.so" not found
  at WhisperEngine.initialize(WhisperEngine.kt:62)
```

Inspection of the actual JAR bytes (verified against Maven Central directly) showed:

```
JAR contents → debian-amd64/, debian-arm64/, debian-armv7l/,
               macos-amd64/, macos-arm64/, win-amd64/
There is NO Android binary in the JAR — anywhere.
```

`io.github.givimad:whisper-jni` is a **desktop JVM library**. The desktop "linux-aarch64" `.so` it does ship is glibc-linked and would not load on Android (which uses bionic).

The original Cursor brief (2026-05-07) explicitly said: *"whisper.cpp Android JNI (https://github.com/ggerganov/whisper.cpp/tree/master/examples/whisper.android) — official C++ port, ~75 MB tiny.en model, well-supported. Cursor: clone whisper.cpp, build the Android sample, confirm it runs on Pixel 8 Pro before integrating."* That guidance was correct. The Phase 3 implementation skipped the build step and substituted a Maven dep, which is what created Bug C.

## Why this library, now

- **Official upstream backbone, properly built.** whisper.cpp is the canonical on-device Whisper port. Building it via NDK is the path the upstream `examples/whisper.android` sample documents.
- **MIT permissive.** Compatible with our Apache 2.0; copyright + permission notices preserved in `NOTICE` and per-file headers.
- **Pixel-tuned.** The `whisper_v8fp16_va` build target uses ARMv8.2-A fp16 intrinsics on the Pixel 8 Pro Tensor G3 CPU.

## Asset bundling

Unchanged from the prior plan:
- The `ggml-tiny.en.bin` file (~75 MB) is **not committed to git.** It is fetched on every build via the `fetchWhisperModel` Gradle task hooked into `mergeAssets`. See `app/build.gradle.kts`.
- SHA-256 verification: `921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f`

## What v0.2 might revisit

- **TFLite Whisper** (community ports): may be faster on Tensor TPU long-term but the Android packaging story remains fragmented and quality varies. Revisit if Pixel 8 Pro real-device latency on `whisper_v8fp16_va` exceeds the 2-4 second per-30s-chunk budget.
- **Multi-ABI APK**: re-add `armeabi-v7a` + `x86_64` if the app targets devices beyond Pixel 8 Pro.
- **Whisper-mark fine-tune deployment**: the LoRA chain trained on Apex (`whisper-mark-chunk{1..5}-v2/`) produces ggml-format adapters. They drop into this build as alternative `.bin` files via `assets/whisper/`.
