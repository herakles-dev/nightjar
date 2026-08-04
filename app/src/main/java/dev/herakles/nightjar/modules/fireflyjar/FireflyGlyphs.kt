package dev.herakles.nightjar.modules.fireflyjar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.herakles.nightjar.picker.Module
import dev.herakles.nightjar.ui.theme.FireflyCreated
import dev.herakles.nightjar.ui.theme.FireflyReceived
import dev.herakles.nightjar.ui.theme.JarBgDusk
import dev.herakles.nightjar.ui.theme.JarGlassOutline
import dev.herakles.nightjar.ui.theme.JarWatchingDim
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Per-module jar glyph for the Firefly Jar shelf/detail screens (design/firefly-jar-identity.md,
 * Task #6). Same Canvas-based, stroke-only-silhouette-plus-motif technique as
 * [dev.herakles.nightjar.picker.ModuleGlyph]'s per-module draw functions, but the warm-palette
 * firefly-glow dots (not flat [TextSecondary] strokes) are the whole point here — this surface's
 * doctrine reverses "no shimmer/glow" on purpose.
 *
 * All four jars share one canonical jar silhouette (a shelf of matching jars, each holding a
 * different catch) — the module identity lives in the firefly arrangement/color, not the jar
 * shape. [stroke] is caller-supplied (expected: [JarGlassOutline]) so shelf/detail call sites can
 * vary emphasis (e.g. a selected tile) without this file hardcoding it.
 */
fun DrawScope.drawJarGlyph(module: Module, stroke: Color) {
    when (module) {
        Module.ACOUSTIC_MODEM -> drawSingingJarGlyph(stroke)
        Module.IMAGE_STEGANOGRAPHY -> drawFramedJarGlyph(stroke)
        Module.DETECTOR -> drawWatchingJarGlyph(stroke)
        Module.AUDIO_STEGANOGRAPHY -> drawHummingJarGlyph(stroke)
    }
}

/** the singing jar (ACOUSTIC_MODEM, CREATION): three fireflies at uneven heights, echoing
 *  the technical singing-jar's sound-bar glyph ([dev.herakles.nightjar.picker.drawWaveformGlyph])
 *  — alternating created/received since the modem both sends and receives a signal. */
private fun DrawScope.drawSingingJarGlyph(stroke: Color) {
    val strokeWidth = size.minDimension * 0.07f
    drawJarOutline(stroke, strokeWidth)
    val body = jarBodyBounds()
    val dotRadius = size.minDimension * 0.05f
    val xFractions = listOf(0.32f, 0.5f, 0.68f)
    val heightFractions = listOf(0.32f, 0.58f, 0.42f)
    val colors = listOf(FireflyCreated, FireflyReceived, FireflyCreated)
    xFractions.forEachIndexed { i, xf ->
        val center = Offset(
            x = body.left + body.width * xf,
            y = body.bottom - body.height * heightFractions[i],
        )
        drawFireflyDot(center, colors[i], dotRadius)
    }
}

/** the framed jar (IMAGE_STEGANOGRAPHY, CREATION): two fireflies on a diagonal, echoing the
 *  technical framed-jar's pixel-grid glyph ([dev.herakles.nightjar.picker.drawGridGlyph])
 *  collapsed to its two occupied corners — one hidden (created), one found (received). */
private fun DrawScope.drawFramedJarGlyph(stroke: Color) {
    val strokeWidth = size.minDimension * 0.07f
    drawJarOutline(stroke, strokeWidth)
    val body = jarBodyBounds()
    val dotRadius = size.minDimension * 0.05f
    drawFireflyDot(
        center = Offset(body.left + body.width * 0.34f, body.top + body.height * 0.36f),
        glowColor = FireflyReceived,
        radius = dotRadius,
    )
    drawFireflyDot(
        center = Offset(body.left + body.width * 0.66f, body.top + body.height * 0.66f),
        glowColor = FireflyCreated,
        radius = dotRadius,
    )
}

/** the humming jar (AUDIO_STEGANOGRAPHY, CREATION): three fireflies riding a gentle sine arc,
 *  echoing the technical humming-jar's sine-wave glyph ([dev.herakles.nightjar.picker.drawAudioStegoGlyph]). */
private fun DrawScope.drawHummingJarGlyph(stroke: Color) {
    val strokeWidth = size.minDimension * 0.07f
    drawJarOutline(stroke, strokeWidth)
    val body = jarBodyBounds()
    val dotRadius = size.minDimension * 0.045f
    val xFractions = listOf(0.3f, 0.5f, 0.7f)
    val colors = listOf(FireflyCreated, FireflyReceived, FireflyCreated)
    xFractions.forEachIndexed { i, xf ->
        val phase = (xf - 0.5f) * PI.toFloat()
        val center = Offset(
            x = body.left + body.width * xf,
            y = body.top + body.height * 0.5f - sin(phase) * body.height * 0.18f,
        )
        drawFireflyDot(center, colors[i], dotRadius)
    }
}

/** the watching jar (DETECTOR, WATCHING): no firefly glow at all — a dim passive ring with one
 *  radial tick in [JarWatchingDim], echoing the technical watching-jar's sweep/crosshair glyph
 *  ([dev.herakles.nightjar.picker.drawSweepGlyph]). Deliberately muted against the other three
 *  jars' warm glow: this jar only ever watches, it never catches. */
private fun DrawScope.drawWatchingJarGlyph(stroke: Color) {
    val strokeWidth = size.minDimension * 0.07f
    drawJarOutline(stroke, strokeWidth)
    val body = jarBodyBounds()
    val center = Offset(body.left + body.width / 2f, body.top + body.height / 2f)
    val radius = body.width * 0.24f
    val ringStroke = strokeWidth * 0.75f
    drawCircle(color = JarWatchingDim, radius = radius, center = center, style = Stroke(width = ringStroke))
    val angle = Math.toRadians(-50.0)
    val tickEnd = Offset(
        x = center.x + radius * cos(angle).toFloat(),
        y = center.y + radius * sin(angle).toFloat(),
    )
    drawLine(color = JarWatchingDim, start = center, end = tickEnd, strokeWidth = ringStroke, cap = StrokeCap.Round)
}

/** The shared jar silhouette: a narrow lid over a wider rounded body, stroke-only (no fill) —
 *  matches the technical glyphs' one-shape-one-stroke discipline even though the palette and
 *  motion doctrine differ on this surface. */
private fun DrawScope.drawJarOutline(color: Color, strokeWidth: Float) {
    val w = size.width
    val h = size.height
    val lidWidth = w * 0.46f
    val lidHeight = h * 0.16f
    val lidLeft = (w - lidWidth) / 2f
    val lidTop = h * 0.06f
    drawRoundRect(
        color = color,
        topLeft = Offset(lidLeft, lidTop),
        size = Size(lidWidth, lidHeight),
        cornerRadius = CornerRadius(strokeWidth, strokeWidth),
        style = Stroke(width = strokeWidth),
    )
    val body = jarBodyBounds()
    drawRoundRect(
        color = color,
        topLeft = Offset(body.left, body.top),
        size = Size(body.width, body.height),
        cornerRadius = CornerRadius(body.width * 0.22f, body.width * 0.22f),
        style = Stroke(width = strokeWidth),
    )
}

/** The jar body's interior bounds, shared by [drawJarOutline] and every per-module firefly
 *  layout function so dots are placed relative to the same rect the outline actually draws. */
private fun DrawScope.jarBodyBounds(): Rect {
    val w = size.width
    val h = size.height
    val lidTop = h * 0.06f
    val lidHeight = h * 0.16f
    val bodyWidth = w * 0.64f
    val bodyLeft = (w - bodyWidth) / 2f
    val bodyTop = lidTop + lidHeight * 0.7f
    val bodyBottom = h * 0.94f
    return Rect(bodyLeft, bodyTop, bodyLeft + bodyWidth, bodyBottom)
}

/** One firefly: a soft halo (25% alpha, 2.4x radius) behind a solid core — a cheap, static
 *  stand-in for the real screens' [rememberInfiniteTransition] blink (design/firefly-jar-identity.md
 *  § Motion); this glyph doesn't animate, it's a fixed row/tile icon. */
private fun DrawScope.drawFireflyDot(center: Offset, glowColor: Color, radius: Float) {
    drawCircle(color = glowColor.copy(alpha = 0.28f), radius = radius * 2.4f, center = center)
    drawCircle(color = glowColor, radius = radius, center = center)
}

@Preview(name = "Firefly Jar Glyphs", showBackground = true, backgroundColor = 0xFF161229)
@Composable
private fun FireflyJarGlyphsPreview() {
    Row(
        modifier = Modifier
            .background(JarBgDusk)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Module.entries.forEach { module ->
            Canvas(modifier = Modifier.size(48.dp)) {
                drawJarGlyph(module = module, stroke = JarGlassOutline)
            }
        }
    }
}
