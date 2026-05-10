# OpenDyslexic font (bundled)

This directory tracks the source + license for the **OpenDyslexic** typeface that
ships in `Android/src/app/src/main/res/font/`:

- `opendyslexic_regular.otf`
- `opendyslexic_bold.otf`
- `opendyslexic_italic.otf`

## License

OpenDyslexic is distributed under the **SIL Open Font License (OFL) Version 1.1**.
The full license text is in [`OFL.txt`](OFL.txt).

The OFL permits free use, including commercial redistribution and bundling inside
binary application packages, provided the license text is shipped alongside the
font files and the Reserved Font Name is not reused for derivative fonts. See
[scripts.sil.org/OFL](https://scripts.sil.org/OFL) for the canonical text and the
SIL OFL FAQ.

The OFL is compatible with Apache License 2.0 (the host license of this repo) for
the purposes of bundling a font asset: the font remains under OFL inside the APK,
and the OFL text is included in this repository so end users and downstream
distributors can read the license that governs the font.

## Source

- Project home: [opendyslexic.org](https://opendyslexic.org/)
- Upstream repo: [github.com/antijingoist/opendyslexic](https://github.com/antijingoist/opendyslexic)
- Release used: `v0.91.12` (asset `opendyslexic-0.910.12-rc2-2019.10.17.zip`)
- Copyright: (c) 2019-07-29 Abbie Gonzalez, with Reserved Font Name "OpenDyslexic"

## Why we bundle it

The Clean-tab outputs in Bidet AI are reading-heavy. Some users with dyslexia or
related reading differences find OpenDyslexic easier to track. The font is an
**opt-in** setting (see "Use OpenDyslexic font" toggle in Bidet Settings) and is
applied only to the cleaned-output text, not the raw transcript.

A subset of users find OpenDyslexic itself harder to read than a conventional
sans; the toggle defaults OFF and is fully reversible.
