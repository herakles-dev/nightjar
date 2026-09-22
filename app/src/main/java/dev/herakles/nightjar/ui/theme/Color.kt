package dev.herakles.nightjar.ui.theme

import androidx.compose.ui.graphics.Color

// nightjar palette — the android-designer default dark baseline (see
// /home/hercules/pixel6a/CLAUDE.md and design/identity.md), not yet an app-specific
// override. Task #17 runs the dedicated visual-identity pass; this is the working
// baseline the module-picker and stub screens ship with until then.

/** Screen background, window background, cold-start frame. */
val BgBase = Color(0xFF0D1117)

/** Cards / sheets — one step lighter. Not in active use yet (no elevated surfaces
 *  on the picker or stub screens), defined so Theme.kt has a real surface color
 *  instead of falling back to an M3 default. */
val BgSurface = Color(0xFF161B22)

/** Primary text. Not pure white — pure white on OLED dark mode is an AI tell. */
val TextPrimary = Color(0xFFE6EDF3)

/** Secondary text — labels, the "back" affordance, stub-screen notes. */
val TextSecondary = Color(0xFF7D8590)

/** Dividers, borders, inactive strokes. Not in active use yet — the module list
 *  has no dividers (matches the whisper-voice-app/Relay session-picker precedent:
 *  dense rows, no dividers, no cards). */
val BorderDefault = Color(0xFF30363D)

/** Real destructive actions only, never "warning". Not used anywhere yet. */
val Danger = Color(0xFFDA3633)

/**
 * Task #17's accent decision. A cyan pulled from the same GitHub-dark color family the
 * rest of the palette already borrows from (not a brand color, not tied to wallpaper) —
 * reads as "signal," not "success" or "alert," which matters because it's reused across
 * two different semantic moments (see below). 9.07:1 contrast against [BgBase], clears
 * WCAG AA (4.5:1) with margin.
 *
 * Reserved for exactly two things, both literally about the covert channel being live,
 * never for a completion/confirmation state:
 *   1. The acoustic modem's `transmitting`/`listening` status words — the moment the
 *      device's speaker or mic is physically emitting/capturing the covert signal.
 *      `encoding`/`decoding` (CPU-only, no hardware I/O) stay TextSecondary.
 *   2. The detector's and image-steganography screen's `flagged` word — the moment a
 *      covert signal has actually been caught. This is the app's actual thesis (spec
 *      INV-4: self-detectability is the defensive proof-of-concept nightjar exists
 *      for), so it's the one state in this app that most deserves a visual signal
 *      distinct from plain text weight.
 *
 * Deliberately NOT applied to `DecodedSuccess`/`ExtractedSuccess`/`Embedded` text, and
 * NOT applied to the confidence percentage itself. "Do NOT define a success color;
 * silence is success" is a harder rule than this task's looser "active/success states"
 * framing — recovered text appearing in [TextPrimary] already IS the feedback (matches
 * Relay's own precedent: its recording accent lands only on the mic ring, never on the
 * result transcript). See identity.md changelog for the full reasoning.
 */
val AccentSignal = Color(0xFF39C5CF)

// No success color. Silence is success.

/**
 * Workshop button chrome fill — owner-directed doctrine override, 2026-09-22 (see
 * `ui/theme/WorkshopButton.kt` and `design/identity.md` § Workshop button chrome override). GitHub
 * dark mode's own secondary-button fill, `#21262D` — pulled from the same GitHub-dark scale the
 * rest of this palette already borrows from, not invented for this task. [BorderDefault]
 * (`#30363D`) is GitHub's matching secondary-button border and was already defined, unused, before
 * this override — no new border token was needed, only this one fill.
 *
 * Scoped to the five technical screens' real-button-chrome rows only
 * ([dev.herakles.nightjar.ui.theme.workshopButton]'s "filled" tier). Never used on the Firefly Jar
 * surface, which has its own tinted-fill button language (`JarAction*Fill`/`JarAction*Border`
 * below) under `design/firefly-jar-identity.md`.
 */
val WorkshopButtonFill = Color(0xFF21262D)

