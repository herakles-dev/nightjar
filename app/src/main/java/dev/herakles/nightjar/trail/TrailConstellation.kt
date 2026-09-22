package dev.herakles.nightjar.trail

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.herakles.nightjar.R
import dev.herakles.nightjar.ui.theme.FireflyCreated
import dev.herakles.nightjar.ui.theme.JarTextTertiary
import dev.herakles.nightjar.ui.theme.JarType
import dev.herakles.nightjar.ui.theme.JarWatchingDim

/** True once every step in [TrailStep.ORDER] is in [TrailState.completedSteps] -- the trail's
 *  finale condition (design/riddle-trail.md § "Reward lines": "at the finale all six dots are
 *  lit together"). */
internal fun isTrailComplete(state: TrailState): Boolean =
    state.completedSteps.containsAll(TrailStep.ORDER)

/**
 * W2-5 (design/riddle-trail.md § "Progress constellation"): whether the shelf's progress
 * constellation should render at all. Gated on [TrailState.welcomeSeen] first -- the
 * constellation is part of the same "the trail has actually started" chrome the shelf's
 * tile-glow/wordmark-hint already hides before `begin` (see [TrailStateStore.markWelcomeSeen]'s
 * KDoc) -- then visible while a step is actively glowing (not [TrailState.skipped]), or, once
 * every step is done, until the finale panel is dismissed.
 */
internal fun trailConstellationVisible(state: TrailState): Boolean {
    if (!state.welcomeSeen) return false
    val active = state.currentStep != null && !state.skipped
    return active || (isTrailComplete(state) && !state.finaleDismissed)
}

/** design/riddle-trail.md: "one soft halo pulse (~800 ms)". */
private const val CONSTELLATION_PULSE_DURATION_MS = 800

private val DOT_SIZE = 10.dp

/**
 * The shelf's progress constellation (design/riddle-trail.md § "Progress constellation", spec.md
 * gate-40): six small firefly dots (one per [TrailStep.ORDER]) plus `trail`/`N of 6`, under the
 * wordmark. A completed step's dot renders lit ([FireflyCreated]); the rest render dim
 * ([JarWatchingDim]), matching every other "not yet reached" treatment on this surface.
 *
 * The instant [TrailState.lastCompletedStep] is non-null, that step's dot plays one
 * [CONSTELLATION_PULSE_DURATION_MS]ms halo pulse -- static (no animation, just the dot's own lit
 * color) when [ValueAnimator.areAnimatorsEnabled] is false, per design/firefly-jar-identity.md's
 * v6 addendum "static under reduce-motion" rule. Either way, [onCompletionAcknowledged] fires
 * once the pulse (or its reduce-motion no-op) is done, so the same completion never re-pulses on
 * a later recomposition (`TrailStateStore.acknowledgeCompletion`).
 *
 * Once every step is done, the header row is joined by the finale panel: the workshop's own
 * reward line (design doc's "the trail is done. every jar is yours now.") and `close`
 * ([onCloseFinale] -> `TrailStateStore.dismissFinale`), which hides this whole composable on the
 * next recomposition.
 *
 * A pure no-op when [trailConstellationVisible] is false for [state] -- same idiom
 * [TrailWelcomeCard]/[trailHighlight] already establish.
 */
@Composable
fun TrailConstellation(
    state: TrailState,
    onCompletionAcknowledged: () -> Unit,
    onCloseFinale: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!trailConstellationVisible(state)) return

    val pulseProgress = remember { Animatable(0f) }
    LaunchedEffect(state.lastCompletedStep) {
        val justCompleted = state.lastCompletedStep
        if (justCompleted != null) {
            if (ValueAnimator.areAnimatorsEnabled()) {
                pulseProgress.snapTo(1f)
                pulseProgress.animateTo(0f, animationSpec = tween(durationMillis = CONSTELLATION_PULSE_DURATION_MS))
            } else {
                pulseProgress.snapTo(0f)
            }
            onCompletionAcknowledged()
        }
    }

    val completedCount = state.completedSteps.size
    val total = TrailStep.ORDER.size
    val trailLabel = stringResource(R.string.trail_constellation_label)
    val countLabel = stringResource(R.string.trail_constellation_count, completedCount, total)
    val talkbackLabel = stringResource(R.string.trail_constellation_content_description, completedCount, total)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = talkbackLabel },
        ) {
            TrailStep.ORDER.forEach { step ->
                ConstellationDot(
                    lit = step in state.completedSteps,
                    pulseProgress = if (step == state.lastCompletedStep) pulseProgress.value else 0f,
                )
            }
            Text(
                text = trailLabel,
                style = JarType.Footer,
                color = JarTextTertiary,
                modifier = Modifier.padding(start = 4.dp),
            )
            Text(text = countLabel, style = JarType.Footer, color = JarTextTertiary)
        }

        if (isTrailComplete(state) && !state.finaleDismissed) {
            Text(text = stringResource(trailRewardRes(TrailStep.WORKSHOP)), style = JarType.Footer, color = FireflyCreated)
            Text(
                text = stringResource(R.string.trail_finale_close),
                style = JarType.Footer,
                color = JarTextTertiary,
                modifier = Modifier.clickable(onClick = onCloseFinale),
            )
        }
    }
}

/** One constellation dot: a small filled circle, lit amber or dim lavender, plus [pulseProgress]
 *  (0f..1f, 0f = no pulse) drawing an expanding, fading halo behind it -- the same
 *  radial-gradient-behind-a-glyph shape [trailHighlight] and `drawAmbientHalo`
 *  (`FireflyGlyphs.kt`) already use, at one-shot-pulse scale rather than continuous breathing. */
@Composable
private fun ConstellationDot(lit: Boolean, pulseProgress: Float) {
    Canvas(modifier = Modifier.size(DOT_SIZE)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = size.minDimension / 2f * 0.55f
        if (pulseProgress > 0f) {
            val haloRadius = size.minDimension / 2f * (1f + pulseProgress)
            drawCircle(
                brush = Brush.radialGradient(
                    0f to FireflyCreated.copy(alpha = (pulseProgress * 0.55f).coerceIn(0f, 1f)),
                    0.7f to Color.Transparent,
                    center = center,
                    radius = haloRadius,
                ),
                radius = haloRadius,
                center = center,
            )
        }
        drawCircle(
            color = if (lit) FireflyCreated else JarWatchingDim,
            radius = baseRadius,
            center = center,
            alpha = if (lit) 0.95f else 0.4f,
        )
    }
}
