package dev.herakles.nightjar.modules.fireflyjar

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.herakles.nightjar.picker.Module
import dev.herakles.nightjar.ui.theme.FireflyCreated
import dev.herakles.nightjar.ui.theme.FireflyReceived
import dev.herakles.nightjar.ui.theme.JarBgDusk
import dev.herakles.nightjar.ui.theme.JarBodyFill
import dev.herakles.nightjar.ui.theme.JarGlassEmpty
import dev.herakles.nightjar.ui.theme.JarGlassFull
import dev.herakles.nightjar.ui.theme.JarGlassHighlight
import dev.herakles.nightjar.ui.theme.JarGlassOutline
import dev.herakles.nightjar.ui.theme.JarGlassPartial
import dev.herakles.nightjar.ui.theme.JarLidKnob
import dev.herakles.nightjar.ui.theme.JarLidKnobStroke
import dev.herakles.nightjar.ui.theme.JarLidRim
import dev.herakles.nightjar.ui.theme.JarLidRimStroke
import dev.herakles.nightjar.ui.theme.JarWatchingDim
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin

/**
 * Parametric, frame-driven jar/firefly render engine (design-refresh DESIGN_SPEC.md §4.3,
 * §4.4, §4.7, §6 — "Cozy Pixel Night", Task #12). Every visual is a continuous function of
 * elapsed time `t` and a per-firefly integer `id`, recomputed fresh each frame — there is no
 * keyframe set and no pose state machine, matching the source mockup's own
 * `requestAnimationFrame`/sine-wave technique (§6, §8 delta 4).
 *
 * The jar outline (56×68 viewBox) is a single canonical shape shared by every non-watching
 * module; module identity now lives entirely in which [FireflyVisual]s a call site passes in,
 * not in a per-module draw function. The DETECTOR module (shown as "the meadow" since v6) is
 * the one bespoke exception: no jar glass at all, an open field instead (§ the v6 addendum,
 * "The meadow tile — dropping the jar glass", design/firefly-jar-identity.md; owner decision
 * 2026-09-22) — a grass-line horizon and a few `distantBlink`-driven dots, dim cool tones only.
 */
data class FireflyVisual(val id: Int, val color: Color)

private const val VIEWBOX_WIDTH = 56f
private const val VIEWBOX_HEIGHT = 68f

// §4.3 jar interior bounds, in viewBox units — standard (shelf tile) vs. large (detail hero).
private val STANDARD_FIREFLY_BOUNDS = Rect(left = 12f, top = 14f, right = 42f, bottom = 54f)
private val LARGE_FIREFLY_BOUNDS = Rect(left = 10f, top = 14f, right = 46f, bottom = 64f)

/**
 * The screen's shared frame clock, published by [JarNightSky]. Everything that breathes reads
 * this rather than starting its own loop: a jar detail screen holds one hero jar plus a dot per
 * caught firefly, and per-composable clocks would mean a coroutine count that grows with how
 * much the user has collected.
 *
 * Null when nothing has provided one — a lone [JarGlyph] in a `@Preview`, say — in which case
 * [rememberFireflyClock] falls back to a private clock so the glyph still animates.
 */
val LocalFireflyClock = staticCompositionLocalOf<State<Float>?> { null }

/** The clock every animated jar composable should use. Prefers the screen's shared one. */
@Composable
fun rememberFireflyClock(): State<Float> =
    LocalFireflyClock.current ?: rememberStandaloneFireflyClock()

/** Elapsed seconds since first composition, ticking every frame via [withFrameNanos] — the
 *  single time source every position/alpha function in this file is a pure function of. Call
 *  this directly only to establish a screen's root clock; everything else wants
 *  [rememberFireflyClock]. */
@Composable
fun rememberStandaloneFireflyClock(): State<Float> {
    val elapsedSeconds = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val startNanos = withFrameNanos { it }
        while (true) {
            withFrameNanos { frameNanos -> elapsedSeconds.floatValue = (frameNanos - startNanos) / 1_000_000_000f }
        }
    }
    return elapsedSeconds
}

/** §4.3 position function — a firefly drifting through [bounds] (viewBox space) on three
 *  summed sine/cosine terms, phase-offset per [id] so no two fireflies ever move in lockstep. */
