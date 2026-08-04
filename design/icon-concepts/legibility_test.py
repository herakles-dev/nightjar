#!/usr/bin/env python3
"""Legibility test for the chosen candidate (A: carrier wave) at the doctrine's
required sizes: 24 / 48 / 96 / 192 px. Composited on bg_base since that's how
it actually appears on-device (transparent PNG viewed on a light backdrop
would misrepresent it)."""
import os

from PIL import Image

from render_nightjar_icon import BG, render_candidate_a, flatten_on_bg

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "review")


def main():
    color = flatten_on_bg(render_candidate_a(mono=False))
    sizes = [24, 48, 96, 192]
    pad = 16
    sheet_w = sum(sizes) + pad * (len(sizes) + 1)
    sheet_h = max(sizes) + pad * 2
    sheet = Image.new("RGB", (sheet_w, sheet_h), BG[:3])
    x = pad
    for s in sizes:
        thumb = color.resize((s, s), Image.LANCZOS)
        y = pad + (max(sizes) - s) // 2
        sheet.paste(thumb, (x, y))
        x += s + pad
    sheet.save(os.path.join(OUT_DIR, "candidate_a_legibility_24_48_96_192.png"))
    print("saved legibility sheet")


if __name__ == "__main__":
    main()
