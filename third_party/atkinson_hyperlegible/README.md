# Atkinson Hyperlegible font (bundled)

This directory tracks the source + license for the **Atkinson Hyperlegible**
typeface that ships in `Android/src/app/src/main/res/font/`:

- `atkinson_hyperlegible_regular.ttf`
- `atkinson_hyperlegible_bold.ttf`
- `atkinson_hyperlegible_italic.ttf`

The upstream project README (with design rationale and feature list straight
from the Braille Institute) is preserved verbatim in
[`UPSTREAM_README.md`](UPSTREAM_README.md).

## License

Atkinson Hyperlegible is distributed under the **SIL Open Font License (OFL)
Version 1.1**. The full license text is in [`OFL.txt`](OFL.txt).

The OFL permits free use, including commercial redistribution and bundling
inside binary application packages, provided the license text is shipped
alongside the font files and the Reserved Font Name is not reused for
derivative fonts. See [scripts.sil.org/OFL](https://scripts.sil.org/OFL) for
the canonical text and the SIL OFL FAQ.

The OFL is compatible with Apache License 2.0 (the host license of this repo)
for the purposes of bundling a font asset: the font remains under OFL inside
the APK, and the OFL text is included in this repository so end users and
downstream distributors can read the license that governs the font.

## Source

- Project home: [brailleinstitute.org/freefont](https://brailleinstitute.org/freefont)
- Upstream repo: [github.com/googlefonts/atkinson-hyperlegible](https://github.com/googlefonts/atkinson-hyperlegible)
- Release used: `main` branch snapshot, `fonts/ttf/` (TTFs preferred over OTF
  for Android `res/font` to match the existing Nunito family bundling style)
- Copyright: (c) 2020 Braille Institute of America, Inc., with Reserved Font
  Name "Atkinson Hyperlegible"

## Why we bundle it as the *default* hyperlegible option

Atkinson Hyperlegible was commissioned by the Braille Institute specifically to
maximize letterform distinguishability for low-vision and reading-difference
readers. Compared to OpenDyslexic, the peer-reviewed evidence base for
Atkinson Hyperlegible is stronger (the OpenDyslexic literature is mixed-to-
negative — Marinus 2016, Wery & Diliberto 2017, Kuster 2018 — with the wider
spacing rather than the letter shapes carrying the gains).

Atkinson Hyperlegible is therefore the **default** font for the Clean-tab
cleaned-output text in Bidet AI. OpenDyslexic and Andika remain available as
user-selectable alternates in the "Font for cleaned text" picker — readers who
prefer them subjectively are respected. The picker also offers "System
default", which leaves Clean-tab text rendered in the device's body font.

The picker applies to both Clean-for-me and Clean-for-others tabs. The RAW
transcript tab is intentionally never re-styled — verbatim text is the source
of truth, not a piece of UX to skin.