fun fireflyPos(id: Int, t: Float, bounds: Rect): Offset {
    val s1 = sin(t * 0.7f + id * 2.1f) * 0.35f
    val s2 = sin(t * 1.3f + id * 4.7f) * 0.2f
    val s3 = sin(t * 0.3f + id * 1.3f) * 0.15f
    val c1 = cos(t * 0.5f + id * 3.2f) * 0.3f
    val c2 = cos(t * 1.1f + id * 5.1f) * 0.25f
    val c3 = cos(t * 0.2f + id * 0.9f) * 0.15f
    val nx = (s1 + s2 + s3) * 0.7f
    val ny = (c1 + c2 + c3) * 0.7f
    val centerX = bounds.left + bounds.width / 2f
    val centerY = bounds.top + bounds.height / 2f
    return Offset(
        x = centerX + nx * bounds.width * 0.35f,
        y = centerY + ny * bounds.height * 0.3f,
    )
}

/** §4.3 glow-breathing function — a soft cubic (not plain sine) shaping so the blink reads as
 *  a slow, uneven breath rather than a metronome pulse. Range 0.05..0.65. */
fun fireflyAlpha(id: Int, t: Float): Float {
    val period = 3.5f + (id % 5) * 0.6f
    val phase = (t / period + id * 0.37f).mod(1f)
    val wave = sin(phase * 2f * PI.toFloat())
    val smooth = wave * wave * sign(wave)
    return 0.35f + smooth * 0.3f
}

/** §4.3/§6 firefly render — 4 concentric rings plus a 4-point fading trail, 8 draw calls
 *  total. The core is always white, never [firefly]'s own color. */
private fun DrawScope.drawFirefly(firefly: FireflyVisual, t: Float, bounds: Rect) {
    val alpha = fireflyAlpha(firefly.id, t)
    val pos = fireflyPos(firefly.id, t, bounds)
    val glowRadius = 4f + alpha * 2f
    val coreRadius = 1f + alpha * 0.4f
    drawCircle(color = firefly.color, radius = glowRadius * 1.8f, center = pos, alpha = alpha * 0.06f)
    drawCircle(color = firefly.color, radius = glowRadius, center = pos, alpha = alpha * 0.18f)
    drawCircle(color = firefly.color, radius = glowRadius * 0.5f, center = pos, alpha = alpha * 0.35f)
    drawCircle(color = Color.White, radius = coreRadius, center = pos, alpha = alpha * 0.9f)
    for (ti in 1..4) {
        val trailPos = fireflyPos(firefly.id, t - ti * 0.15f, bounds)
        drawCircle(color = firefly.color, radius = glowRadius * 0.4f, center = trailPos, alpha = alpha * (0.08f / ti))
    }
}

/** §4.4 ambient halo behind the jar — a soft radial ellipse sized off the full call-site box
 *  (not the 56×68 viewBox), so it stays proportional whether this is a 56dp shelf icon or a
 *  140dp detail hero. Callers with zero fireflies never reach this function (§4.4: "no halo
 *  div at all"). */
private fun DrawScope.drawAmbientHalo(glowColor: Color, opacity: Float) {
    if (opacity <= 0f) return
    val haloWidth = size.width * 0.80f
    val haloHeight = size.height * 0.70f
    val haloLeft = size.width * 0.10f
    val haloTop = size.height * 0.15f
    val center = Offset(haloLeft + haloWidth / 2f, haloTop + haloHeight / 2f)
    scale(scaleX = haloWidth, scaleY = haloHeight, pivot = center) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to glowColor.copy(alpha = opacity.coerceIn(0f, 1f)),
                0.7f to Color.Transparent,
                center = center,
                radius = 0.5f,
            ),
            radius = 0.5f,
            center = center,
        )
    }
}

private inline fun DrawScope.withViewBoxScale(block: DrawScope.() -> Unit) {
    scale(scaleX = size.width / VIEWBOX_WIDTH, scaleY = size.height / VIEWBOX_HEIGHT, pivot = Offset.Zero, block = block)
}

