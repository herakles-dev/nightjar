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

/** The watching jar's passive, non-glowing treatment — it never creates fireflies. */
val JarWatchingDim = Color(0xFF6B6690)

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
