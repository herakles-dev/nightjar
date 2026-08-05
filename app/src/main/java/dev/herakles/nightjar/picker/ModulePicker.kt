package dev.herakles.nightjar.picker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.herakles.nightjar.ui.theme.TextPrimary
import dev.herakles.nightjar.ui.theme.TextSecondary
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A module's role on the Firefly Jar shelf: does selecting it catch a new firefly
 *  (CREATION — the module embeds/transmits) or does it just watch for one (WATCHING —
 *  the module is a passive one-way analyzer, never creates fireflies)? See
 *  design/firefly-jar-identity.md. */
enum class JarRole { CREATION, WATCHING }

/**
 * The three research modules nightjar demos (spec.md Boundaries). Order is the home
 * screen's row order: Module 3 (the primary gate-2 phone-to-phone demo) first, then
 * Module 1, then Module 5. Real content lands in later tasks — this task only wires
 * navigation to a stub per module.
 *
 * Task #28: added [description] — a one-line, first-run answer to "what does this
 * module do" (real feedback: a first-time user had no idea what any of the three rows
 * led to before tapping). Plain lowercase fact, no marketing, matches the voice
 * contract's settings-label register.
 *
 * Task #4 (Firefly Jar v3): added [jarName]/[jarRole] — the disguise-themed name and
 * shelf role shown on the Firefly Jar surface (design/firefly-jar-identity.md). Not used
 * by [ModulePicker] itself; consumed by the jar shelf/detail screens.
 *
 * Task #16 (design refresh): added [jarChannel] — the one-word carrier a firefly
 * travelled over, for the firefly-detail popup's metadata row (DESIGN_SPEC.md §5 1c).
 * It lives here for the same reason [jarName] does: architecture.md § 6 allows exactly
 * two exhaustive per-module branches in the app, and the detail screen is not one of
 * them, so a display string it needs has to arrive as enum data rather than a `when`.
 */
enum class Module(
    val label: String,
    val description: String,
    val jarName: String,
    val jarRole: JarRole,
    val jarChannel: String,
) {
    ACOUSTIC_MODEM("acoustic modem", "send text as sound, phone to phone", "the singing jar", JarRole.CREATION, "sound"),
    IMAGE_STEGANOGRAPHY("image steganography", "hide or extract text inside an image", "the framed jar", JarRole.CREATION, "a picture"),
    DETECTOR("detector", "continuously listens for the modem's signal", "the watching jar", JarRole.WATCHING, "the air"),
    AUDIO_STEGANOGRAPHY("audio steganography", "hide or extract text inside audio", "the humming jar", JarRole.CREATION, "a recording"),
}

/**
 * Home screen. Dense, scannable list — no cards, no icons, no dividers (matches the
 * whisper-voice-app Zeus-picker row precedent). Exactly 4 rows; nothing to sort by
 * frequency yet since the list is fixed at this scope.
 */
@Composable
fun ModulePicker(onSelect: (Module) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "nightjar",
            style = MaterialTheme.typography.displayLarge,
            color = TextPrimary,
            modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        Module.entries.forEach { module ->
            ModuleRow(module = module, onClick = { onSelect(module) })
        }
    }
}

@Composable
private fun ModuleRow(module: Module, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModuleGlyph(module = module)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = module.label,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
            Text(
                text = module.description,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
    }
}

private val GlyphSize = 20.dp
private val GlyphStroke = 1.6.dp

/**
 * Per-module glyph, secondary to the row label (text stays primary hierarchy — the
 * label/description pair carries all the actual information). Canvas-drawn flat
 * monochrome shapes in [TextSecondary], not a Material icon-pack glyph (those read
 * as generic Material 3) and not a literal/skeuomorphic pictogram (no mic photo-icon,
 * no photo-frame-with-mountain, no magnifying-glass-with-sparkle). One shape, one
 * stroke weight, no fill, no gradient — matches the app icon's own flat-shape
 * discipline (see design/icon.md).
 */