/** No fullness threshold is given in DESIGN_SPEC.md §4/§6 — this ladder is this file's own
 *  reading of JarGlassFull/Partial/Empty's doc comment ("a fuller jar gets a fractionally
 *  brighter rim"). Revisit once JarShelfScreen/JarDetailScreen (Task #13/#14) carry real
 *  fullness data instead of a live firefly count. */
private fun jarGlassAccent(fireflyCount: Int): Color = when {
    fireflyCount <= 0 -> JarGlassEmpty
    fireflyCount <= 2 -> JarGlassPartial
    else -> JarGlassFull
}

/** §6 jar outline — verbatim shape stack, drawn back-to-front, in the 56×68 viewBox space.
 *  [glassAccent] is the one caller-varying value (the jar's fullness-tinted glass stroke);
 *  everything else is a fixed Cozy Pixel Night token. */
private fun DrawScope.drawJarOutlineShape(glassAccent: Color) {
    // Knob on top
    drawCircle(color = JarLidKnob, radius = 3f, center = Offset(28f, 6f))
    drawCircle(color = JarLidKnobStroke, radius = 3f, center = Offset(28f, 6f), style = Stroke(width = 0.6f))
    drawCircle(color = JarGlassHighlight, radius = 1f, center = Offset(27f, 5f), alpha = 0.12f)

    // Lid rim
    drawRoundRect(color = JarLidRim, topLeft = Offset(15f, 8f), size = Size(26f, 3.5f), cornerRadius = CornerRadius(1.5f, 1.5f))
    drawRoundRect(
        color = JarLidRimStroke,
        topLeft = Offset(15f, 8f),
        size = Size(26f, 3.5f),
        cornerRadius = CornerRadius(1.5f, 1.5f),
        style = Stroke(width = 0.6f),
    )
    drawRoundRect(
        color = JarGlassHighlight,
        topLeft = Offset(16f, 8.5f),
        size = Size(24f, 1.2f),
        cornerRadius = CornerRadius(0.6f, 0.6f),
        alpha = 0.05f,
    )

    // Jar body
    drawRoundRect(color = JarBodyFill, topLeft = Offset(10f, 12f), size = Size(36f, 50f), cornerRadius = CornerRadius(6f, 6f))
    drawRoundRect(
        color = glassAccent,
        topLeft = Offset(10f, 12f),
        size = Size(36f, 50f),
        cornerRadius = CornerRadius(6f, 6f),
        style = Stroke(width = 1.2f),
    )
    drawRoundRect(
        color = JarGlassHighlight,
        topLeft = Offset(12f, 14f),
        size = Size(32f, 46f),
        cornerRadius = CornerRadius(5f, 5f),
        style = Stroke(width = 0.5f),
        alpha = 6f / 255f, // #ffffff06
    )

    // Reflections — all near-zero-opacity glass highlights, never the accent color.
    val leftReflection = Path().apply {
        moveTo(14f, 18f)
        quadraticTo(13f, 18f, 13f, 20f)
        lineTo(13f, 52f)
        quadraticTo(13f, 54f, 14f, 54f)
    }
    drawPath(leftReflection, color = JarGlassHighlight, alpha = 0.08f, style = Stroke(width = 1.5f, cap = StrokeCap.Round))
    drawLine(color = JarGlassHighlight, start = Offset(16f, 22f), end = Offset(16f, 48f), strokeWidth = 0.6f, cap = StrokeCap.Round, alpha = 0.05f)
    val topCurveReflection = Path().apply {
        moveTo(18f, 14f)
        quadraticTo(28f, 11f, 38f, 14f)
    }
    drawPath(topCurveReflection, color = JarGlassHighlight, alpha = 0.06f, style = Stroke(width = 0.6f))
    drawOval(color = JarGlassHighlight, topLeft = Offset(15f, 57f), size = Size(26f, 4f), alpha = 0.03f)
    drawLine(color = JarGlassHighlight, start = Offset(42f, 25f), end = Offset(42f, 50f), strokeWidth = 0.4f, cap = StrokeCap.Round, alpha = 0.04f)
}

