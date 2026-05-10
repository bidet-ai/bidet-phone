# Moonshine-Tiny v2 quantized ONNX bundle

This directory holds the speech-to-text model files for the **moonshine** product flavor.
The bundle's `.ort` and `tokens.txt` files are gitignored — they're fetched at build time
by the `fetchMoonshineModel` Gradle task in `app/build.gradle.kts`.

## Files (after a build)

| File | Size | Purpose |
|---|---|---|
| `encoder_model.ort` | ~13 MB | Moonshine v2 encoder, ORT flatbuffer |
| `decoder_model_merged.ort` | ~30 MB | Moonshine v2 merged decoder, ORT flatbuffer |
| `tokens.txt` | ~550 KB | BPE tokenizer vocabulary |
| `.sha256.ok` | 64 B | up-to-date marker (matches expected SHA when present) |

## Source

The bundle is the v2 quantized English variant published by the k2-fsa / sherpa-onnx team:

  https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27.tar.bz2

SHA-256 (verified 2026-05-10):

  9ec31b342d8fa3240c3b81b8f82e1cf7e3ac467c93ca5a999b741d5887164f8d

## Manual fetch

If you want to populate this dir without running Gradle (e.g. CI debug):

```
curl -L -o /tmp/moonshine.tar.bz2 \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27.tar.bz2
tar -xjf /tmp/moonshine.tar.bz2 -C Android/src/app/src/main/assets/moonshine --strip-components=1
shasum -a 256 /tmp/moonshine.tar.bz2  # should match the SHA above
```

## License

Moonshine itself is MIT-licensed (Useful Sensors / moonshine-ai). The sherpa-onnx packaging
is Apache-2.0 (k2-fsa). Both are compatible with Bidet's Apache-2.0.
