# Moonshine + sherpa-onnx ASR engine choice

We use **Moonshine-Tiny** (Useful Sensors / moonshine-ai) as the on-device speech-to-text
model, run via **sherpa-onnx** (k2-fsa) — an ONNX Runtime-based speech library.

| Field             | Value                                                          |
| ----------------- | -------------------------------------------------------------- |
| Model             | Moonshine-Tiny v2 (English, 27M params)                        |
| Model license     | MIT (Useful Sensors)                                           |
| Bundle            | `sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27` (encoder + merged-decoder, ORT flatbuffer, ~44 MB extracted) |
| Bundle URL        | https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27.tar.bz2 |
| Bundle SHA-256    | `9ec31b342d8fa3240c3b81b8f82e1cf7e3ac467c93ca5a999b741d5887164f8d` |
| Bundle location   | `Android/src/app/src/main/assets/moonshine/` (fetched + extracted at build time, sha-256 verified) |
| Inference engine  | **sherpa-onnx** v1.13.1                                        |
| Engine license    | Apache-2.0 (k2-fsa)                                            |
| AAR variant       | **`sherpa-onnx-static-link-onnxruntime-1.13.1.aar`** (36.4 MB, vendored at `app/libs/`) |
| Native libs       | `libsherpa-onnx-jni.so` only (ONNX Runtime statically linked — no `libonnxruntime.so` collision with LiteRT-LM's bundled ORT) |
| Supported ABI     | `arm64-v8a` only (Pixel 8 Pro target)                          |
| Kotlin entry point| `com.k2fsa.sherpa.onnx.OfflineRecognizer` + `OfflineMoonshineModelConfig` |
| Bidet wrapper     | `com.google.ai.edge.gallery.bidet.transcription.MoonshineEngine` |

## Why Moonshine + sherpa-onnx (replacing Whisper-tiny + whisper.cpp v0.2)

The v0.2 build shipped Whisper-tiny via a from-source whisper.cpp NDK build. v0.3
replaces it with Moonshine-Tiny via sherpa-onnx because Moonshine wins on every axis:

| Axis | Whisper-tiny | Moonshine-Tiny |
|---|---|---|
| Params | 39M | **27M** |
| LibriSpeech-clean WER | 5.66 | **4.55** |
| FLOPs vs Whisper-tiny @10s | 1.0× | **5× less** |
| Engine | whisper.cpp (manual NDK build) | **sherpa-onnx (Apache-2.0 AAR, no NDK)** |
| Variable-length audio | Pads to 30 s (wastes compute on silence) | **RoPE encoder, no padding waste** |
| License | MIT | MIT |

Plus the operational wins:

- **No NDK CMake project** — sherpa-onnx ships a prebuilt AAR. Saves ~30 LOC of Gradle
  + an `externalNativeBuild` block + a git submodule.
- **No `libwhisper.so` build artifact** — one fewer .so per APK to debug.
- **Smaller APK** — Moonshine v2 quantized bundle (~44 MB extracted) vs Whisper-tiny GGUF
  Q8 (~75 MB).
- **Streaming-ready** — sherpa-onnx ships `OnlineRecognizer` + streaming Moonshine ONNX
  bundles. v0.3 ships non-streaming for simplicity; v0.4 polish slot.

Background research lives in the parent claude-memory repo at
`reference_moonshine_replaces_whisper_2026-05-10.md` and the deep-research doc the
migration was scoped from at `/tmp/moonshine_sherpa_unsloth_research.md`.

## How the engine is loaded

`MoonshineEngine.initialize()` constructs a `com.k2fsa.sherpa.onnx.OfflineRecognizer`
with an `OfflineMoonshineModelConfig` pointing at the assets:

```kotlin
val cfg = OfflineRecognizerConfig(
    modelConfig = OfflineModelConfig(
        moonshine = OfflineMoonshineModelConfig(
            encoder = "moonshine/encoder_model.ort",
            mergedDecoder = "moonshine/decoder_model_merged.ort",
        ),
        tokens = "moonshine/tokens.txt",
        numThreads = 2,
        provider = "cpu",
    ),
    decodingMethod = "greedy_search",
)
val recognizer = OfflineRecognizer(assetManager = context.assets, config = cfg)
```

Per-chunk decode is one stream:

```kotlin
val stream = recognizer.createStream()
stream.acceptWaveform(floatPcm, sampleRate = 16_000)
recognizer.decode(stream)
val text = recognizer.getResult(stream).text
stream.release()
```

(No `runBlocking` needed — `recognizer.decode` is sync. The bidet wrapper still pushes
this onto `Dispatchers.Default` so the audio capture loop keeps a free CPU.)

## ABI cohabitation with LiteRT-LM (gemma flavor)

The big risk during scoping was that both the sherpa-onnx AAR and LiteRT-LM's AAR would
ship `libonnxruntime.so` at different versions, with the second-loaded version silently
overwriting the first and producing version-mismatch crashes. We dodged this by using the
**static-link** sherpa-onnx AAR (`sherpa-onnx-static-link-onnxruntime-1.13.1.aar`), which
only ships `libsherpa-onnx-jni.so` for arm64-v8a (ONNX Runtime baked into the JNI lib).
LiteRT-LM is free to bring its own ORT for the gemma flavor without conflict.

## License compatibility

| Component | License | Compatible? |
|---|---|---|
| Moonshine-Tiny weights | MIT | yes |
| sherpa-onnx engine | Apache-2.0 | yes |
| LiteRT-LM (gemma flavor) | Apache-2.0 | yes |
| Gemma 4 weights | Apache-2.0 (relicensed 2026) | yes |
| Bidet AI codebase | Apache-2.0 | yes |

All commercial + derivative use is permitted.

## Future work (post-contest)

- **Streaming variant.** Swap the v2 quantized non-streaming bundle for the
  `sherpa-onnx-moonshine-tiny-en-streaming-*` bundle + use `OnlineRecognizer` instead of
  `OfflineRecognizer`. Surfaces partial transcripts to the RAW tab while the user is still
  speaking. ~30 LOC delta.
- **Mark-voice fine-tune.** Re-run the Whisper-mark fine-tune corpus through Moonshine's
  full-finetune toolkit (`pierre-cheneau/finetune-moonshine-asr` on GitHub). v0.4 stretch
  per the deep-research doc.
- **Rename the `RecordingService.EngineType.Whisper` enum case** to `Moonshine` — locked in
  v0.3 because RecordingService.kt was owned by another agent during the parallel-agent
  refactor wave.