/** viewBox-unit endpoints for the meadow's grass-line horizon — a few short, irregular
 *  strokes rather than one flat line, per design/firefly-jar-identity.md's v6 addendum and the
 *  2026-09-22 owner decision ("a grass-line horizon"). Fixed hand-placed art, not randomized
 *  per frame — the same "one shape, quiet" restraint [drawJarOutlineShape]'s own hand-placed
 *  offsets already hold themselves to. */
private val MEADOW_GRASS_STROKES = listOf(
    Offset(7f, 50f) to Offset(17f, 47f),
    Offset(15f, 52f) to Offset(26f, 49f),
    Offset(24f, 48f) to Offset(34f, 51f),
    Offset(32f, 51f) to Offset(43f, 48f),
    Offset(41f, 49f) to Offset(50f, 52f),
)

/** viewBox-unit positions for the meadow's distant blinking fireflies, hovering in the open
 *  sky above [MEADOW_GRASS_STROKES] — a fixed field (not randomized), paired with distinct
 *  [distantBlink] ids so the five never flash in lockstep. */
private val MEADOW_DOTS = listOf(
    201 to Offset(13f, 32f),
    204 to Offset(23f, 18f),
    208 to Offset(32f, 28f),
    212 to Offset(41f, 15f),
    216 to Offset(20f, 41f),
)

/** With system animations off, [distantBlink] would otherwise leave some dots sitting at
 *  its "long dark stretch" phase depending on where the frame clock happens to be — reading as
 *  missing fireflies rather than a still frame of the same field. This mid alpha keeps every
 *  dot visibly present, and non-blinking, when that's the case. */
private const val MEADOW_DOT_REDUCED_MOTION_BLINK = 0.6f

/** §6/v6 addendum meadow — the open-field replacement for the old jar-glass "watching jar"
 *  render (design/firefly-jar-identity.md § "The meadow tile — dropping the jar glass"; owner
 *  decision 2026-09-22). No jar glass, no lid, no radar sweep: a low grass-line horizon plus a
 *  handful of [distantBlink]-driven dots, reusing [JarNightSky]'s own distant-firefly primitive
 *  rather than a bespoke new glow, in the meadow's cool/dim palette only — never warm parchment.
 *  [large] only nudges the dot glow up slightly for the detail-hero scale; the geometry itself
 *  doesn't need a second layout since [withViewBoxScale] already fits this whole box to
 *  whatever call-site size it's given (56×68 shelf tile through the larger detail hero). */
private fun DrawScope.drawMeadow(t: Float, large: Boolean) {
    for ((start, end) in MEADOW_GRASS_STROKES) {
        drawLine(color = JarWatchingDim, start = start, end = end, strokeWidth = 1.1f, cap = StrokeCap.Round, alpha = 0.35f)
    }

    val reducedMotion = !ValueAnimator.areAnimatorsEnabled()
    val dotRadius = if (large) 1.6f else 1.3f
    for ((id, pos) in MEADOW_DOTS) {
        val blink = if (reducedMotion) MEADOW_DOT_REDUCED_MOTION_BLINK else distantBlink(id, t)
        if (blink <= 0.02f) continue
        val alpha = (blink * 0.5f).coerceIn(0f, 1f)
        drawCircle(color = JarWatchingDim, radius = dotRadius * 2.2f, center = pos, alpha = alpha * 0.3f)
        drawCircle(color = JarWatchingDim, radius = dotRadius, center = pos, alpha = alpha)
    }
}

private fun fireflyBounds(large: Boolean): Rect = if (large) LARGE_FIREFLY_BOUNDS else STANDARD_FIREFLY_BOUNDS

private fun DrawScope.drawJarGlyph(module: Module, fireflies: List<FireflyVisual>, t: Float, large: Boolean) {
    if (module == Module.DETECTOR) {
        withViewBoxScale { drawMeadow(t, large) }
        return
    }
    if (fireflies.isNotEmpty()) {
        // Summed in a loop rather than map{}.average(): this runs once per jar per frame, and
        // the intermediate list would be pure garbage at 60fps.
        var alphaSum = 0f
        for (firefly in fireflies) alphaSum += fireflyAlpha(firefly.id, t)
        val meanAlpha = alphaSum / fireflies.size
        drawAmbientHalo(glowColor = fireflies.first().color, opacity = meanAlpha * 0.15f)
    }
    val bounds = fireflyBounds(large)
    withViewBoxScale {
        drawJarOutlineShape(jarGlassAccent(fireflies.size))
        fireflies.forEach { drawFirefly(it, t, bounds) }
    }
}

