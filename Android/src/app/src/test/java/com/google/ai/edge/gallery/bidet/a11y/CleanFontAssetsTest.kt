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
 * Pure-JVM smoke test that all three Clean-tab font families bundled by the v0.3 picker exist
 * on disk in `src/main/res/font/`, have valid font magic bytes, and ship with their SIL OFL
 * license bundles in `third_party/<font>/`.
 *
 * Why not a Compose `FontFamily(...)` instantiation test:
 *   `androidx.compose.ui.text.font.Font(R.font.atkinson_hyperlegible_regular)` constructs a
 *   `ResourceFont` whose loader needs an Android Context to resolve `R.font.*`. That requires
 *   Robolectric, which the bidet test suite has explicitly rejected in
 *   [com.google.ai.edge.gallery.bidet.service.EngineInitGateTest]'s docstring (~30s CI
 *   penalty + every `@HiltViewModel`-adjacent file would need its own runner annotation).
 *
 * What this test guards against — the realistic failure modes:
 *   1. Someone deletes one of the .ttf / .otf files. Build still passes (R.font references
 *      the resource by name, not by content), but the app crashes on first Clean-tab render.
 *   2. Someone replaces a font with a corrupt binary (e.g. a half-finished download). Same
 *      crash mode.
 *   3. Someone renames a file (e.g. `atkinson-hyperlegible-regular.ttf` with hyphens) —
 *      Android `res/font/` only accepts `[a-z0-9_]` so this is a build break, but catching
 *      it here gives a clearer signal at test time.
 *   4. Someone forgets to bundle an OFL.txt for one of the fonts — that would silently
 *      violate the SIL OFL requirement that the license travel with the font.
 *
 * The font *families* are constructed in `Type.kt`; if any of those `Font(R.font.*)`
 * references cease to resolve, the Android build fails with "resource not found".
 */
class CleanFontAssetsTest {

    /** OpenType / CFF font magic bytes — `OTTO` (0x4F 0x54 0x54 0x4F). */
    private val OTF_MAGIC = byteArrayOf(0x4F, 0x54, 0x54, 0x4F)

    /** TrueType font magic bytes — `\x00\x01\x00\x00`. */
    private val TTF_MAGIC = byteArrayOf(0x00, 0x01, 0x00, 0x00)

    private fun fontDir(): File {
        // The test runs from `Android/src/app/` (Gradle module dir). The font files live at
        // `src/main/res/font/`. Walk a couple of plausible cwd's so a future `./gradlew test`
        // from a different cwd still finds them.
        val candidates = listOf(
            File("src/main/res/font"),
            File("Android/src/app/src/main/res/font"),
            File("app/src/main/res/font"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error(
                "Font directory not found. Tried: " + candidates.joinToString { it.absolutePath },
            )
    }

    private fun thirdPartyDir(name: String): File {
        // From Android/src/app: ../../../third_party/<name>
        // From repo root:        third_party/<name>
        val candidates = listOf(
            File("../../../third_party/$name"),
            File("third_party/$name"),
            File("../../../../third_party/$name"),
            File("../../../../../third_party/$name"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error(
                "third_party/$name not found. Tried: " +
                    candidates.joinToString { it.absolutePath },
            )
    }

    /** A bundled font asset: filename + the magic-bytes signature it should start with. */
    private data class Asset(val fileName: String, val magic: ByteArray)

    private val atkinsonAssets = listOf(
        Asset("atkinson_hyperlegible_regular.ttf", TTF_MAGIC),
        Asset("atkinson_hyperlegible_bold.ttf", TTF_MAGIC),
        Asset("atkinson_hyperlegible_italic.ttf", TTF_MAGIC),
    )

    private val openDyslexicAssets = listOf(
        Asset("opendyslexic_regular.otf", OTF_MAGIC),
        Asset("opendyslexic_bold.otf", OTF_MAGIC),
        Asset("opendyslexic_italic.otf", OTF_MAGIC),
    )

    private val andikaAssets = listOf(
        Asset("andika_regular.ttf", TTF_MAGIC),
        Asset("andika_bold.ttf", TTF_MAGIC),
        Asset("andika_italic.ttf", TTF_MAGIC),
    )

    private fun assertAssetPresentAndValid(asset: Asset) {
        val dir = fontDir()
        val f = File(dir, asset.fileName)
        assertTrue("Missing bundled font asset: ${asset.fileName}", f.isFile)
        assertTrue(
            "Font file is suspiciously small (<10KB): ${asset.fileName} (${f.length()}b)",
            f.length() > 10_000L,
        )
        val header = f.inputStream().use { it.readNBytes(4) }
        assertEquals(
            "Wrong magic bytes for ${asset.fileName} — expected ${asset.magic.toHex()}, " +
                "got ${header.toHex()}",
            asset.magic.toHex(),
            header.toHex(),
        )
    }

    // -------------------------------------------------------------------------------------
    // Per-font asset presence + magic-bytes
    // -------------------------------------------------------------------------------------

    @Test fun atkinson_assets_arePresentAndValid() {
        atkinsonAssets.forEach { assertAssetPresentAndValid(it) }
    }

    @Test fun openDyslexic_assets_arePresentAndValid() {
        openDyslexicAssets.forEach { assertAssetPresentAndValid(it) }
    }

    @Test fun andika_assets_arePresentAndValid() {
        andikaAssets.forEach { assertAssetPresentAndValid(it) }
    }

    // -------------------------------------------------------------------------------------
    // OFL license bundles — required for SIL OFL compliance
    // -------------------------------------------------------------------------------------

    /**
     * Assert that an OFL bundle is present and looks like a real SIL OFL license file. We accept
     * any of [acceptableAttributions] in the body — the upstream OFL.txt files vary on whether
     * they include the Reserved Font Name vs. only the foundry's copyright (Atkinson's upstream
     * OFL.txt only names the Braille Institute, for instance), so we look for any signal that
     * uniquely identifies the right font family's license file.
     */
    private fun assertOflBundle(thirdPartyName: String, vararg acceptableAttributions: String) {
        val dir = thirdPartyDir(thirdPartyName)
        val ofl = File(dir, "OFL.txt")
        assertNotNull("OFL.txt missing for third_party/$thirdPartyName", ofl)
        assertTrue(
            "OFL.txt missing for third_party/$thirdPartyName at ${ofl.absolutePath}",
            ofl.isFile,
        )
        val text = ofl.readText()
        val matched = acceptableAttributions.any { text.contains(it, ignoreCase = true) }
        assertTrue(
            "third_party/$thirdPartyName/OFL.txt does not contain any of " +
                acceptableAttributions.joinToString { "'$it'" } +
                " — license bundle may be wrong file",
            matched,
        )
        assertTrue(
            "third_party/$thirdPartyName/OFL.txt missing SIL Open Font License header",
            text.contains("SIL OPEN FONT LICENSE", ignoreCase = true),
        )
    }

    @Test fun atkinson_oflLicense_isBundled() {
        // Upstream Atkinson OFL.txt names only the Braille Institute on the copyright line.
        assertOflBundle("atkinson_hyperlegible", "Atkinson Hyperlegible", "Braille Institute")
    }

    @Test fun openDyslexic_oflLicense_isBundled() {
        assertOflBundle("opendyslexic", "OpenDyslexic")
    }

    @Test fun andika_oflLicense_isBundled() {
        // Andika's OFL names "Andika" (and "SIL") on the Reserved Font Name line.
        assertOflBundle("andika", "Andika")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
}
