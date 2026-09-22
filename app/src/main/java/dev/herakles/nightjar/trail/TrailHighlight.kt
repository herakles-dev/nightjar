package dev.herakles.nightjar.trail

import android.animation.ValueAnimator
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import dev.herakles.nightjar.R
import dev.herakles.nightjar.modules.fireflyjar.fireflyAlpha
import dev.herakles.nightjar.modules.fireflyjar.rememberFireflyClock
import dev.herakles.nightjar.ui.theme.FireflyCreated

/**
 * Stable id [fireflyAlpha] breathes on for every trail highlight -- deliberately not tied to any
 * real firefly/record id, since exactly one target glows at a time and every one of
 * them shares this one steady breathing rhythm rather than each getting its own phase.
 */
private const val TRAIL_HIGHLIGHT_BREATH_ID = -1

/** [fireflyAlpha]'s own documented range is 0.35 +- 0.3 (0.05..0.65); this is its time-average --
 *  the flat value drawn under reduce-motion so a static highlight doesn't read louder than the
 *  moving one ever did ("Static under reduce-motion"). */
private const val REDUCED_MOTION_ALPHA = 0.5f

/** Peak alpha of the halo at the breathing curve's own peak (0.65) -- kept well under 1 so the
 *  glow reads as a soft aura behind the target, never a solid highlight fill. */
private const val HALO_PEAK_ALPHA = 0.4f

/** How far past the target's own bounds the halo's gradient radius reaches, so it visibly
 *  extends beyond the edges of whatever it's drawn behind rather than clipping flush to them. */
private const val HALO_RADIUS_SCALE = 1.15f

/**
 * Marks whatever this [Modifier] is chained onto as the riddle trail's one "next thing to try"
 * target ("one next step glows at a time"). A pure no-op
 * when [active] is false -- callers apply this unconditionally on every candidate target and let
 * this function decide whether anything actually renders, rather than each call site branching
 * on its own `if (active)`.
 *
 * Draws a soft breathing glow behind the target: [FireflyCreated] (the same amber every other
 * catch/embed moment in this app already uses, never a new "guidance" hue) at low opacity fading
 * to transparent, breathing on the same [fireflyAlpha] curve and the same shared
 * [rememberFireflyClock] every firefly dot in this app already animates on -- the same
 * radial-gradient-behind-a-jar shape `drawAmbientHalo` (`FireflyGlyphs.kt`) already renders for a
 * jar that holds fireflies, reused here for a guidance target instead of a fullness indicator.
 * This is the one visual mechanic every v6 guidance surface shares -- a shelf tile, an action
 * row, the wordmark's own long-press area, or anything a later trail task points this at.
 *
 * Static, not missing, under reduce-motion ([ValueAnimator.areAnimatorsEnabled] false): the
 * breathing collapses to [REDUCED_MOTION_ALPHA] -- [fireflyAlpha]'s own documented time-average
 * -- rather than disappearing.
 *
 * [description] is the target's own existing spoken label (e.g. `"look for fireflies"`, or a
 * shelf tile's `"the humming jar, 0 fireflies"`). While [active], this function appends one
 * clause naming the guidance context and reports the combined string as the *sole* accessibility
 * label for this element, via [Modifier.clearAndSetSemantics] -- which also stops any semantics a
 * wrapped/child composable would otherwise contribute from leaking through as a second, separate
 * announcement. Closes the "every highlight ... has TalkBack descriptions" rule without
 * inventing a second announcement alongside the target's normal one. Callers still own their own
 * `contentDescription` for the inactive case (this function changes nothing when [active] is
 * false); pass the exact text that side would have announced anyway.
 *
 * Stopping the glow the instant the target is tapped (the "stops on tap, not faded out") is
 * the caller's job: pass `active = <this is still the current trail step>`, which flips to false
 * the moment [TrailStateStore.advance] moves [TrailState.currentStep] past this target -- an
 * immediate state change, not a transition this modifier animates out.
 */
fun Modifier.trailHighlight(active: Boolean, description: String): Modifier {
    if (!active) return this
    return this.composed {
        val clock = rememberFireflyClock()
        val guidanceSuffix = stringResource(R.string.trail_highlight_next_step)
        Modifier
            .drawBehind {
                val reducedMotion = !ValueAnimator.areAnimatorsEnabled()
                val breath = if (reducedMotion) {
                    REDUCED_MOTION_ALPHA
                } else {
                    fireflyAlpha(TRAIL_HIGHLIGHT_BREATH_ID, clock.value)
                }
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = (maxOf(size.width, size.height) / 2f) * HALO_RADIUS_SCALE
                if (radius <= 0f) return@drawBehind
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to FireflyCreated.copy(alpha = (breath * HALO_PEAK_ALPHA).coerceIn(0f, 1f)),
                        0.7f to Color.Transparent,
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }
            .clearAndSetSemantics {
                contentDescription = "$description, $guidanceSuffix"
            }
    }
}