// --- Firefly Jar (v3 disguise surface) ---------------------------------------------
// design/firefly-jar-identity.md is the source of truth for this block — a warm
// dusk-meadow palette deliberately distinct from the cold scale above, scoped to
// Screen.JarShelf/Screen.JarDetail only. The technical screens above this comment
// keep using identity.md's palette unchanged. Added for Task #6 (FireflyGlyphs.kt),
// which is the first consumer; Task #7 (JarShelfScreen.kt) reuses the same constants
// rather than redefining them.

/** A firefly you caught (embedded/transmitted). */
val FireflyCreated = Color(0xFFFFC857)

/** A firefly you spotted (extracted/decoded). Same numeric value as [AccentSignal] on
 *  purpose — see firefly-jar-identity.md § Palette: the technical app's "the covert
 *  channel is live" cyan and this surface's "a message arrived" cyan are the same real
 *  event, seen through two skins. */
val FireflyReceived = Color(0xFF39C5CF)

/** The watching jar's passive, non-glowing treatment — it never creates fireflies, and the
 *  dimmest text tier generally (footers, "ready to catch", "no fireflies yet").
 *
 *  Lightened from the design package's `#6B6690` to clear WCAG AA: at 7–8sp against the sky's
 *  `#1E1548` horizon stop the original was 3.1:1, well under the 4.5:1 this surface's doctrine
 *  holds itself to, and it was visibly hard to read on device. Hue and saturation are the
 *  package's, untouched — only lightness moved. [JarGlassWatching]/[JarRadarSweep] keep the
 *  original base: they're sub-25%-alpha decorative strokes, not text, and lightening them would
 *  change how the watching jar reads. */
val JarWatchingDim = Color(0xFF8581A5)

/** Jar silhouette outline/glass — translucent (35% alpha baked in), unlike the
 *  technical screens' opaque 1px borders. */
val JarGlassOutline = Color(0x59D9C9A3)

/** Screen background for the jar shelf/detail screens — a deep night-sky indigo. */
val JarBgDusk = Color(0xFF161229)

/** Soft gradient toward a warm horizon glow at the bottom of the jar shelf/detail
 *  screens — the one deliberate gradient-background exception to the technical
 *  screens' flat-fill-only rule (design/firefly-jar-identity.md § Palette). Added for
 *  Task #7 (JarShelfScreen.kt), the first consumer. */
val JarBgHorizon = Color(0xFF2A2450)

/** Wordmark, jar names, firefly-detail text — a warm parchment tone. Added for Task #7
 *  (JarShelfScreen.kt), the first consumer. */
val JarTextPrimary = Color(0xFFF5E6C8)

/** Captions, the "long-press to open the workshop" hint, timestamps on the jar
 *  shelf/detail screens. Added for Task #7 (JarShelfScreen.kt), the first consumer. */
val JarTextSecondary = Color(0xFF9B8FBF)

// --- Cozy Pixel Night (design refresh) ---------------------------------------------
// The "Firefly Redesign v2" design-team package, extracted to
// sessions/nightjar/artifacts/design-refresh/DESIGN_SPEC.md. Mostly an EXTENSION of the
// block above rather than a replacement: FireflyCreated, FireflyReceived, JarTextPrimary,
// JarTextSecondary, JarWatchingDim and JarGlassOutline all already carried the package's
// values. What's genuinely new is the deeper four-stop sky, a third text tier, and the
// per-jar tile tints.

/** Night-sky gradient, top to bottom — four stops at 0% / 40% / 70% / 100%. Deeper at
 *  the top and warmer at the horizon than the two-stop [JarBgDusk] → [JarBgHorizon] pair
 *  it supersedes on the jar surface. */
val JarSkyZenith = Color(0xFF060610)
val JarSkyUpper = Color(0xFF0A0A22)
val JarSkyLower = Color(0xFF141035)
val JarSkyHorizon = Color(0xFF1E1548)

/** Stop positions for the sky gradient, matching the package's
 *  `linear-gradient(180deg, … 0%, … 40%, … 70%, … 100%)`. */
