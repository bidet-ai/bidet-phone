# Whisper model bundle

This directory holds `ggml-tiny.en.bin` (~75 MB), the bundled English-only
Whisper-tiny weights used by `WhisperEngine` for on-device ASR.

## Auto-fetch (Phase 3 default)

The `.bin` file is intentionally **not committed to git** (see `.gitignore`
in this directory). It is fetched automatically at build time by the
`fetchWhisperModel` Gradle task registered in `app/build.gradle.kts`. The
task:

1. Checks if `ggml-tiny.en.bin` already exists with the expected SHA-256.
2. If not present (or the hash mismatches), downloads from HuggingFace.
3. Verifies SHA-256 against the locked value below.
4. Atomically renames `.tmp` → final on success.

The task is hooked into `mergeDebugAssets` / `mergeReleaseAssets` so the
asset is in place before the APK is assembled. CI fetches once per build;
locally the file is cached in the working tree.

URL: `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin`
SHA-256: `921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f`

## Manual fetch (fallback)

If you need to seed the file out-of-band (e.g. running `./gradlew test`
without network access in a sealed environment, or pre-populating a
container image):

```bash
curl -L --create-dirs -o Android/src/app/src/main/assets/whisper/ggml-tiny.en.bin \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin
```

Then verify:

```bash
shasum -a 256 Android/src/app/src/main/assets/whisper/ggml-tiny.en.bin
# expected: 921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f
```

The auto-fetch task will see the existing file, hash-verify it, and
short-circuit — no network call.
