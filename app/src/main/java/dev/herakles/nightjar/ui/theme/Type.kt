package dev.herakles.nightjar.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.herakles.nightjar.R

// System sans-serif for now (Roboto on the Pixel 6a — the "system-ui-sans" fallback
// the doctrine allows). No Inter TTFs bundled in this task; whisper-voice-app bundles
// Inter (see its design/identity.md) and task #17 decides whether nightjar follows
// suit. Weights limited to 400/500/600 either way — no 300 (fragile on OLED), no
// 700+ (too heavy in dark mode).

private val base = TextStyle(fontFamily = FontFamily.Default)

// Three active type-scale positions for this task's surfaces:
//   displayLarge — "nightjar" wordmark, module name on the stub screen
//   bodyLarge    — module-picker row labels, stub-screen note
//   labelLarge   — the "back" affordance
val NightjarTypography = Typography(
    displayLarge = base.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    bodyLarge = base.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    labelLarge = base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),

    // Collapse remaining M3 slots to a bodyLarge-equivalent so nothing falls through
    // to an unstyled system default when a later task reaches for an unused slot.
    displayMedium = base.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    displaySmall = base.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    headlineLarge = base.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    headlineMedium = base.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    headlineSmall = base.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleLarge = base.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleMedium = base.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    bodyMedium = base.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodySmall = base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelMedium = base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = base.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
)

// --- Firefly Jar surface: Silkscreen ------------------------------------------------
// The "Firefly Redesign v2" package builds its whole identity on Silkscreen, a pixel
// display face (OFL, vendored at res/font/ with its license). This REVERSES
// firefly-jar-identity.md § Typography's "staying on system default font (Roboto)" call
// — see that doc's change log for the reasoning and the design package that overrode it.
//
// Deliberately NOT wired into [NightjarTypography] above. That object is the shared M3
// typography, and identity.md still binds the four technical screens under the
// long-press reveal to the system font at weights 400/500/600 — a rule the jar surface's
// doctrine relaxation does not touch. Jar composables reference [JarType] explicitly;
// anything that resolves through MaterialTheme.typography stays Roboto.

val SilkscreenFamily = FontFamily(
    Font(R.font.silkscreen_regular, FontWeight.Normal),
    Font(R.font.silkscreen_bold, FontWeight.Bold),
)

private val pixel = TextStyle(fontFamily = SilkscreenFamily)

/**
 * The jar surface's type roles, derived from DESIGN_SPEC.md § 2 but **not** its raw numbers.
 *
 * The package's sizes were CSS px in a 375×812 design canvas. Reading them straight across to
 * sp is geometrically defensible — that frame is about a phone's dp box — but it shipped a
 * scale whose captions sat at 7–8sp, and on a real Pixel 6a that is simply too small to read.
 * The mockup was reviewed on a desktop, where the same numbers look comfortable.
 *
 * So the ramp is rebuilt around one fixed point: [ActionTitle] at 16sp, the size confirmed
 * correct on device ("catch a firefly" / "look for fireflies"). Everything else is scaled to
 * sit in proportion to it, which roughly doubles the small tiers.
 *
 * The structural bug that fell out of the 1:1 port: [TileTitle] was 10sp while [ActionTitle]
 * was 16sp, even though both are the primary label of a full-width tappable row — the shelf
 * read as a weaker surface than the detail screens for no reason. They are now the same size.
 *
 * Five tiers, in proportion:
 *   micro 12sp — captions, footers, meta labels, byte counters
 *   small 13sp — back links, section labels, timestamps
 *   base  16sp — tile and action titles, body copy, metadata values
 *   title 24sp — screen titles;  display 28sp — the wordmark;  numeral 36sp
 *
 * Tracking scales with the tier rather than staying at the package's absolute px, so the
 * letter-spacing reads the same relative to the glyphs at every size.
 */
object JarType {
    /** "night jar" on the shelf. */
    val Wordmark = pixel.copy(fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 1.5.sp)

    /** "hold to open workshop" under the wordmark. */
    val WordmarkSubtitle = pixel.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.8.sp)

    /** Detail-screen titles — "the singing jar". */
    val ScreenTitle = pixel.copy(fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 1.5.sp)

    /** Action-screen titles — "catch a firefly", "look for fireflies". The anchor: this size
     *  was verified on device and everything else is proportioned to it. Don't drift it
     *  without re-checking the whole ramp. */
    val ActionTitle = pixel.copy(fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 1.sp)

    /** The line under a screen title. */
    val ScreenSubtitle = pixel.copy(fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.5.sp)

    /** "← back to the shelf". */
    val BackLink = pixel.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.8.sp)

    /** Section labels — "your fireflies", "history", "technique". */
    val SectionLabel = pixel.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.8.sp)

    /** Jar name in a shelf tile; action-row title. Same size as [ActionTitle] on purpose —
     *  both are the primary label of a full-width tappable row. */
    val TileTitle = pixel.copy(fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.5.sp)

    /** Tile status caption — "3 fireflies", "no fireflies yet". */
    val TileCaption = pixel.copy(fontSize = 12.sp, lineHeight = 17.sp)

    /** Message and payload text. */
    val Body = pixel.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp)

    /** Metadata labels — "size", "channel", "direction". */
    val MetaLabel = pixel.copy(fontSize = 12.sp, lineHeight = 17.sp)

    /** Metadata values next to those labels. */
    val MetaValue = pixel.copy(fontSize = 16.sp, lineHeight = 22.sp)

    /** Primary button labels — "send", "stop", "watch". */
    val ButtonLabel = pixel.copy(fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 1.sp)

    /** The dimmest tier — footers, "ready to catch", "flags at 20%". */
    val Footer = pixel.copy(fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.5.sp)

    /** The watching jar's live confidence numeral. */
    val Numeral = pixel.copy(fontSize = 36.sp, lineHeight = 44.sp)

    /**
     * Machine-generated values only — firefly timestamps and the watching jar's history
     * time column. The package deliberately drops out of the pixel face here and uses a
     * plain monospace, keeping Silkscreen for display type and a data font for readouts.
     */
    val Timestamp = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )
}
