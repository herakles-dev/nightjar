package dev.herakles.nightjar.trail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.herakles.nightjar.R
import dev.herakles.nightjar.ui.theme.FireflyCreated
import dev.herakles.nightjar.ui.theme.JarTextPrimary
import dev.herakles.nightjar.ui.theme.JarTextTertiary
import dev.herakles.nightjar.ui.theme.JarTileBorderCreated
import dev.herakles.nightjar.ui.theme.JarTileFill
import dev.herakles.nightjar.ui.theme.JarType

/**
 * W2-5 (design/riddle-trail.md § "Welcome card"): whether the shelf's first-launch welcome card
 * should render. Shown while [TrailState.welcomeSeen] is false and the trail hasn't been
 * [TrailState.skipped] -- the design doc's own condition, verbatim. Not gated on trail
 * completion: the card and the constellation ([trailConstellationVisible]) are mutually
 * exclusive in practice (the card only ever shows before `begin`, which is the same tap that
 * sets [TrailState.welcomeSeen] and therefore unlocks the constellation -- see
 * [TrailStateStore.markWelcomeSeen]'s KDoc), so there's no state where both would render.
 */
internal fun trailWelcomeCardVisible(state: TrailState): Boolean =
    !state.welcomeSeen && !state.skipped

/**
 * The shelf's first-launch welcome card (design/riddle-trail.md § "Welcome card"): in place
 * above the jar tiles, never a modal/dialog/scrim/carousel — a plain card in the jar's own dusk
 * palette ([JarTileFill]/[JarTileBorderCreated], the exact fill/border pair every creating jar's
 * shelf tile already uses) with a soft amber glow border, reusing existing primitives rather
 * than inventing a new "welcome" treatment. `begin` sets [TrailState.welcomeSeen] (the art jar's
 * tile glow and wordmark hint start the instant this card disappears — both already gated on
 * the same flag at the call site); `skip` calls [TrailStateStore.skip].
 *
 * A pure no-op (renders nothing) when [trailWelcomeCardVisible] is false for [state] — same
 * "callers apply unconditionally, this function decides" idiom [trailHighlight] already
 * establishes, so the shelf can place this call wherever "above the jars" sits in its layout
 * without its own `if` guard.
 */
@Composable
fun TrailWelcomeCard(state: TrailState, onBegin: () -> Unit, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    if (!trailWelcomeCardVisible(state)) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(JarTileFill)
            .border(width = 1.5.dp, color = JarTileBorderCreated, shape = RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = stringResource(R.string.trail_welcome_title), style = JarType.ActionTitle, color = JarTextPrimary)
        Text(text = stringResource(R.string.trail_welcome_body), style = JarType.Body, color = JarTextTertiary)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                text = stringResource(R.string.trail_welcome_begin),
                style = JarType.ButtonLabel,
                color = FireflyCreated,
                modifier = Modifier.clickable(onClick = onBegin),
            )
            Text(
                text = stringResource(R.string.trail_welcome_skip),
                style = JarType.ButtonLabel,
                color = JarTextTertiary,
                modifier = Modifier.clickable(onClick = onSkip),
            )
        }
    }
}
