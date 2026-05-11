/*
 * Copyright 2025 Google LLC
 * Modifications Copyright 2026 bidet-ai contributors. Changed: strip HF OAuth (appAuthRedirectScheme + openid-appauth dep), add release signingConfig wired to CI secrets via env vars (RELEASE_KEYSTORE_PATH/PASS, RELEASE_KEY_ALIAS/PASS), bump applicationId to ai.bidet.phone, reset versionName to 0.1.0, register banWordCheck Gradle task hook, Phase 4A.1 strip firebase-bom + firebase-analytics + firebase-messaging deps + drop the now-unused google.services plugin alias (zero-telemetry hard rule). v0.3 (2026-05-10): replace whisper.cpp NDK + Whisper-tiny GGUF with Moonshine-Tiny ONNX + sherpa-onnx static-link AAR (Cactus prize narrative: small fast STT routed → Gemma 4 cleaning).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File as JavaFile
import java.net.URL as JavaURL
import java.security.MessageDigest

plugins {
  alias(libs.plugins.android.application)
  // bidet-ai Phase 4A.1: google-services plugin alias removed alongside the firebase-* deps
  // (zero-telemetry hard rule).
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.hilt.application)
  alias(libs.plugins.oss.licenses)
  alias(libs.plugins.ksp)
  kotlin("kapt")
}

// bidet-ai: banWordCheck — fails the build if banned tokens leak into user-visible code paths.
// Implementation lives in $rootDir/gradle/ban-word-check.gradle.kts.
apply(from = "$rootDir/gradle/ban-word-check.gradle.kts")

android {
  namespace = "com.google.ai.edge.gallery"
  compileSdk = 35

  defaultConfig {
    // bidet-ai: rebrand from upstream Gallery → Bidet AI for Android.
    applicationId = "ai.bidet.phone"
    minSdk = 31
    targetSdk = 35
    versionCode = 1
    versionName = "0.1.0"

    // bidet-ai: HuggingFace OAuth dep RESTORED 2026-05-08 (was stripped per brief, but
    // upstream Gallery's model-picker code references it). Phase 2 will strip the
    // consuming code along with the model picker; for now we keep both the dep and the
    // redirect-scheme placeholder so AndroidManifest compiles.
    manifestPlaceholders["appAuthRedirectScheme"] =
        "ai.bidet.phone.placeholder"
    manifestPlaceholders["applicationName"] = "com.google.ai.edge.gallery.GalleryApplication"
    manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // bidet-ai v0.3 (2026-05-10): whisper.cpp NDK build REPLACED with sherpa-onnx static-link
    // AAR vendored at app/libs/. The AAR ships only `libsherpa-onnx-jni.so` for arm64-v8a
    // (statically linked against ONNX Runtime 1.13.1) — no `libonnxruntime.so` collision risk
    // with LiteRT-LM's bundled ORT. Pixel 8 Pro is the only supported target for v0.x, so we
    // restrict to arm64-v8a to keep APK size minimal.
    ndk {
      abiFilters += "arm64-v8a"
    }
  }

  // v0.3 (2026-05-10): Moonshine + Gemma flavors. The "moonshine" flavor (renamed from
  // "whisper") wires the Moonshine-Tiny ONNX → sherpa-onnx fast-STT path; the "gemma" flavor
  // wires the integrated Gemma 4 audio encoder + cleaning path. Both APKs install side-by-side
  // on the same device with distinct applicationIds for A/B comparison. Differs only in the
  // transcription engine.
  flavorDimensions += "engine"
  productFlavors {
    create("moonshine") {
      dimension = "engine"
      applicationIdSuffix = ".moonshine"
      versionNameSuffix = "-moonshine"
      // Engine-name first to survive launcher truncation (FlavorBrandingTest pins this).
      resValue("string", "bidet_app_name_flavor", "Moonshine · Bidet")
      buildConfigField("boolean", "USE_GEMMA_AUDIO", "false")
    }
    create("gemma") {
      dimension = "engine"
      applicationIdSuffix = ".gemma"
      versionNameSuffix = "-gemma"
      resValue("string", "bidet_app_name_flavor", "Gemma · Bidet")
      buildConfigField("boolean", "USE_GEMMA_AUDIO", "true")
    }
  }

  // bidet-ai v0.3 (2026-05-10): NDK still pinned in case other native deps land in future,
  // but no externalNativeBuild block — the whisper.cpp CMake project was deleted. sherpa-onnx
  // ships its own prebuilt arm64-v8a .so inside the AAR, so AGP just packages it.
  ndkVersion = "27.0.12077973"

  // bidet-ai: release signingConfig populated from CI env vars (GitHub Secrets workflow
  // base64-decodes RELEASE_KEYSTORE_B64 → RELEASE_KEYSTORE_PATH then exports the
  // password/alias env vars). Locally, if the env vars are unset, we silently fall through to
  // debug signing so a developer's `assembleRelease` does not require the production keystore.
  //
  // 2026-05-11: debug signingConfig wired to a stable repo-committed keystore at
  // app/debug.keystore (NOT gitignored — intentional, debug-only). Without this AGP
  // auto-generates a fresh random keystore per build host, which means every CI APK
  // is signed with a different cert. That forces `adb uninstall` before each install
  // (losing the 2.4 GB on-device Gemma model + recording sessions). Mark hit exactly
  // this wall going v18.7 → v18.8. The committed keystore is DEBUG-ONLY — release
  // signing is unchanged and still keyed to GitHub Secrets above.
  signingConfigs {
    getByName("debug") {
      storeFile = file("debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
    create("release") {
      val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
      if (!keystorePath.isNullOrBlank()) {
        storeFile = file(keystorePath)
        storePassword = System.getenv("RELEASE_KEYSTORE_PASS")
        keyAlias = System.getenv("RELEASE_KEY_ALIAS")
        keyPassword = System.getenv("RELEASE_KEY_PASS")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig =
          if (!System.getenv("RELEASE_KEYSTORE_PATH").isNullOrBlank())
              signingConfigs.getByName("release")
          else signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
    freeCompilerArgs += "-Xcontext-receivers"
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }

  // 2026-05-09: returnDefaultValues so JVM unit tests can call android.util.Log without
  // the Android stub throwing "Method not mocked" RuntimeException. The
  // BidetSharedLiteRtEngineProvider's failure path logs via Log.e — without this flag the
  // failure-state test (BidetSharedLiteRtEnginePrewarmTest.ensureReadyImpl_failureThenRetry_…)
  // crashes on the Log call instead of asserting the state-flow transition. The flag returns
  // the JDK default for any unstubbed method (null/0/false), which is the right semantic for
  // "this is a JVM unit test and we don't care about Android-specific behaviour".
  testOptions {
    unitTests.isReturnDefaultValues = true
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.compose.navigation)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlin.reflect)
  implementation(libs.material.icon.extended)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.datastore)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.com.google.code.gson)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.security.crypto)
  // Restored 2026-05-08: brief stripped these but upstream Gallery's
  // ProjectConfig.kt + ModelManagerViewModel.kt + DownloadAndTryButton.kt
  // still reference them. Phase 2 strips the consuming code; for now keep
  // the deps so the build compiles.
  implementation(libs.openid.appauth)
  implementation(libs.androidx.browser)
  implementation(libs.androidx.webkit)
  implementation(libs.litertlm)
  implementation(libs.commonmark)
  implementation(libs.richtext)
  implementation(libs.tflite)
  implementation(libs.tflite.gpu)
  implementation(libs.tflite.support)
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  // bidet-ai: openid-appauth removed (Gemma 4 E4B is public/ungated).
  implementation(libs.androidx.splashscreen)
  implementation(libs.protobuf.javalite)
  implementation(libs.hilt.android)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.play.services.oss.licenses)
  // bidet-ai Phase 4A.1+4A.2: firebase-bom + firebase-analytics + firebase-messaging removed
  // (zero-telemetry hard rule). 4A.2 deleted the Analytics.kt stub + every upstream telemetry
  // call site; FcmMessagingService deleted (4A.1); manifest entries stripped (4A.1).
  // play-services-oss-licenses is NOT a Firebase dep so it stays.
  // bidet-ai: androidx-documentfile dep removed — the upstream
  // Gallery customtasks/agentchat/SkillManagerViewModel.kt that imported it has been
  // deleted alongside the rest of the customtasks/ tree.
  implementation(libs.androidx.exifinterface)
  implementation(libs.moshi.kotlin)
  // bidet-ai v0.3 (2026-05-10): sherpa-onnx replaces whisper.cpp. Vendored AAR ships
  // libsherpa-onnx-jni.so (statically linked ONNX Runtime 1.13.1) so there's no
  // libonnxruntime.so collision with LiteRT-LM's bundled ORT. Apache-2.0 licensed,
  // compatible with Bidet's Apache-2.0 + Gemma 4's Apache-2.0 + Moonshine's MIT licenses.
  // Inference call path: MoonshineEngine.kt → com.k2fsa.sherpa.onnx.OfflineRecognizer →
  // libsherpa-onnx-jni.so. Pulls Moonshine-Tiny v2 quantized ONNX bundle at build time
  // (see fetchMoonshineModel task below).
  implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.1.aar"))
  // bidet-ai: OkHttp for the optional debug Tp3Sender webhook POST. Phase 4A also uses
  // OkHttp for the dynamic Content-Length HEAD fetch in BidetModelProvider.
  implementation(libs.okhttp)
  // bidet-ai Phase 4A: Room for session persistence (saved brain-dumps + detail screen +
  // WAV export). Wired into Hilt via BidetDatabaseModule. Phase 4A keeps Room schemas
  // in-memory (no exportSchema=true) — the entity is small and not yet stable enough
  // to warrant export-and-version-bump churn during the 16-day pre-contest window.
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)
  kapt(libs.hilt.android.compiler)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  // bidet-ai 2026-05-08: real org.json for unit tests. Android stub's JSONObject
  // throws "Method not mocked" RuntimeException in JVM unit-test runtime;
  // DedupAlgorithmTest fixtures parse JSON, so we need the real artifact.
  testImplementation("org.json:json:20240303")
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.hilt.android.testing)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
  ksp(libs.moshi.kotlin.codegen)
  implementation(libs.mlkit.genai.prompt)
}

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:4.26.1" }
  generateProtoTasks { all().forEach { it.plugins { create("java") { option("lite") } } } }
}

// bidet-ai v0.3 (2026-05-10): fetch Moonshine-Tiny ONNX bundle at build time. The bundle's
// .ort + tokens.txt total ~44 MB extracted; gitignored. CI/local builds download once into
// the Gradle project cache, verify the tarball SHA-256, and extract into
// src/main/assets/moonshine/ before mergeAssets runs. Idempotent — the task is
// up-to-date when the marker file (sha256.ok) sits next to the unpacked assets.
//
// Bundle: sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27 (Moonshine v2 = encoder.ort +
// decoder_model_merged.ort, ~44 MB total). Smaller than Moonshine v1 (4 ONNX files, ~120 MB)
// and matches the OfflineMoonshineModelConfig v2 path in sherpa-onnx (encoder + mergedDecoder).
val moonshineModelUrl =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
        "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27.tar.bz2"
// SHA-256 of the tarball (verified 2026-05-10 on G16 cache).
val moonshineModelSha256 =
    "9ec31b342d8fa3240c3b81b8f82e1cf7e3ac467c93ca5a999b741d5887164f8d"
val moonshineAssetsDir = layout.projectDirectory.dir(
    "src/main/assets/moonshine"
).asFile

abstract class FetchMoonshineModelTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val outDir = outputDir.get().asFile
        outDir.mkdirs()
        val marker = JavaFile(outDir, ".sha256.ok")
        val expected = expectedSha256.get()

        // Up-to-date check: marker file present AND contents match expected SHA.
        if (marker.exists() && marker.readText().trim().equals(expected, ignoreCase = true)) {
            // Plus check that the two .ort files actually exist (defensive against partial
            // extraction).
            val encoder = JavaFile(outDir, "encoder_model.ort")
            val decoder = JavaFile(outDir, "decoder_model_merged.ort")
            val tokens = JavaFile(outDir, "tokens.txt")
            if (encoder.isFile && encoder.length() > 0 && decoder.isFile &&
                decoder.length() > 0 && tokens.isFile && tokens.length() > 0) {
                logger.lifecycle("Moonshine bundle already present + verified: ${outDir.absolutePath}")
                return
            } else {
                logger.warn("Moonshine marker present but assets missing — re-extracting.")
            }
        }

        // Download tarball into a tmp file, verify SHA, then extract.
        val tmp = JavaFile(outDir.parentFile, "moonshine-bundle.tar.bz2.tmp")
        logger.lifecycle("Downloading Moonshine bundle from ${url.get()} → ${tmp.absolutePath}")
        try {
            JavaURL(url.get()).openStream().use { input ->
                tmp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            val actual = computeSha256(tmp)
            if (!actual.equals(expected, ignoreCase = true)) {
                tmp.delete()
                throw GradleException(
                    "SHA-256 mismatch for downloaded Moonshine bundle. " +
                        "Expected $expected, got $actual."
                )
            }

            // Wipe just the gitignored payload files (NOT README.md / .gitignore which we
            // commit), then extract the tarball into the asset dir. We flatten the bundle's
            // top-level "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27/" directory away
            // so MoonshineEngine.kt can reference assets/moonshine/<file>.
            JavaFile(outDir, "encoder_model.ort").delete()
            JavaFile(outDir, "decoder_model_merged.ort").delete()
            JavaFile(outDir, "tokens.txt").delete()
            JavaFile(outDir, "LICENSE").delete()
            JavaFile(outDir, "test_wavs").deleteRecursively()
            outDir.mkdirs()
            extractTarBz2(tmp, outDir)
            tmp.delete()

            // Drop test_wavs from the asset dir — we don't ship those into the APK.
            JavaFile(outDir, "test_wavs").deleteRecursively()

            // Write marker.
            marker.writeText(expected)
            logger.lifecycle("Moonshine bundle fetched + extracted into ${outDir.absolutePath}")
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    /**
     * Extract a .tar.bz2 archive by shelling out to the `tar` binary (universally available
     * on Linux + macOS CI runners + dev machines; Windows isn't a build host for this
     * project). bzip2 decompression happens transparently via `-xjf`. `--strip-components=1`
     * flattens the single top-level directory the sherpa-onnx tarball ships with.
     */
    private fun extractTarBz2(tarBz2: JavaFile, destDir: JavaFile) {
        val proc = ProcessBuilder(
            "tar", "-xjf", tarBz2.absolutePath,
            "-C", destDir.absolutePath,
            "--strip-components=1",
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        if (exit != 0) {
            throw GradleException(
                "tar exited with code $exit while extracting ${tarBz2.absolutePath}: $output"
            )
        }
    }

    private fun computeSha256(file: JavaFile): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

val fetchMoonshineModel = tasks.register<FetchMoonshineModelTask>("fetchMoonshineModel") {
    group = "bidet"
    description = "Download + verify Moonshine-Tiny v2 quantized ONNX bundle into assets/moonshine/."
    url.set(moonshineModelUrl)
    expectedSha256.set(moonshineModelSha256)
    outputDir.set(layout.projectDirectory.dir("src/main/assets/moonshine"))
}

// Hook into the asset-merge step so the bundle lands in src/main/assets/moonshine/ before
// AGP reads it. Mirror of the same flavor-aware regex match used for fetchWhisperModel
// (2026-05-09): merge<Flavor><BuildType>Assets needs the dep too, not just the unflavored
// task name. The Gemma flavor doesn't *use* the Moonshine assets (its USE_GEMMA_AUDIO flag
// routes around them) — but they're still packaged into both flavor APKs because the
// fetch happens before flavor-specific packaging splits it. Net cost on Gemma flavor:
// ~44 MB asset overhead. Acceptable for now; if APK budget becomes tight, future work
// is to gate the asset packaging via packagingOptions per-flavor.
afterEvaluate {
    val pattern = Regex("(merge|generate).*Assets")
    tasks.matching { pattern.matches(it.name) }
        .configureEach { dependsOn(fetchMoonshineModel) }
}
