# Andika font (bundled)

This directory tracks the source + license for the **Andika** typeface that
ships in `Android/src/app/src/main/res/font/`:

- `andika_regular.ttf`
- `andika_bold.ttf`
- `andika_italic.ttf`

## License

Andika is distributed under the **SIL Open Font License (OFL) Version 1.1**.
The full license text is in [`OFL.txt`](OFL.txt). FONTLOG and upstream README
are also bundled for OFL provenance.

The OFL permits free use, including commercial redistribution and bundling
inside binary application packages, provided the license text is shipped
alongside the font files and the Reserved Font Name is not reused for
derivative fonts. See [scripts.sil.org/OFL](https://scripts.sil.org/OFL).

The OFL is compatible with Apache License 2.0 (the host license of this repo)
for the purposes of bundling a font asset.

## Source

- Project home: [software.sil.org/andika](https://software.sil.org/andika/)
- Release used: `Andika-6.200` (the released TTFs include Regular, Bold, and
  Italic — we ship those three; the BoldItalic ships in the upstream package
  but is not bundled here because Compose's `FontFamily` slot for our Clean-tab
  text style only needs Regular/Bold/Italic).
- Copyright: (c) 2004-2023 SIL International, with Reserved Font Names
  "Andika" and "SIL"

## Why Andika and not Lexie Readable

The original v0.3 spec listed Lexie Readable (K-Type) as the third picker
option. On license review (2026-05-10), Lexie Readable's free terms permit
**personal use** and use by **educational and charitable institutions only**;
business / commercial / redistribution use requires a paid K-Type Commercial
or Enterprise license, and the K-Type license explicitly forbids "giving
fonts to others." Bundling Lexie Readable in a public GitHub repo and
distributing via Google Play would not satisfy those terms under our Apache
2.0 host license.

Andika was substituted because it shares the design goals Lexie Readable was
chosen for — large x-height, generous spacing, non-symmetric `b/d`, single-
storey `a/g`, designed explicitly for literacy and reading-difference readers
(SIL's literacy-focused sister to Charis SIL) — and because it is genuinely
SIL OFL, with no commercial restriction. If a future maintainer wants Lexie
Readable back in the picker, swap one enum entry in `CleanFontChoice.kt` and
purchase the K-Type Commercial license; the picker architecture is
font-agnostic.

## Use in Bidet AI

Andika is one of three opt-in alternates in the "Font for cleaned text"
picker (Atkinson Hyperlegible is the default). The picker applies to both
Clean tabs; the RAW transcript tab is never re-styled.
