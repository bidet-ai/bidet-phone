# Building bidet-phone

## Prerequisites

- Temurin JDK 21 (CI uses `actions/setup-java@v4` with `java-version: 21,
  distribution: temurin`).
- Android SDK 35 (compile + target).
- Gradle wrapper is committed; `./gradlew` handles the rest.

## Local build

```bash
cd Android/src
./gradlew :app:test            # unit tests
./gradlew :app:assembleDebug   # produces app/build/outputs/apk/debug/app-debug.apk
```

The `fetchMoonshineModel` task fires automatically before `mergeDebugAssets`
and downloads + extracts the Moonshine-Tiny v2 quantized ONNX bundle
(~30 MB compressed, ~44 MB extracted: encoder + decoder + tokens) into
`app/src/main/assets/moonshine/`. It is idempotent (SHA-256 short-circuit
on the tarball + a marker file in the extracted dir) so subsequent builds
skip the network call.

The vendored sherpa-onnx AAR lives at
`app/libs/sherpa-onnx-static-link-onnxruntime-1.13.1.aar` (36.4 MB) —
committed to the repo because it's not on Maven Central. The static-link
variant statically links ONNX Runtime so there's no `libonnxruntime.so`
collision with LiteRT-LM's bundled ORT (the gemma flavor's runtime).

The Gemma 4 E4B model (~3.7 GB) is **NOT** bundled into the APK. The app
downloads it on first launch (post-Gemma-Terms-consent) into the user's
external files directory. The download is resumable via HTTP-Range.

## CI

Push to any branch builds debug APK + runs unit tests
(`.github/workflows/build_android.yaml`).

A push of a `v*` tag additionally builds a release-signed APK and attaches
it to the matching GitHub Release. Required GitHub Secrets for the release
path:

| Secret | Description |
| --- | --- |
| `RELEASE_KEYSTORE_B64` | Base64-encoded `bidetphone-release.jks` keystore. |
| `RELEASE_KEYSTORE_PASS` | Keystore password. |
| `RELEASE_KEY_ALIAS` | Alias of the signing key inside the keystore. |
| `RELEASE_KEY_PASS` | Password for the signing key. |

The CI workflow's `Decode release keystore (only on tag push)` step
base64-decodes the secret to `${RUNNER_TEMP}/release.jks` and exports the
env vars. The Gradle `signingConfigs.release` block in
`app/build.gradle.kts` reads these env vars; if any are unset (typical
locally), the release build silently falls back to debug signing so
`./gradlew assembleRelease` doesn't require the production keystore for
day-to-day development.

## Generating the release keystore (one-time, before the first signed release)

> **Phase 3 deliberately does NOT generate the keystore.** Mark generates
> the keystore manually when he is ready to publish the first signed APK,
> then uploads its base64 to GitHub Secrets.

When ready:

```bash
keytool -genkey -v \
  -keystore bidetphone-release.jks \
  -alias bidetphone \
  -keyalg RSA -keysize 2048 \
  -validity 10000

# Encode for GitHub Secrets:
base64 -i bidetphone-release.jks -o bidetphone-release.jks.b64

# Then upload:
#   bidetphone-release.jks.b64 contents → RELEASE_KEYSTORE_B64
#   the keystore password           → RELEASE_KEYSTORE_PASS
#   "bidetphone"                    → RELEASE_KEY_ALIAS
#   the key password                → RELEASE_KEY_PASS
```

Keep the keystore file outside the repo. Once the first signed APK ships
to Obtainium users, **never lose this keystore** — losing it means
Obtainium users would have to uninstall and reinstall to take a future
update (signature mismatch).
