#!/usr/bin/env python3
"""
nightjar app icon — Task #17 (android-designer visual-identity pass).

Renders 3 candidate concepts at review size, then produces full production
assets (adaptive icon foreground + monochrome + flat mipmap composites +
one 512x512 README hero) for the chosen candidate.

No SVG tooling is installed in this environment (no rsvg-convert, no
cairosvg/svgwrite). whisper-voice-app/Relay's own icon.md documents Python
raster renderers producing PNGs despite naming them "render_*.py" next to
an SVG-flavored spec doc -- same practical pattern followed here. Doctrine
asks for "a single 192x192 mono SVG" per candidate; substituted with a
192x192 mono PNG, same review purpose.

Convention matches Relay's icon.md exactly: 432dp working canvas (4x the
108dp adaptive-icon baseline), safe zone = central 264dp circle (radius 132,
center 216,216). Supersampled 4x (1728px) and downsampled with LANCZOS for
clean anti-aliased edges -- PIL/ImageMagick have no vector path renderer
here, so supersampling is the anti-aliasing strategy.
"""

import math
import os

from PIL import Image, ImageDraw

BG = (0x0D, 0x11, 0x17, 255)          # bg-base, icon canvas fill
FG = (0xE6, 0xED, 0xF3, 255)          # text-primary equivalent, foreground shape
ACCENT = (0x39, 0xC5, 0xCF, 255)      # AccentSignal -- chosen this task, see identity.md
MONO = (0xFF, 0xFF, 0xFF, 255)        # themed-icon monochrome layer, plain white silhouette

CANVAS = 432
SS = 4  # supersample factor
BIG = CANVAS * SS

SAFE_CENTER = (216, 216)
SAFE_RADIUS = 132  # 264dp safe-zone circle, matches Relay's icon.md spec

OUT_DIR = os.path.dirname(os.path.abspath(__file__))


def _s(v):
    """Scale a 432-space coordinate/length into supersampled space."""
    return v * SS


def new_canvas(transparent=True):
    fill = (0, 0, 0, 0) if transparent else BG
    return Image.new("RGBA", (BIG, BIG), fill)


def downsample(img_big):
    return img_big.resize((CANVAS, CANVAS), Image.LANCZOS)


# --------------------------------------------------------------------------
# Candidate A: carrier wave with one hidden bit
# A carrier sine wave (the acoustic channel, Module 3/5's literal medium)
# with one square node sitting on it where data is riding the signal --
# the covert-channel idea in one shape: something extra hidden inside an
# otherwise ordinary carrier.
# --------------------------------------------------------------------------
def wave_points(cycles=2.0, amp=62, x0=108, x1=324, cy=216, phase=-math.pi / 2, n=400):
    pts = []
    for i in range(n + 1):
        t = i / n
        x = x0 + t * (x1 - x0)
        y = cy + amp * math.sin(2 * math.pi * cycles * t + phase)
        pts.append((x, y))
    return pts


