package dev.herakles.nightjar.modules.fireflyjar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.herakles.nightjar.ui.theme.FireflyCreated
import dev.herakles.nightjar.ui.theme.FireflyReceived
import dev.herakles.nightjar.ui.theme.JarSkyHorizon
import dev.herakles.nightjar.ui.theme.JarSkyLower
import dev.herakles.nightjar.ui.theme.JarSkyStops
import dev.herakles.nightjar.ui.theme.JarSkyUpper
import dev.herakles.nightjar.ui.theme.JarSkyZenith
import kotlin.math.cos
import kotlin.math.sin

/**
 * The shared backdrop behind every jar screen: a four-stop night sky, 25 slowly
 * twinkling stars, and 20 fireflies drifting somewhere far off. All of it is a
 * continuous function of [rememberFireflyClock]'s elapsed seconds — see
 * `sessions/nightjar/artifacts/design-refresh/DESIGN_SPEC.md` §4.1 and §4.2 for the
 * source formulas.
 *
 * The distant fireflies here are pure atmosphere and carry no data — the ones that mean
 * something live inside the jars ([JarGlyph]). They're held at a quarter alpha so the
 * two layers never compete.
 */
@Composable
fun JarNightSky(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // This is the screen's root clock — published via LocalFireflyClock so the jars, swarm dots
    // and hero fireflies inside content() all tick off this one loop instead of each starting
    // their own. Held as State and read inside the draw lambda, never in this composable's body:
    // this wraps content(), so a composition-phase read would recompose the whole screen every
    // frame rather than just redrawing the sky.
    val clock = rememberStandaloneFireflyClock()

    CompositionLocalProvider(LocalFireflyClock provides clock) {
        Box(
            modifier = modifier.background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        JarSkyStops[0] to JarSkyZenith,
                        JarSkyStops[1] to JarSkyUpper,
                        JarSkyStops[2] to JarSkyLower,
                        JarSkyStops[3] to JarSkyHorizon,
                    ),
                ),
            ),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val t = clock.value
                drawStars(t)
                drawDistantFireflies(t)
            }
            content()
        }
    }
}

/**
 * 25 stars on a fixed pseudo-random lattice — the modular arithmetic is the design
 * package's, kept verbatim so the constellation matches the mockup exactly rather than
 * being re-rolled from a random seed.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStars(t: Float) {
    for (i in 0 until 25) {
        val x = ((i * 137 + 29) % 100) / 100f * size.width
        val y = ((i * 73 + 11) % 85) / 100f * size.height
        val radius = (1 + (i % 2)).toFloat() * density / 2f
        val alpha = 0.12f + 0.2f * (0.5f + 0.5f * sin(t * 0.3f + i * 0.8f))
        drawCircle(color = Color.White, radius = radius, center = Offset(x, y), alpha = alpha)
    }
}

/**
 * 20 background fireflies, one in three cyan. Each drifts on three layered waves per
 * axis and flashes twice per cycle — a bright flash, dark, then a shorter dimmer one.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDistantFireflies(t: Float) {
    for (i in 0 until 20) {
        val id = 50 + i
        val baseX = (((i * 47 + 13) % 92) + 4) / 100f * size.width
        val baseY = (((i * 31 + 7) % 88) + 6) / 100f * size.height

        val dx = sin(t * 0.12f + id * 2.3f) * 8f +
            sin(t * 0.07f + id * 1.1f) * 5f +
            sin(t * 0.03f + id * 4.2f) * 3f
        val dy = cos(t * 0.1f + id * 3.1f) * 7f +
            cos(t * 0.05f + id * 0.7f) * 4f +
            cos(t * 0.02f + id * 2.8f) * 3f

        val alpha = distantBlink(id, t) * 0.25f
        if (alpha <= 0.02f) continue

        val color = if (i % 3 == 0) FireflyReceived else FireflyCreated
        val center = Offset(baseX + dx * density, baseY + dy * density)
        val glow = (2f + alpha * 6f) * density

        drawCircle(color = color, radius = glow * 2f, center = center, alpha = alpha * 0.25f)
        drawCircle(color = color, radius = glow, center = center, alpha = alpha)
    }
}

/**
 * The two-flash blink cycle, verbatim from DESIGN_SPEC.md §4.2 — a fast ramp to full, a
 * hold, a fade, a long dark stretch, then a second flash at 60% before going dark again.
 * Period varies per firefly so the field never pulses in unison.
 *
 * `internal` rather than `private`: [FireflyGlyphs]'s meadow render (v6 addendum, the
 * DETECTOR/"watching" module's open-field tile) reuses this same primitive for its own
 * distant-firefly dots rather than inventing a second blink function.
 */
internal fun distantBlink(id: Int, t: Float): Float {
    val period = 4f + (id % 7) * 0.8f
    val phase = ((t / period) + id * 0.29f).mod(1f)
    return when {
        phase < 0.03f -> phase / 0.03f
        phase < 0.08f -> 1f
        phase < 0.18f -> 1f - (phase - 0.08f) / 0.1f
        phase < 0.55f -> 0f
        phase < 0.58f -> (phase - 0.55f) / 0.03f * 0.6f
        phase < 0.62f -> 0.6f
        phase < 0.72f -> 0.6f * (1f - (phase - 0.62f) / 0.1f)
        else -> 0f
    }
}
