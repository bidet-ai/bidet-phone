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

package com.google.ai.edge.gallery.bidet.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM smoke test that the bundled OpenDyslexic font assets exist on disk in
 * `src/main/res/font/` and have valid OTF magic bytes.
 *
 * Why not a Compose `FontFamily(...)` instantiation test:
 *   `androidx.compose.ui.text.font.Font(R.font.opendyslexic_regular)` constructs a
 *   `ResourceFont` whose loader needs an Android Context to resolve `R.font.*`. That requires
 *   Robolectric, which the bidet test suite has explicitly rejected in
 *   [com.google.ai.edge.gallery.bidet.service.EngineInitGateTest]'s docstring (~30s CI
 *   penalty + every `@HiltViewModel`-adjacent file would need its own runner annotation).
 *
 * What this test guards against instead — the realistic failure modes:
 *   1. Someone deletes one of the .otf files. Build still passes (R.font references the
 *      resource by name, not by content), but the app crashes on first Clean-tab render.
 *   2. Someone replaces the .otf with a corrupt binary (e.g. a half-finished download).
 *      Same crash mode.
 *   3. Someone renames a file (e.g. `opendyslexic-regular.otf` with a hyphen) — Android
 *      `res/font/` only accepts `[a-z0-9_]` so this is a build break, but the failure mode
 *      is unhelpful at build time. Catching it here gives a clearer signal.
 *
 * The font *family* is constructed in [com.google.ai.edge.gallery.ui.theme.openDyslexicFontFamily];
 * if any of those `Font(R.font.*)` references cease to resolve, the Android build fails with
 * "resource not found" — which is louder than a runtime NPE.
 */
class OpenDyslexicFontAssetTest {

    /** OpenType / CFF font magic bytes — `OTTO` (0x4F 0x54 0x54 0x4F). */
    private val OTF_MAGIC = byteArrayOf(0x4F, 0x54, 0x54, 0x4F)

    private fun fontDir(): File {
        // The test runs from `Android/src/app/` (Gradle module dir). The font files live at
        // `src/main/res/font/`. Walk up to find the module root if needed.
        val candidates = listOf(
            File("src/main/res/font"),
            File("Android/src/app/src/main/res/font"),
            File("app/src/main/res/font"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error(
                "OpenDyslexic font directory not found. Tried: " +
                    candidates.joinToString { it.absolutePath }
            )
    }

    @Test
    fun all_three_otf_assets_are_present() {
        val dir = fontDir()
        val expected = listOf(
            "opendyslexic_regular.otf",
            "opendyslexic_bold.otf",
            "opendyslexic_italic.otf",
        )
        for (name in expected) {
            val f = File(dir, name)
            assertTrue("Missing bundled font asset: $name", f.isFile)
            assertTrue(
                "Font file is suspiciously small (<10KB): $name (${f.length()}b)",
                f.length() > 10_000L,
            )
        }
    }

    @Test
    fun all_three_otf_files_have_otf_magic_bytes() {
        val dir = fontDir()
        for (name in listOf(
            "opendyslexic_regular.otf",
            "opendyslexic_bold.otf",
            "opendyslexic_italic.otf",
        )) {
            val f = File(dir, name)
            val header = f.inputStream().use { it.readNBytes(4) }
            assertEquals(
                "Wrong magic bytes for $name — expected OTF (OTTO), got ${header.toHex()}",
                OTF_MAGIC.toHex(),
                header.toHex(),
            )
        }
    }

    @Test
    fun ofl_license_is_bundled_alongside_fonts() {
        // Tests run from `Android/src/app/`. Walk up: app → src → Android → repo root.
        // Try a couple of candidate working directories so a future `./gradlew test` from a
        // different cwd still finds the license.
        val candidates = listOf(
            File("../../../third_party/opendyslexic/OFL.txt"), // from Android/src/app
            File("third_party/opendyslexic/OFL.txt"),          // from repo root
            File("../../../../third_party/opendyslexic/OFL.txt"),
            File("../../../../../third_party/opendyslexic/OFL.txt"),
        )
        val ofl = candidates.firstOrNull { it.isFile }
        assertNotNull(
            "OFL license must be bundled with the OpenDyslexic font (SIL Open Font License v1.1). " +
                "Looked in: ${candidates.joinToString { it.absolutePath }}",
            ofl,
        )
        val text = ofl!!.readText()
        assertTrue("OFL.txt missing 'OpenDyslexic' attribution", text.contains("OpenDyslexic"))
        assertTrue(
            "OFL.txt missing SIL Open Font License header",
            text.contains("SIL OPEN FONT LICENSE", ignoreCase = true),
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
}
