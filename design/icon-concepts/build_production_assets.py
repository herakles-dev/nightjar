#!/usr/bin/env python3
"""Produces the shipped nightjar icon assets from Candidate A (carrier wave,
one hidden bit) -- the pick documented in design/icon.md.

Outputs (all under app/src/main/res/, matching Relay's file layout):
  drawable/ic_launcher_foreground.png   432x432 RGBA, adaptive foreground layer
  drawable/ic_launcher_monochrome.png   432x432 RGBA, themed-icon layer
  mipmap-mdpi/ic_launcher.png           48x48   flat composite (pre-API26 fallback)
  mipmap-hdpi/ic_launcher.png           72x72
  mipmap-xhdpi/ic_launcher.png          96x96
  mipmap-xxhdpi/ic_launcher.png         144x144
  mipmap-xxxhdpi/ic_launcher.png        192x192
And, outside res/, for the README/repo banner:
  design/assets/icon-512.png            512x512, flattened on bg_base
"""
import os

from PIL import Image

from render_nightjar_icon import BG, render_candidate_a, flatten_on_bg

ICON_DIR = os.path.dirname(os.path.abspath(__file__))
NIGHTJAR_ROOT = os.path.abspath(os.path.join(ICON_DIR, "..", ".."))
RES_DIR = os.path.join(NIGHTJAR_ROOT, "app", "src", "main", "res")
ASSETS_DIR = os.path.join(NIGHTJAR_ROOT, "design", "assets")

MIPMAP_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def main():
    foreground = render_candidate_a(mono=False)  # 432x432 RGBA, transparent bg
    monochrome = render_candidate_a(mono=True)    # 432x432 RGBA, white silhouette

    bbox = foreground.getbbox()
    print("foreground alpha bbox (432-space):", bbox, "-- safe zone box is [84,348]x[84,348]")

    drawable_dir = os.path.join(RES_DIR, "drawable")
    os.makedirs(drawable_dir, exist_ok=True)
    foreground.save(os.path.join(drawable_dir, "ic_launcher_foreground.png"))
    monochrome.save(os.path.join(drawable_dir, "ic_launcher_monochrome.png"))

    flat = flatten_on_bg(foreground)  # RGB, wave composited onto bg_base
    for mipmap_name, size in MIPMAP_SIZES.items():
        out_dir = os.path.join(RES_DIR, mipmap_name)
        os.makedirs(out_dir, exist_ok=True)
        resized = flat.resize((size, size), Image.LANCZOS)
        resized.save(os.path.join(out_dir, "ic_launcher.png"))
        resized.save(os.path.join(out_dir, "ic_launcher_round.png"))

    os.makedirs(ASSETS_DIR, exist_ok=True)
    flat.resize((512, 512), Image.LANCZOS).save(os.path.join(ASSETS_DIR, "icon-512.png"))

    print("Wrote adaptive layers, 5 mipmap sizes, and the 512x512 README hero.")


if __name__ == "__main__":
    main()
