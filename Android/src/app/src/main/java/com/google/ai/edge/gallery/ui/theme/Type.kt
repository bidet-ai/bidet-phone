/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R

val appFontFamily =
  FontFamily(
    Font(R.font.nunito_regular, FontWeight.Normal),
    Font(R.font.nunito_extralight, FontWeight.ExtraLight),
    Font(R.font.nunito_light, FontWeight.Light),
    Font(R.font.nunito_medium, FontWeight.Medium),
    Font(R.font.nunito_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_bold, FontWeight.Bold),
    Font(R.font.nunito_extrabold, FontWeight.ExtraBold),
    Font(R.font.nunito_black, FontWeight.Black),
  )

val baseline = Typography()

// bidet-ai a11y (2026-05-10): three bundled FontFamilies for the v0.3 Clean-tab font picker.
// All three are SIL Open Font License v1.1; license texts live in `third_party/<font>/OFL.txt`.
// The picker enum is [com.google.ai.edge.gallery.bidet.a11y.CleanFontChoice]; the helper
// [cleanTabBodyStyle] below maps a picker value to the TextStyle the Clean-tab Text nodes apply.
//
// Default is Atkinson Hyperlegible (Braille Institute) — stronger evidence base than
// OpenDyslexic per the 2026-05-10 a11y audit. OpenDyslexic + Andika are user-selectable
// alternates. "System default" leaves the Clean tab at Material's bodyMedium (with the app's
// existing Nunito family) so it visibly differs from the picker fonts.

/**
 * Atkinson Hyperlegible (Braille Institute, SIL OFL). See
 * `third_party/atkinson_hyperlegible/README.md`.
 */
val atkinsonHyperlegibleFontFamily =
  FontFamily(
    Font(R.font.atkinson_hyperlegible_regular, FontWeight.Normal),
    Font(R.font.atkinson_hyperlegible_bold, FontWeight.Bold),
    Font(
      R.font.atkinson_hyperlegible_italic,
      FontWeight.Normal,
      androidx.compose.ui.text.font.FontStyle.Italic,
    ),
  )

/** OpenDyslexic (SIL OFL). See `third_party/opendyslexic/README.md`. */
val openDyslexicFontFamily =
  FontFamily(
    Font(R.font.opendyslexic_regular, FontWeight.Normal),
    Font(R.font.opendyslexic_bold, FontWeight.Bold),
    Font(
      R.font.opendyslexic_italic,
      FontWeight.Normal,
      androidx.compose.ui.text.font.FontStyle.Italic,
    ),
  )

/**
 * Andika (SIL International, SIL OFL). See `third_party/andika/README.md`. Substituted for
 * Lexie Readable in the original v0.3 spec because Lexie's free terms forbid redistribution.
 */
val andikaFontFamily =
  FontFamily(
    Font(R.font.andika_regular, FontWeight.Normal),
    Font(R.font.andika_bold, FontWeight.Bold),
    Font(R.font.andika_italic, FontWeight.Normal, androidx.compose.ui.text.font.FontStyle.Italic),
  )

/**
 * Resolve a [CleanFontChoice] into the [androidx.compose.ui.text.TextStyle] that the Clean-tab
 * Text nodes should apply. Always derives from `baseline.bodyMedium` so size + line-height
 * stay consistent across choices and only the glyph shapes change when the user picks a
 * different font — that keeps the layout from reflowing dramatically when the picker changes.
 *
 * For [CleanFontChoice.SYSTEM_DEFAULT] we return `bodyMedium` with the app's default Nunito
 * family (the Material baseline the rest of the app uses). The Clean-tab Text nodes therefore
 * always have an explicit `style =` and never depend on the implicit `LocalTextStyle`, which
 * keeps the picker behavior predictable across Compose tree depths.
 */
fun cleanTabBodyStyle(choice: com.google.ai.edge.gallery.bidet.a11y.CleanFontChoice) =
  when (choice) {
    com.google.ai.edge.gallery.bidet.a11y.CleanFontChoice.SYSTEM_DEFAULT ->
      baseline.bodyMedium.copy(fontFamily = appFontFamily)
    com.google.ai.edge.gallery.bidet.a11y.CleanFontChoice.ATKINSON_HYPERLEGIBLE ->
      baseline.bodyMedium.copy(fontFamily = atkinsonHyperlegibleFontFamily)
    com.google.ai.edge.gallery.bidet.a11y.CleanFontChoice.OPEN_DYSLEXIC ->
      baseline.bodyMedium.copy(fontFamily = openDyslexicFontFamily)
    com.google.ai.edge.gallery.bidet.a11y.CleanFontChoice.ANDIKA ->
      baseline.bodyMedium.copy(fontFamily = andikaFontFamily)
  }

