/*
 * Copyright 2025 Google LLC
 * Modifications Copyright 2026 bidet-ai contributors. Changed: strip HF OAuth (appAuthRedirectScheme + openid-appauth dep), add release signingConfig wired to CI secrets via env vars (RELEASE_KEYSTORE_PATH/PASS, RELEASE_KEY_ALIAS/PASS), bump applicationId to ai.bidet.phone, reset versionName to 0.1.0, register banWordCheck Gradle task hook.
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

plugins {
  alias(libs.plugins.android.application)
  // Note: set apply to true to enable google-services (requires google-services.json).
  alias(libs.plugins.google.services) apply false
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

    // bidet-ai: HuggingFace OAuth stripped — Gemma 4 E4B LiteRT-LM artifact is public/ungated.
    // (The appAuthRedirectScheme manifestPlaceholder was removed; openid-appauth dep also
    // removed in libs.versions.toml.)
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
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.analytics)
  implementation(libs.firebase.messaging)
  implementation(libs.androidx.exifinterface)
  implementation(libs.moshi.kotlin)
  // bidet-ai: Whisper.cpp Android JNI bindings for ASR.
  // Choice rationale: see UPSTREAM_WHISPER.md (whisper-jni 1.7.1, official ggml-tiny.en model).
  implementation(libs.whisper.jni)
  // bidet-ai: OkHttp for the optional debug Tp3Sender webhook POST.
  implementation(libs.okhttp)
  kapt(libs.hilt.android.compiler)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
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