def render_candidate_a(mono=False, fg_color=None, accent_color=None):
    img = new_canvas()
    draw = ImageDraw.Draw(img)
    fg = fg_color or (MONO if mono else FG)
    accent = accent_color or (MONO if mono else ACCENT)

    # PIL's ImageDraw.line(..., width=..., joint="curve") produces a hatched/
    # bowtie artifact on tight curves at large stroke widths (background
    # bleeds through at each point where the path bends sharply relative to
    # the stroke width). Stamping a dense series of overlapping filled
    # circles along the path is the reliable way to get a smooth, solid,
    # round-capped brush stroke out of PIL with no vector stroking API.
    pts = wave_points()
    stroke_w = _s(46)
    r = stroke_w / 2
    for x, y in pts:
        cx, cy = _s(x), _s(y)
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=fg)

    # Accent node: the "hidden bit" -- a sharp-cornered square straddling
    # the wave at its second crest. Sharp corners are deliberate (same
    # contrast move Relay's terminal-rectangle-with-cursor made): the
    # wave is organic/curved, the bit is a hard discrete value.
    peak_idx = max(range(len(pts)), key=lambda i: pts[i][1] if False else -pts[i][1])
    # second local maximum (crest), not the first -- visually more centered
    crest_xs = [x for i, (x, y) in enumerate(pts) if 0 < i < len(pts) - 1
                and pts[i - 1][1] > y < pts[i + 1][1]]
    crest_x = crest_xs[1] if len(crest_xs) > 1 else (crest_xs[0] if crest_xs else pts[len(pts) // 2][0])
    crest_y = min(y for x, y in pts if abs(x - crest_x) < 1)
    half = _s(42)
    cx, cy = _s(crest_x), _s(crest_y)
    draw.rectangle([cx - half, cy - half, cx + half, cy + half], fill=accent)

    return downsample(img)


# --------------------------------------------------------------------------
# Candidate B: pixel grid, one flipped cell
# A 4x4 grid of even squares, all one value except a single cell in the
# accent color -- the literal LSB-embedding idea (Module 1): change one
# bit in a field of many, invisible unless you know where to look.
# --------------------------------------------------------------------------
def render_candidate_b(mono=False, fg_color=None, accent_color=None):
    img = new_canvas()
    draw = ImageDraw.Draw(img)
    fg = fg_color or (MONO if mono else FG)
    accent = accent_color or (MONO if mono else ACCENT)

    cols = 4
    gap = 14
    total = 248
    cell = (total - gap * (cols - 1)) / cols
    x0 = 216 - total / 2
    y0 = 216 - total / 2

    flip_row, flip_col = 1, 2  # off-center cell, not the middle/symmetric one
    for row in range(cols):
        for col in range(cols):
            x = x0 + col * (cell + gap)
            y = y0 + row * (cell + gap)
            color = accent if (row, col) == (flip_row, flip_col) else fg
            draw.rectangle([_s(x), _s(y), _s(x + cell), _s(y + cell)], fill=color)

    return downsample(img)


# --------------------------------------------------------------------------
# Candidate C: nightjar wing / chevron
# A single angular wing shape, literal to the app's namesake bird -- a
# nightjar is cryptically camouflaged and easy to miss, a fitting mark for
# a covert-channel app. One bold chevron, no accent needed.
# --------------------------------------------------------------------------
def render_candidate_c(mono=False, fg_color=None, accent_color=None):
    img = new_canvas()
    draw = ImageDraw.Draw(img)
    fg = fg_color or (MONO if mono else FG)

    # An angular wing: 5-point polygon, asymmetric, pointed tip lower-left,
    # broad trailing edge upper-right -- reads as a wing-in-flight silhouette.
    poly = [
        (110, 300),   # leading tip
        (200, 150),
        (330, 120),   # trailing tip, upper
        (270, 210),
        (330, 300),   # trailing tip, lower
        (190, 260),
    ]
    big_poly = [(_s(x), _s(y)) for x, y in poly]
    draw.polygon(big_poly, fill=fg)

    return downsample(img)


def flatten_on_bg(img_rgba):
    base = Image.new("RGBA", img_rgba.size, BG)
    base.alpha_composite(img_rgba)
    return base.convert("RGB")


def main():
    review_dir = os.path.join(OUT_DIR, "review")
    os.makedirs(review_dir, exist_ok=True)

    candidates = {
        "a_carrier_wave": render_candidate_a,
        "b_pixel_grid": render_candidate_b,
        "c_wing_chevron": render_candidate_c,
    }

    # Full-color 432 preview + 192x192 mono preview per candidate.
    for name, fn in candidates.items():
        color_img = fn(mono=False)
        flatten_on_bg(color_img).save(os.path.join(review_dir, f"{name}_color_432.png"))

        mono_img = fn(mono=True)
        mono_192 = flatten_on_bg(mono_img).resize((192, 192), Image.LANCZOS)
        mono_192.save(os.path.join(review_dir, f"{name}_mono_192.png"))

    # Contact sheet: all 3 color candidates side by side at 216px each, on bg.
    sheet = Image.new("RGB", (216 * 3, 216), BG[:3])
    for i, name in enumerate(candidates):
        thumb = Image.open(os.path.join(review_dir, f"{name}_color_432.png")).resize((216, 216), Image.LANCZOS)
        sheet.paste(thumb, (i * 216, 0))
    sheet.save(os.path.join(review_dir, "contact_sheet.png"))

    print("Rendered 3 candidates to", review_dir)


if __name__ == "__main__":
    main()
