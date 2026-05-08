/*
 * Copyright 2025 Google LLC
 * Modifications Copyright 2026 bidet-ai contributors. Changed: strip HF OAuth (appAuthRedirectScheme + openid-appauth dep), add release signingConfig wired to CI secrets via env vars (RELEASE_KEYSTORE_PATH/PASS, RELEASE_KEY_ALIAS/PASS), bump applicationId to ai.bidet.phone, reset versionName to 0.1.0, register banWordCheck Gradle task hook, add fetchWhisperModel task hooked into mergeAssets for Phase 3 build-time Whisper-tiny fetch, Phase 4A.1 strip firebase-bom + firebase-analytics + firebase-messaging deps + drop the now-unused google.services plugin alias (zero-telemetry hard rule).
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
  }

  // bidet-ai: release signingConfig populated from CI env vars (GitHub Secrets workflow
  // base64-decodes RELEASE_KEYSTORE_B64 → RELEASE_KEYSTORE_PATH then exports the
  // password/alias env vars). Locally, if the env vars are unset, we silently fall through to
  // debug signing so a developer's `assembleRelease` does not require the production keystore.
  signingConfigs {
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
  // bidet-ai Phase 4A.1: firebase-bom + firebase-analytics + firebase-messaging removed
  // (zero-telemetry hard rule). Analytics.kt is gutted to a no-op handle, FcmMessagingService
  // deleted, manifest entries stripped. play-services-oss-licenses is NOT a Firebase dep so
  // it stays.
  implementation(libs.androidx.exifinterface)
  implementation(libs.moshi.kotlin)
  // bidet-ai: Whisper.cpp Android JNI bindings for ASR.
  // Choice rationale: see UPSTREAM_WHISPER.md (whisper-jni 1.7.1, official ggml-tiny.en model).
  implementation(libs.whisper.jni)
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

// bidet-ai Phase 3: fetch the Whisper-tiny.en weights at build time. The .bin file is
// gitignored (75 MB binary); CI fetches on every build and caches the result. The fetch is
// idempotent — once the file exists with a matching SHA-256 the task is up-to-date.
//
// Wire-in: a manual `dependsOn` on mergeDebugAssets / mergeReleaseAssets ensures the asset
// is in place before APK assembly. We do NOT hook into `assembleDebug` directly (that bug
// hit Phase 1's banWordCheck — see CI workflow comment).
val whisperModelUrl =
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin"
// SHA-256 from the HuggingFace API (model file's LFS oid). Verified 2026-05-08:
// https://huggingface.co/api/models/ggerganov/whisper.cpp/tree/main → ggml-tiny.en.bin
val whisperModelSha256 =
    "921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f"
val whisperModelFile = layout.projectDirectory.file(
    "src/main/assets/whisper/ggml-tiny.en.bin"
).asFile

abstract class FetchWhisperModelTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun fetch() {
        val out = outputFile.get().asFile
        out.parentFile.mkdirs()

        // Up-to-date check: file exists AND SHA-256 matches.
        if (out.exists() && out.length() > 0) {
            val actual = computeSha256(out)
            if (actual.equals(expectedSha256.get(), ignoreCase = true)) {
                logger.lifecycle("Whisper model already present + verified: ${out.absolutePath}")
                return
            } else {
                logger.warn(
                    "Whisper model SHA mismatch (expected ${expectedSha256.get()}, got $actual). " +
                        "Re-downloading."
                )
                out.delete()
            }
        }

        logger.lifecycle("Downloading Whisper-tiny.en model from ${url.get()} → ${out.absolutePath}")
        val tmp = JavaFile(out.parentFile, out.name + ".tmp")
        try {
            JavaURL(url.get()).openStream().use { input ->
                tmp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            val actual = computeSha256(tmp)
            if (!actual.equals(expectedSha256.get(), ignoreCase = true)) {
                tmp.delete()
                throw GradleException(
                    "SHA-256 mismatch for downloaded Whisper model. " +
                        "Expected ${expectedSha256.get()}, got $actual."
                )
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) {
                throw GradleException(
                    "Failed to rename ${tmp.absolutePath} to ${out.absolutePath}"
                )
            }
            logger.lifecycle("Whisper model fetched + verified: ${out.absolutePath}")
        } catch (t: Throwable) {
            tmp.delete()
            throw t
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

val fetchWhisperModel = tasks.register<FetchWhisperModelTask>("fetchWhisperModel") {
    group = "bidet"
    description = "Download + verify Whisper-tiny.en model weights into assets/whisper/."
    url.set(whisperModelUrl)
    expectedSha256.set(whisperModelSha256)
    outputFile.set(whisperModelFile)
}

// Hook into the asset-merge step so the .bin lands in src/main/assets/whisper/ before AGP
// reads it. Phase 1 lesson: this is the legitimate kind of task hook (asset-pipeline
// prerequisite, not a verification gate stapled onto assembleDebug).
afterEvaluate {
    listOf(
        "mergeDebugAssets",
        "mergeReleaseAssets",
        "generateDebugAssets",
        "generateReleaseAssets",
    ).forEach { taskName ->
        tasks.findByName(taskName)?.dependsOn(fetchWhisperModel)
    }
}
