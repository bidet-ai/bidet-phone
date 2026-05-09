/*
 * Copyright 2026 bidet-ai contributors
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

package com.google.ai.edge.gallery.bidet.branding

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM unit test that pins the per-flavor launcher branding so a future hand can't
 * silently regress the launcher-truncation fix.
 *
 * The labels live in `app/build.gradle.kts` as `resValue("string", "bidet_app_name_flavor", ...)`
 * declarations under each productFlavor. Reading the build script as text is the simplest way
 * to assert the contract without standing up an instrumented Android Context.
 *
 * Invariants asserted:
 *   - both flavors declare bidet_app_name_flavor
 *   - the engine name comes BEFORE the brand name (so "Whisper…" or "Gemma…" is what
 *     survives launcher truncation, not "Bidet…")
 *   - the two flavor labels are not equal
 *   - per-flavor adaptive icon resources exist for both square and round masks
 *   - per-flavor background drawables use the documented Material hex codes
 */
class FlavorBrandingTest {

    private val moduleDir: File by lazy {
        // Tests run with cwd = Android/src/app under Gradle. Search a small set of relative
        // candidates in case the runner invokes from somewhere else (IDE, root project).
        val candidates = listOf(
            File("build.gradle.kts"),
            File("app/build.gradle.kts"),
            File("../build.gradle.kts"),
        )
        val matched = candidates.first { it.exists() }.absoluteFile
        matched.parentFile ?: error("build.gradle.kts has no parent dir: $matched")
    }

    private val buildScript: String by lazy {
        File(moduleDir, "build.gradle.kts").readText()
    }

    @Test
    fun whisperFlavor_declaresEngineFirstLabel() {
        val match = WHISPER_RESVALUE_PATTERN.find(buildScript)
        assertNotNull("whisper flavor must declare bidet_app_name_flavor resValue", match)
        val label = match!!.groupValues[1]
        assertTrue(
            "whisper label must lead with 'Whisper' so it survives launcher truncation, was: '$label'",
            label.startsWith("Whisper"),
        )
        assertTrue(
            "whisper label must still mention Bidet for brand recognition, was: '$label'",
            label.contains("Bidet"),
        )
    }

    @Test
    fun gemmaFlavor_declaresEngineFirstLabel() {
        val match = GEMMA_RESVALUE_PATTERN.find(buildScript)
        assertNotNull("gemma flavor must declare bidet_app_name_flavor resValue", match)
        val label = match!!.groupValues[1]
        assertTrue(
            "gemma label must lead with 'Gemma' so it survives launcher truncation, was: '$label'",
            label.startsWith("Gemma"),
        )
        assertTrue(
            "gemma label must still mention Bidet for brand recognition, was: '$label'",
            label.contains("Bidet"),
        )
    }

    @Test
    fun flavorLabels_areDistinct() {
        val whisperLabel = WHISPER_RESVALUE_PATTERN.find(buildScript)?.groupValues?.get(1)
        val gemmaLabel = GEMMA_RESVALUE_PATTERN.find(buildScript)?.groupValues?.get(1)
        assertNotNull(whisperLabel)
        assertNotNull(gemmaLabel)
        assertNotEquals(
            "whisper and gemma flavors must show different launcher labels",
            whisperLabel,
            gemmaLabel,
        )
    }

    @Test
    fun gemmaFlavor_hasAdaptiveIconWithMaterialGreenBackground() {
        val resDir = File(moduleDir, "src/gemma/res")
        assertTrue("gemma flavor res dir must exist", resDir.isDirectory)

        val launcherSquare = File(resDir, "mipmap-anydpi-v26/ic_launcher.xml")
        val launcherRound = File(resDir, "mipmap-anydpi-v26/ic_launcher_round.xml")
        assertTrue("gemma square adaptive icon missing", launcherSquare.isFile)
        assertTrue("gemma round adaptive icon missing", launcherRound.isFile)

        val backgroundDrawable = File(resDir, "drawable/ic_launcher_background_flavor.xml")
        assertTrue("gemma background drawable missing", backgroundDrawable.isFile)
        assertTrue(
            "gemma background must use Material Green 700 (#388E3C)",
            backgroundDrawable.readText().contains("#388E3C", ignoreCase = true),
        )

        val foreground = File(resDir, "drawable/ic_launcher_foreground_letter.xml")
        assertTrue("gemma foreground letter drawable missing", foreground.isFile)
    }

    @Test
    fun whisperFlavor_hasAdaptiveIconWithMaterialBlueBackground() {
        val resDir = File(moduleDir, "src/whisper/res")
        assertTrue("whisper flavor res dir must exist", resDir.isDirectory)

        val launcherSquare = File(resDir, "mipmap-anydpi-v26/ic_launcher.xml")
        val launcherRound = File(resDir, "mipmap-anydpi-v26/ic_launcher_round.xml")
        assertTrue("whisper square adaptive icon missing", launcherSquare.isFile)
        assertTrue("whisper round adaptive icon missing", launcherRound.isFile)

        val backgroundDrawable = File(resDir, "drawable/ic_launcher_background_flavor.xml")
        assertTrue("whisper background drawable missing", backgroundDrawable.isFile)
        assertTrue(
            "whisper background must use Material Blue 700 (#1976D2)",
            backgroundDrawable.readText().contains("#1976D2", ignoreCase = true),
        )

        val foreground = File(resDir, "drawable/ic_launcher_foreground_letter.xml")
        assertTrue("whisper foreground letter drawable missing", foreground.isFile)
    }

    @Test
    fun adaptiveIcons_referenceFlavorBackgroundAndForegroundDrawables() {
        val flavors = listOf("gemma", "whisper")
        val variants = listOf("ic_launcher.xml", "ic_launcher_round.xml")
        flavors.forEach { flavor ->
            variants.forEach { variant ->
                val xml = File(moduleDir, "src/$flavor/res/mipmap-anydpi-v26/$variant").readText()
                assertTrue(
                    "$flavor/$variant must reference @drawable/ic_launcher_background_flavor",
                    xml.contains("@drawable/ic_launcher_background_flavor"),
                )
                assertTrue(
                    "$flavor/$variant must reference @drawable/ic_launcher_foreground_letter",
                    xml.contains("@drawable/ic_launcher_foreground_letter"),
                )
            }
        }
    }

    companion object {
        private val WHISPER_RESVALUE_PATTERN = Regex(
            """create\("whisper"\)\s*\{[^}]*?resValue\(\s*"string"\s*,\s*"bidet_app_name_flavor"\s*,\s*"([^"]+)"\s*\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val GEMMA_RESVALUE_PATTERN = Regex(
            """create\("gemma"\)\s*\{[^}]*?resValue\(\s*"string"\s*,\s*"bidet_app_name_flavor"\s*,\s*"([^"]+)"\s*\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