val JarSkyStops = floatArrayOf(0f, 0.40f, 0.70f, 1f)

/** Third text tier, between [JarTextSecondary] and [JarWatchingDim]: subtitles under
 *  titles, section labels ("your fireflies"), tile status captions, byte counters. The
 *  one text color the pre-refresh palette had no equivalent for.
 *
 *  Lightened from the design package's `#7B6FA0` for the same reason as [JarWatchingDim] —
 *  3.7:1 against the horizon stop, and this tier carries the picker rows' unselected options,
 *  which were the worst case on device: barely legible as options at all. */
val JarTextTertiary = Color(0xFF897FAA)

/** Opaque backing behind the jar's glass strokes, so the starfield doesn't show through
 *  the jar's interior. */
val JarBodyFill = Color(0xFF0A0A20)

// Lid and wood tones. Lit jars get warm browns; the watching jar gets cool purples so
// the "asleep" detector jar reads as distinct at a glance (DESIGN_SPEC.md § 1).
val JarLidKnob = Color(0xFF6A4A30)
val JarLidKnobStroke = Color(0xFF8A6A4A)
val JarLidRim = Color(0xFF4A3520)
val JarLidRimStroke = Color(0xFF6A4A30)
val JarLidKnobDim = Color(0xFF3A2850)
val JarLidKnobStrokeDim = Color(0xFF5A4A6A)
val JarLidRimDim = Color(0xFF2A2040)
val JarLidRimStrokeDim = Color(0xFF4A3A60)

/** Jar glass outline at the three "fullness" alphas the package uses — a fuller jar gets
 *  a fractionally brighter rim. Base hex is [JarGlassOutline]'s `#D9C9A3`. */
val JarGlassFull = Color(0x30D9C9A3)
val JarGlassPartial = Color(0x25D9C9A3)
val JarGlassEmpty = Color(0x20D9C9A3)

/** The watching jar's outline and radar sweep — cool gray-purple, never warm parchment. */
val JarGlassWatching = Color(0x206B6690)
val JarRadarSweep = Color(0x356B6690)

/** Shelf-tile fills. The watching jar's tile sits one step darker than the rest. */
val JarTileFill = Color(0x0AFFFFFF)
val JarTileFillDim = Color(0x08FFFFFF)

/** Shelf-tile borders, tinted by the jar's state color. The humming jar's is fainter
 *  than the singing jar's because it has no fireflies yet. */
val JarTileBorderCreated = Color(0x1FFFC857)
val JarTileBorderCreatedFaint = Color(0x14FFC857)
val JarTileBorderReceived = Color(0x1F39C5CF)
val JarTileBorderWatching = Color(0x266B6690)

/** Action-row fills and borders, by verb. Gold = catch/transmit, cyan = look/listen,
 *  lavender = the framed jar's passive "check for hidden data". */
val JarActionCatchFill = Color(0x14FFC857)
val JarActionCatchBorder = Color(0x26FFC857)
val JarActionLookFill = Color(0x0F39C5CF)
val JarActionLookBorder = Color(0x1F39C5CF)
val JarActionCheckFill = Color(0x0F9B8FBF)
val JarActionCheckBorder = Color(0x1F9B8FBF)

/** The firefly-detail message box — a touch fainter than the catch action row it sits
 *  near, so the payload reads as content rather than as something tappable. */
val JarMessageFill = Color(0x0FFFC857)
val JarMessageBorder = Color(0x1AFFC857)

/** Neutral card/chip surfaces — metadata mini-cards, unselected technique chips,
 *  history rows, the level-meter track. */
val JarCardFill = Color(0x08FFFFFF)
val JarCardBorder = Color(0x0FFFFFFF)
val JarHistoryRowFill = Color(0x05FFFFFF)
val JarMeterTrack = Color(0x0FFFFFFF)

/** The white specular highlights on the jar glass. Opacity is applied per-stroke at the
 *  call site (0.03–0.12 depending on which reflection) rather than baked in here. */
val JarGlassHighlight = Color(0xFFFFFFFF)
