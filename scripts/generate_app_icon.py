#!/usr/bin/env python3
# Copyright 2026 bidet-ai contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# generate_app_icon.py — Regenerate Android adaptive-icon mipmap PNGs from
# branding/bidet-ai_icon.png. Run once after editing the source asset.
#
# The source PNG (351x411) is centered onto a transparent square canvas,
# downsampled to the standard density buckets, and written into
# Android/src/app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/.
#
# Adaptive icon contract: the foreground is rendered inside a 108x108dp box
# with the inner 72x72dp guaranteed visible. We provision a small inner pad so
# the toilet motif sits comfortably within the safe area on all launcher
# masks (circle/squircle/teardrop).
#
# Requirements: Pillow >= 9.0 (`pip install Pillow`).

from pathlib import Path
import sys

try:
    from PIL import Image
except ImportError:
    sys.stderr.write(
        "Pillow is required. Install with: python3 -m pip install Pillow\n"
    )
    sys.exit(2)

REPO_ROOT = Path(__file__).resolve().parent.parent
SOURCE = REPO_ROOT / "branding" / "bidet-ai_icon.png"
RES = REPO_ROOT / "Android" / "src" / "app" / "src" / "main" / "res"

# Density buckets: ic_launcher.png (square) sizes per Android spec.
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# Adaptive-icon foreground sizes (108dp at each density).
FG_DENSITIES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}

# Inner-safe padding factor — keep the motif within the 72/108 safe zone.
INNER_SCALE = 72.0 / 108.0


def square_canvas(src: Image.Image, side: int, scale: float = 1.0) -> Image.Image:
    """Center src onto a transparent side x side canvas, scaled to fit."""
    target = int(side * scale)
    w, h = src.size
    ratio = min(target / w, target / h)
    new_w = max(1, int(w * ratio))
    new_h = max(1, int(h * ratio))
    resized = src.resize((new_w, new_h), Image.LANCZOS)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(resized, ((side - new_w) // 2, (side - new_h) // 2), resized)
    return canvas


def main() -> int:
    if not SOURCE.exists():
        sys.stderr.write(f"Source asset missing: {SOURCE}\n")
        return 1
    src = Image.open(SOURCE).convert("RGBA")

    # Square ic_launcher.png at each density (legacy launcher fallback).
    for bucket, size in DENSITIES.items():
        dest_dir = RES / f"mipmap-{bucket}"
        dest_dir.mkdir(parents=True, exist_ok=True)
        out = square_canvas(src, size, scale=1.0)
        out.save(dest_dir / "ic_launcher.png", optimize=True)
        # Round variant — same square; Android masks at runtime.
        out.save(dest_dir / "ic_launcher_round.png", optimize=True)
        print(f"wrote {dest_dir}/ic_launcher{{,_round}}.png ({size}x{size})")

    # Adaptive-icon foreground at each density — use the inner-safe scale.
    for bucket, size in FG_DENSITIES.items():
        dest_dir = RES / f"mipmap-{bucket}"
        dest_dir.mkdir(parents=True, exist_ok=True)
        out = square_canvas(src, size, scale=INNER_SCALE)
        out.save(dest_dir / "ic_launcher_foreground.png", optimize=True)
        print(f"wrote {dest_dir}/ic_launcher_foreground.png ({size}x{size})")

    print("done.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