/** Public entry point — screens drop this in at any size (56dp shelf tile through a 170dp
 *  detail hero) and it stays a live, breathing jar (or, for DETECTOR, meadow). [large] selects
 *  the wider firefly-roaming interior used by detail-hero call sites (§6). */
@Composable
fun JarGlyph(module: Module, fireflies: List<FireflyVisual>, modifier: Modifier = Modifier, large: Boolean = false) {
    val clock = rememberFireflyClock()
    Canvas(modifier = modifier) {
        drawJarGlyph(module, fireflies, clock.value, large)
    }
}

/** Legacy stroke-only entry point, kept for [JarShelfScreen]'s static (non-animated) jar
 *  icon — a single frame at t=0 with a representative firefly set per module. [stroke] drives
 *  the glass accent directly since this call site has no fullness data to pick a
 *  JarGlassFull/Partial/Empty rung from. */
fun DrawScope.drawJarGlyph(module: Module, stroke: Color) {
    if (module == Module.DETECTOR) {
        withViewBoxScale { drawMeadow(t = 0f, large = false) }
        return
    }
    withViewBoxScale {
        drawJarOutlineShape(stroke)
        defaultFireflies(module).forEach { drawFirefly(it, t = 0f, STANDARD_FIREFLY_BOUNDS) }
    }
}

/**
 * §4.5 — a single firefly out of its jar, for the firefly-detail popup. Same breathing
 * function as the rest ([fireflyAlpha] at `id = 40`) but held still and drawn at hero scale,
 * so the one you tapped reads as the subject of the screen rather than one of a crowd.
 *
 * Unlike [drawFirefly]'s viewBox-unit rings this sizes off the call-site box, and it has no
 * trail — nothing to trail behind when the firefly isn't moving.
 */
@Composable
fun HeroFirefly(color: Color = FireflyCreated, modifier: Modifier = Modifier) {
    val clock = rememberFireflyClock()
    Canvas(modifier = modifier) {
        val alpha = fireflyAlpha(40, clock.value)
        val center = Offset(size.width / 2f, size.height / 2f)
        val unit = minOf(size.width, size.height) / 80f

        drawCircle(
            brush = Brush.radialGradient(
                0f to color.copy(alpha = alpha * 0.16f),
                1f to Color.Transparent,
                center = center,
                radius = 40f * unit,
            ),
            radius = 40f * unit,
            center = center,
        )
        drawCircle(
            brush = Brush.radialGradient(
                0f to color.copy(alpha = alpha * 0.25f),
                1f to Color.Transparent,
                center = center,
                radius = 20f * unit,
            ),
            radius = 20f * unit,
            center = center,
        )
        drawCircle(
            brush = Brush.radialGradient(
                0f to color.copy(alpha = alpha * 0.5f),
                1f to Color.Transparent,
                center = center,
                radius = 10f * unit,
            ),
            radius = 10f * unit,
            center = center,
        )
        drawCircle(color = Color.White, radius = 4f * unit, center = center, alpha = alpha * 0.9f)
    }
}

private fun defaultFireflies(module: Module): List<FireflyVisual> = when (module) {
    Module.ACOUSTIC_MODEM -> listOf(
        FireflyVisual(0, FireflyCreated),
        FireflyVisual(1, FireflyReceived),
        FireflyVisual(2, FireflyCreated),
    )
    Module.IMAGE_STEGANOGRAPHY -> listOf(
        FireflyVisual(0, FireflyReceived),
        FireflyVisual(1, FireflyCreated),
    )
    Module.AUDIO_STEGANOGRAPHY -> listOf(
        FireflyVisual(0, FireflyCreated),
        FireflyVisual(1, FireflyReceived),
        FireflyVisual(2, FireflyCreated),
    )
    Module.DETECTOR -> emptyList()
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