@Composable
private fun ModuleGlyph(module: Module) {
    Canvas(modifier = Modifier.size(GlyphSize)) {
        val stroke = GlyphStroke.toPx()
        when (module) {
            Module.ACOUSTIC_MODEM -> drawWaveformGlyph(stroke)
            Module.IMAGE_STEGANOGRAPHY -> drawGridGlyph(stroke)
            Module.DETECTOR -> drawSweepGlyph(stroke)
            Module.AUDIO_STEGANOGRAPHY -> drawAudioStegoGlyph(stroke)
        }
    }
}

/** Acoustic modem: a schematic signal-level tick, not a literal speaker/waveform icon. */
private fun DrawScope.drawWaveformGlyph(stroke: Float) {
    val heightFractions = listOf(0.4f, 0.7f, 1f, 0.7f, 0.4f)
    val barSpacing = size.width / heightFractions.size
    heightFractions.forEachIndexed { index, fraction ->
        val cx = barSpacing * (index + 0.5f)
        val barHeight = size.height * fraction
        val top = (size.height - barHeight) / 2f
        drawLine(
            color = TextSecondary,
            start = Offset(cx, top),
            end = Offset(cx, top + barHeight),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** Image steganography: a subdivided block — pixel/frequency grid, not a photo frame. */
private fun DrawScope.drawGridGlyph(stroke: Float) {
    val inset = size.width * 0.12f
    val left = inset
    val top = inset
    val right = size.width - inset
    val bottom = size.height - inset
    val midX = size.width / 2f
    val midY = size.height / 2f

    drawRect(
        color = TextSecondary,
        topLeft = Offset(left, top),
        size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
        style = Stroke(width = stroke),
    )
    drawLine(TextSecondary, Offset(midX, top), Offset(midX, bottom), strokeWidth = stroke)
    drawLine(TextSecondary, Offset(left, midY), Offset(right, midY), strokeWidth = stroke)
}

/** Detector: a ring with one radial tick — a passive sweep/crosshair mark, entirely
 *  inside the ring so it never reads as a magnifying glass (no handle protruding past
 *  the circumference). */
private fun DrawScope.drawSweepGlyph(stroke: Float) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = (size.minDimension / 2f) - stroke
    drawCircle(color = TextSecondary, radius = radius, center = center, style = Stroke(width = stroke))
    val angle = Math.toRadians(-50.0)
    val tickEnd = Offset(
        x = center.x + radius * cos(angle).toFloat(),
        y = center.y + radius * sin(angle).toFloat(),
    )
    drawLine(color = TextSecondary, start = center, end = tickEnd, strokeWidth = stroke, cap = StrokeCap.Round)
}

/** Audio steganography: a single-period sine wave with a small hollow square centered on it —
 *  evokes "something hidden inside the audio," distinct from the modem's discrete
 *  waveform-bars glyph ([drawWaveformGlyph]) and not a literal speaker/headphone pictogram. */
private fun DrawScope.drawAudioStegoGlyph(stroke: Float) {
    val segments = 20
    val amplitude = size.height * 0.28f
    val midY = size.height / 2f
    val step = size.width / segments
    for (i in 0 until segments) {
        val angleStart = 2.0 * PI * i / segments
        val angleEnd = 2.0 * PI * (i + 1) / segments
        drawLine(
            color = TextSecondary,
            start = Offset(step * i, midY - amplitude * sin(angleStart).toFloat()),
            end = Offset(step * (i + 1), midY - amplitude * sin(angleEnd).toFloat()),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
    val squareSize = size.width * 0.22f
    val squareLeft = (size.width - squareSize) / 2f
    val squareTop = midY - squareSize / 2f
    drawRect(
        color = TextSecondary,
        topLeft = Offset(squareLeft, squareTop),
        size = androidx.compose.ui.geometry.Size(squareSize, squareSize),
        style = Stroke(width = stroke),
    )
}
