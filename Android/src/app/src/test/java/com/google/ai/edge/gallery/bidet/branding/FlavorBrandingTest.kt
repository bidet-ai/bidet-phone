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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM unit test that pins the per-flavor launcher branding so a future hand can't
 * silently regress Mark's unified-label spec.
 *
 * The labels live in `app/build.gradle.kts` as `resValue("string", "bidet_app_name_flavor", ...)`
 * declarations under each productFlavor. Reading the build script as text is the simplest way
 * to assert the contract without standing up an instrumented Android Context.
 *
 * History
 * -------
 *  - v0.3 (2026-05-10): the "whisper" flavor was renamed to "moonshine" when the on-device
 *    STT engine moved from Whisper-tiny + whisper.cpp to Moonshine-Tiny + sherpa-onnx.
 *  - v21 (2026-05-13): unified the per-flavor labels to a single "Bidet AI" string. The
 *    engine-first labels ("Moonshine · Bidet" / "Gemma · Bidet") were a launcher-truncation
 *    workaround that the shorter unified label no longer needs. The per-flavor adaptive-icon
 *    artwork was also unified onto a single brain-over-water bitmap (sourced from
 *    /mnt/c/Users/Breez/Downloads/BidetAi logo 1.png). Only the gemma flavor ships, but the
 *    moonshine flavor matches so a debug sideload reads identically.
 *
 * Invariants asserted (v21):
 *   - both flavors declare bidet_app_name_flavor
 *   - both flavors use exactly the string "Bidet AI"
 *   - per-flavor adaptive icon resources exist for both square and round masks
 *   - per-flavor background drawables use the documented soft-blue hex code that matches the
 *     brain-icon's water-splash hue
 *   - the foreground drawables redirect to the main mipmap brain bitmap
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
    fun moonshineFlavor_declaresBidetAiLabel() {
        val match = MOONSHINE_RESVALUE_PATTERN.find(buildScript)
        assertNotNull("moonshine flavor must declare bidet_app_name_flavor resValue", match)
        val label = match!!.groupValues[1]
        assertEquals(
            "moonshine label must be unified 'Bidet AI' (v21 Mark spec 2026-05-13)",
            "Bidet AI",
            label,
        )
    }

    @Test
    fun gemmaFlavor_declaresBidetAiLabel() {
        val match = GEMMA_RESVALUE_PATTERN.find(buildScript)
        assertNotNull("gemma flavor must declare bidet_app_name_flavor resValue", match)
        val label = match!!.groupValues[1]
        assertEquals(
            "gemma label must be unified 'Bidet AI' (v21 Mark spec 2026-05-13)",
            "Bidet AI",
            label,
        )
    }

    @Test
    fun flavorLabels_areUnified() {
        val moonshineLabel = MOONSHINE_RESVALUE_PATTERN.find(buildScript)?.groupValues?.get(1)
        val gemmaLabel = GEMMA_RESVALUE_PATTERN.find(buildScript)?.groupValues?.get(1)
        assertNotNull(moonshineLabel)
        assertNotNull(gemmaLabel)
        assertEquals(
            "v21 (2026-05-13): both flavors must show the unified 'Bidet AI' launcher label",
            moonshineLabel,
            gemmaLabel,
        )
    }

    @Test
    fun gemmaFlavor_hasAdaptiveIconWithSoftBlueBackground() {
        val resDir = File(moduleDir, "src/gemma/res")
        assertTrue("gemma flavor res dir must exist", resDir.isDirectory)

        val launcherSquare = File(resDir, "mipmap-anydpi-v26/ic_launcher.xml")
        val launcherRound = File(resDir, "mipmap-anydpi-v26/ic_launcher_round.xml")
        assertTrue("gemma square adaptive icon missing", launcherSquare.isFile)
        assertTrue("gemma round adaptive icon missing", launcherRound.isFile)

        val backgroundDrawable = File(resDir, "drawable/ic_launcher_background_flavor.xml")
        assertTrue("gemma background drawable missing", backgroundDrawable.isFile)
        assertTrue(
            "gemma background must use soft blue (#FFE6F0FA) matching the brain-icon water splash",
            backgroundDrawable.readText().contains("#FFE6F0FA", ignoreCase = true),
        )

        val foreground = File(resDir, "drawable/ic_launcher_foreground_letter.xml")
        assertTrue("gemma foreground drawable missing", foreground.isFile)
        assertTrue(
            "gemma foreground must redirect to @mipmap/ic_launcher_foreground (brain bitmap)",
            foreground.readText().contains("@mipmap/ic_launcher_foreground"),
        )
    }

    @Test
    fun moonshineFlavor_hasAdaptiveIconWithSoftBlueBackground() {
        val resDir = File(moduleDir, "src/moonshine/res")
        assertTrue("moonshine flavor res dir must exist", resDir.isDirectory)

        val launcherSquare = File(resDir, "mipmap-anydpi-v26/ic_launcher.xml")
        val launcherRound = File(resDir, "mipmap-anydpi-v26/ic_launcher_round.xml")
        assertTrue("moonshine square adaptive icon missing", launcherSquare.isFile)
        assertTrue("moonshine round adaptive icon missing", launcherRound.isFile)

        val backgroundDrawable = File(resDir, "drawable/ic_launcher_background_flavor.xml")
        assertTrue("moonshine background drawable missing", backgroundDrawable.isFile)
        assertTrue(
            "moonshine background must use soft blue (#FFE6F0FA) matching the brain-icon water splash",
            backgroundDrawable.readText().contains("#FFE6F0FA", ignoreCase = true),
        )

        val foreground = File(resDir, "drawable/ic_launcher_foreground_letter.xml")
        assertTrue("moonshine foreground drawable missing", foreground.isFile)
        assertTrue(
            "moonshine foreground must redirect to @mipmap/ic_launcher_foreground (brain bitmap)",
            foreground.readText().contains("@mipmap/ic_launcher_foreground"),
        )
    }

    @Test
    fun adaptiveIcons_referenceFlavorBackgroundAndForegroundDrawables() {
        val flavors = listOf("gemma", "moonshine")
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
        private val MOONSHINE_RESVALUE_PATTERN = Regex(
            """create\("moonshine"\)\s*\{[^}]*?resValue\(\s*"string"\s*,\s*"bidet_app_name_flavor"\s*,\s*"([^"]+)"\s*\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val GEMMA_RESVALUE_PATTERN = Regex(
            """create\("gemma"\)\s*\{[^}]*?resValue\(\s*"string"\s*,\s*"bidet_app_name_flavor"\s*,\s*"([^"]+)"\s*\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
