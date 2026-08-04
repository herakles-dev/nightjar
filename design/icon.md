# nightjar — App Icon

**Date settled:** 2026-08-03 (Task #17)
**Concept shipped:** A — carrier wave, one hidden bit
**Status:** shipped to mipmap set + adaptive icon; not yet installed/eyeballed on-device
(ADB was unreachable this pass — `hek adb-status` reported `NOT CONNECTED`, `hek
adb-connect` timed out with the device apparently asleep/off Wi-Fi. Build-level
verification only; see identity.md's "Verification" note.)

---

## Why an icon pass now

No launcher icon existed anywhere in the tree before this task — `AndroidManifest.xml`
had no `android:icon` attribute at all, and `res/` had no `mipmap*` directories. The app
would have shipped with whatever generic fallback the OS supplies. Task #17 is the
dedicated visual-identity pass, and doctrine requires an icon before this app is
considered done ("App icon final pick: Always 3 candidates, user picks" per the
android-designer decision framework) — so this task also commissions one.

Per doctrine, the final pick is normally the user's call, not the designer's. This task
ran as a single autonomous pass with no interactive checkpoint mid-task, so — to avoid
leaving the app iconless — I shipped the strongest candidate as the default and kept the
other two fully rendered and documented below so the user can override with a one-line
XML swap if a different concept reads better to them. Treat "Concept shipped: A" as a
placeholder pick, not a closed decision.

---

## The three candidates

Rendered via `design/icon-concepts/render_nightjar_icon.py` (Python/Pillow — no SVG
tooling installed in this environment; same practical PNG-renderer pattern Relay's own
`icon-concepts/render_relay_station.py` used despite the doctrine's "SVG" phrasing).
Review renders: `design/icon-concepts/review/`.

| # | Concept | Ties to | Why it was or wasn't picked |
|---|---|---|---|
| A | **Carrier wave, one hidden bit.** A 2-cycle sine wave with a sharp-cornered accent square straddling one crest. | Module 3 (acoustic modem) — spec.md's own "primary gate-2 phone-to-phone demo" — plus the literal thesis of the whole app: something extra riding an ordinary-looking signal. | **Shipped.** Most specific to *covert channels* specifically, not just "an audio app." The accent square reuses [`AccentSignal`](#accentsignal-cross-reference) exactly, so the icon and the in-app "flagged"/"transmitting" accent are the same color — one visual language, not two. Holds up as a monochrome silhouette (see below). |
| B | **Pixel grid, one flipped cell.** A 4×4 grid of even squares, one cell in the accent color. | Module 1 (image LSB steganography) — the literal embedding idea: change one bit in a field of many. | Not picked. Reads clearly in full color, but the *entire point* of the concept disappears in the monochrome/themed-icon variant — a same-color grid is just a grid (could be a calendar, a keyboard, a QR fragment). A concept that only works in one color mode is a weaker pick than one that survives both. |
| C | **Nightjar wing / chevron.** A single angular 6-point polygon, asymmetric, wing-in-flight silhouette. | The app's actual namesake — a nightjar is a cryptically camouflaged nocturnal bird, easy to miss, an apt mark for a covert-channel app. | Not picked, but a close second. Boldest, cleanest silhouette of the three — reads instantly at 24px. Traded off against A because it's abstract enough that "wing" isn't obviously legible without the name already in hand (could read as an arrow, a paper airplane, a shard) and it carries no accent-color moment, so it doesn't reinforce [`AccentSignal`](#accentsignal-cross-reference) the way A does. Kept fully rendered as the strongest fallback if A doesn't land with the user. |

Review images:
- `design/icon-concepts/review/contact_sheet.png` — all 3 in full color, side by side
- `design/icon-concepts/review/{a,b,c}_*_mono_192.png` — each candidate's monochrome
  192×192 read
- `design/icon-concepts/review/candidate_a_legibility_24_48_96_192.png` — the shipped
  candidate at all 4 required test sizes

### AccentSignal cross-reference

The accent square in Candidate A is rendered in the exact hex Task #17 chose for the
app's one reserved UI accent — `#39C5CF`, `AccentSignal` in
`app/src/main/java/dev/herakles/nightjar/ui/theme/Color.kt`. See identity.md for the
full rationale on what that color is reserved for in the UI (the modem's
transmitting/listening state, the detector's/image-stego's flagged state). The icon
reusing it is deliberate, not incidental — it's the one place in the whole visual
system where "a covert bit riding a carrier" gets a color, in the icon and in the app.

---

## Concept rationale (Candidate A, shipped)

A carrier wave — two full sine cycles, thick round-stroked line — with one sharp-cornered
square sitting on its second crest. The wave is the medium (sound, in Module 3's case);
the square is the payload. Everything else about the wave is smooth and continuous; the
square is a hard, discrete value breaking that continuity — which is exactly what a
covert channel is: an otherwise-ordinary signal carrying one thing it shouldn't.

At 24×24 silhouette the shape reads as a distinctive "W-with-a-notch" — not a generic
wave/audio glyph, and not confusable with a stock Material waveform icon, because of that
one squared-off interruption. See
`design/icon-concepts/review/candidate_a_legibility_24_48_96_192.png` — the notch is
still visible at 24px, though small; it does not fully disappear into the wave form.

No gradient, no inner highlight, no rim light, no outer glow, no rounded terminal corners
(the accent square is sharp-90°, deliberately, the same contrast move Relay's icon.md
documents for its terminal-rectangle-with-cursor: organic curve vs. hard-edged discrete
value). Not a letter in a colored circle. Not the Apple-squircle-with-inner-shadow look.

---

## Colors

| Role | Hex | Element |
|---|---|---|
| background | `#0D1117` | icon canvas fill (`ic_launcher_background`, matches `bg_base`) |
| foreground | `#E6EDF3` | the wave stroke (text-primary equivalent) |
| accent | `#39C5CF` | the hidden-bit square only (`AccentSignal`) |

Three values. No gradients.

---

## Files

| File | Purpose | Size |
|---|---|---|
| `icon-concepts/render_nightjar_icon.py` | renders all 3 candidates (color + mono review) | — |
| `icon-concepts/legibility_test.py` | 24/48/96/192 contact sheet for the shipped candidate | — |
| `icon-concepts/build_production_assets.py` | writes the shipped candidate into `app/src/main/res/` + the README hero | — |
| `icon-concepts/review/` | all candidate renders + legibility sheet (not shipped, review-only) | — |
| `app/src/main/res/drawable/ic_launcher_foreground.png` | adaptive foreground layer, transparent bg | 432×432 |
| `app/src/main/res/drawable/ic_launcher_monochrome.png` | themed-icon layer, white silhouette | 432×432 |
| `app/src/main/res/mipmap-mdpi/ic_launcher.png` (+ `_round`) | flat composite, pre-API26 fallback | 48×48 |
| `app/src/main/res/mipmap-hdpi/ic_launcher.png` (+ `_round`) | — | 72×72 |
| `app/src/main/res/mipmap-xhdpi/ic_launcher.png` (+ `_round`) | — | 96×96 |
| `app/src/main/res/mipmap-xxhdpi/ic_launcher.png` (+ `_round`) | — | 144×144 |
| `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` (+ `_round`) | — | 192×192 |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` (+ `_round.xml`) | adaptive icon descriptor (Android 8+) | — |
| `app/src/main/res/values/colors.xml` | `ic_launcher_background` = `#0D1117` | — |
| `design/assets/icon-512.png` | flattened 512×512 for README / repo banner | 512×512 |

`AndroidManifest.xml`'s `<application>` tag now carries
`android:icon="@mipmap/ic_launcher"` and `android:roundIcon="@mipmap/ic_launcher_round"`
— it had neither before this task.

---

## Adaptive icon safe zone

Same convention Relay's icon.md documents: 432dp working canvas (4× the 108dp adaptive-
icon baseline), safe zone = central 264dp circle (radius 132, center 216,216). The wave's
path endpoints were pulled in from an initial [96,336] span to [108,324] specifically so
the stroke's paint (path ± 23px stroke radius) lands inside that box — measured via
`Image.getbbox()` on the actual rendered alpha channel: final bbox `(82, 109, 350, 304)`
against a `[84,348]×[84,348]` target, i.e. within about 2px of the box on either side
(sub-1%-of-canvas overflow from anti-aliased edge pixels, not a real design overflow).

---

## Anti-tell checklist

- [x] One shape (the wave), one accent shape (the square) — max two colors plus background
- [x] No gradient fills
- [x] Sharp 90° corners on the accent square (the one deliberately non-rounded element)
- [x] No inner highlight, no rim light, no glow
- [x] No glassmorphism or frosted layers
- [x] No letter in a colored circle
- [x] Legible as silhouette at 24×24 (see legibility sheet — notch is small but present)
- [x] Not the Apple squircle aesthetic
- [x] No Material Symbols default icon
- [x] Adaptive foreground (432×432) + monochrome (432×432) + solid background hex exported
- [x] One 512×512 PNG exported for README / repo banner (`design/assets/icon-512.png`)
