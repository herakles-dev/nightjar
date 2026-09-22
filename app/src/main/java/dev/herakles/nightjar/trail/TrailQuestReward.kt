package dev.herakles.nightjar.trail

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.herakles.nightjar.ui.theme.FireflyCreated
import dev.herakles.nightjar.ui.theme.JarTextTertiary
import dev.herakles.nightjar.ui.theme.JarType

/**
 * [step]'s one-line hint,
 * rendered "above its actions in the jar's footer/body style" -- [JarType.Footer], the same
 * dimmest/advisory tier the wordmark subtitle and every other static guidance line on this
 * surface already uses ([JarTextTertiary]), never a new text style. Callers gate this on their
 * own `trailActive` check (`trailState.currentStep == step`) -- this composable always renders
 * when called, matching [TrailRewardLine]'s own shape.
 */
@Composable
fun TrailQuestLine(step: TrailStep, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(trailQuestRes(step)),
        style = JarType.Footer,
        color = JarTextTertiary,
        modifier = modifier,
    )
}

/**
 * [step]'s one-line celebration, shown once
 * where it completed. [FireflyCreated] (the same amber every catch/embed success already reads
 * in) rather than [JarTextTertiary] -- a reward is a positive event, not neutral guidance, per
 * the "a defined success/positive-event color" allowance. Callers
 * own the "shown once" bookkeeping (a local `remember`ed flag flipped the moment their own
 * `TrailStateStore.advance(step)` call fires) -- this composable always renders when called.
 */
@Composable
fun TrailRewardLine(step: TrailStep, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(trailRewardRes(step)),
        style = JarType.Footer,
        color = FireflyCreated,
        modifier = modifier,
    )
}
