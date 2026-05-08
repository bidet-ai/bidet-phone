# Whisper model bundle

This directory holds `ggml-tiny.en.bin` (~75 MB), the bundled English-only
Whisper-tiny weights used by `WhisperEngine` for on-device ASR.

The `.bin` file is intentionally **not committed to git** (see `.gitignore`
in this directory). At assemble time, fetch it once with:

```bash
curl -L --create-dirs -o Android/src/app/src/main/assets/whisper/ggml-tiny.en.bin \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin
```

Then verify:

```bash
shasum -a 256 Android/src/app/src/main/assets/whisper/ggml-tiny.en.bin
# expected: be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21
```

CI (Phase 2) will automate the fetch + checksum step. For v0.1 the file is
supplied out-of-band by the developer assembling the APK locally.