/**
 * @deprecated v0.2 single-toggle style. Kept temporarily so a stray reference doesn't break
 * the build during the v0.2 → v0.3 transition; use [cleanTabBodyStyle] with a
 * [com.google.ai.edge.gallery.bidet.a11y.CleanFontChoice] instead.
 */
@Deprecated(
  message = "Use cleanTabBodyStyle(CleanFontChoice) — v0.3 picker replaces the v0.2 OpenDyslexic toggle.",
  replaceWith = ReplaceWith("cleanTabBodyStyle(CleanFontChoice.OPEN_DYSLEXIC)"),
)
val cleanTabBody = baseline.bodyMedium.copy(fontFamily = openDyslexicFontFamily)

val AppTypography =
  Typography(
    displayLarge = baseline.displayLarge.copy(fontFamily = appFontFamily),
    displayMedium = baseline.displayMedium.copy(fontFamily = appFontFamily),
    displaySmall = baseline.displaySmall.copy(fontFamily = appFontFamily),
    headlineLarge = baseline.headlineLarge.copy(fontFamily = appFontFamily),
    headlineMedium = baseline.headlineMedium.copy(fontFamily = appFontFamily),
    headlineSmall = baseline.headlineSmall.copy(fontFamily = appFontFamily),
    titleLarge = baseline.titleLarge.copy(fontFamily = appFontFamily),
    titleMedium = baseline.titleMedium.copy(fontFamily = appFontFamily),
    titleSmall = baseline.titleSmall.copy(fontFamily = appFontFamily),
    bodyLarge = baseline.bodyLarge.copy(fontFamily = appFontFamily),
    bodyMedium = baseline.bodyMedium.copy(fontFamily = appFontFamily),
    bodySmall = baseline.bodySmall.copy(fontFamily = appFontFamily),
    labelLarge = baseline.labelLarge.copy(fontFamily = appFontFamily),
    labelMedium = baseline.labelMedium.copy(fontFamily = appFontFamily),
    labelSmall = baseline.labelSmall.copy(fontFamily = appFontFamily),
  )

val titleMediumNarrow =
  baseline.titleMedium.copy(fontFamily = appFontFamily, letterSpacing = 0.0.sp)

val titleSmaller =
  baseline.titleSmall.copy(
    fontFamily = appFontFamily,
    fontSize = 12.sp,
    fontWeight = FontWeight.Bold,
  )

val labelSmallNarrow = baseline.labelSmall.copy(fontFamily = appFontFamily, letterSpacing = 0.0.sp)

val labelSmallNarrowMedium =
  baseline.labelSmall.copy(
    fontFamily = appFontFamily,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.0.sp,
  )

val bodySmallNarrow = baseline.bodySmall.copy(fontFamily = appFontFamily, letterSpacing = 0.0.sp)

val bodySmallMediumNarrow =
  baseline.bodySmall.copy(fontFamily = appFontFamily, letterSpacing = 0.0.sp, fontSize = 14.sp)

val bodySmallMediumNarrowBold =
  baseline.bodySmall.copy(
    fontFamily = appFontFamily,
    letterSpacing = 0.0.sp,
    fontSize = 14.sp,
    fontWeight = FontWeight.Bold,
  )

val homePageTitleStyle =
  baseline.displayMedium.copy(
    fontFamily = appFontFamily,
    fontSize = 48.sp,
    lineHeight = 48.sp,
    letterSpacing = -1.sp,
    fontWeight = FontWeight.Medium,
  )

val bodyLargeNarrow = baseline.bodyLarge.copy(letterSpacing = 0.2.sp)
val bodyMediumMedium = baseline.bodyMedium.copy(fontWeight = FontWeight.Medium)

val headlineLargeMedium = baseline.headlineLarge.copy(fontWeight = FontWeight.Medium)

val emptyStateTitle = baseline.headlineSmall.copy(fontSize = 37.sp, lineHeight = 50.sp)
val emptyStateContent = baseline.headlineSmall.copy(fontSize = 16.sp, lineHeight = 22.sp)
