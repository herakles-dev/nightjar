package dev.herakles.nightjar.incoming

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.herakles.nightjar.R
import dev.herakles.nightjar.modules.fireflyjar.JarNightSky
import dev.herakles.nightjar.picker.Module
import dev.herakles.nightjar.ui.theme.FireflyReceived
import dev.herakles.nightjar.ui.theme.JarActionLookBorder
import dev.herakles.nightjar.ui.theme.JarActionLookFill
import dev.herakles.nightjar.ui.theme.JarTextPrimary
import dev.herakles.nightjar.ui.theme.JarTextSecondary
import dev.herakles.nightjar.ui.theme.JarType

/**
 * The v6 receive-outcome screen: what `MainActivity.kt`'s
 * `Screen.Incoming` renders after routing a share-sheet/open-with/"catch from a photo or file"
 * `Intent` through [IncomingPipeline]. Replaces the MINIMAL plain-text placeholder left
 * behind (`IncomingPlaceholderScreen`, `MainActivity.kt`) with real jar-voice copy from
 * design/screen-flow.md's v6 "The five outcomes" table, rendered in this surface's own visual
 * language ([JarNightSky] backdrop, [JarType], the jar palette) -- the same shell every other jar
 * screen ([dev.herakles.nightjar.modules.fireflyjar.JarDetailScreen]) already uses, plus the same
 * top "← back to ..." row convention that screen's own `BackRow` establishes.
 *
 * One layout per outcome kind, per this task's brief:
 *  - [IncomingOutcome.Caught]: which jar it landed in ([Module.jarName]), the decoded message,
 *    the byte count, an "open the &lt;jar&gt;" action ([onOpenJar]), and the back row.
 *  - [IncomingOutcome.Squeezed] (either [SqueezedContainer]), [IncomingOutcome.Damaged],
 *    [IncomingOutcome.NoFirefly], [IncomingOutcome.Unsupported]: a title + explanatory sentence
 *    -- never asserting a firefly was actually sent -- and just the back row; none of these
 *    landed in a specific jar.
 *
 * All copy resolves through [incomingOutcomeCopyFor] (`IncomingOutcomeCopy.kt`) -- this
 * composable never inlines a `when`-on-outcome string literal of its own, so the mapping
 * rendered here and the one `IncomingOutcomeCopyTest` checks for distinctness/voice-rule
 * compliance are provably the same code path.
 */
@Composable
fun IncomingScreen(outcome: IncomingOutcome, onBack: () -> Unit, onOpenJar: (Module) -> Unit) {
    JarNightSky(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            IncomingBackRow(onClick = onBack)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                when (outcome) {
                    is IncomingOutcome.Caught -> CaughtContent(outcome = outcome, onOpenJar = onOpenJar)
                    else -> OutcomeContent(outcome = outcome)
                }
            }
        }
    }
}

/** [IncomingOutcome.Caught]'s own layout -- the one outcome that lands in a specific jar and
 *  carries a real decoded message, so it earns a richer layout than the other four/five's shared
 *  [OutcomeContent]. */
@Composable
private fun CaughtContent(outcome: IncomingOutcome.Caught, onOpenJar: (Module) -> Unit) {
    val copy = incomingOutcomeCopyFor(outcome)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = outcome.module.jarName, style = JarType.ScreenTitle, color = JarTextPrimary)
        // design/screen-flow.md v6 "the five outcomes" table's own copy pattern, split across two
        // lines rather than one hand-concatenated string: "you caught one" (static headline) /
        // "N bytes, hidden in {channel}." (the one format-arg'd sentence in this table).
        Text(text = stringResource(copy.titleRes), style = JarType.SectionLabel, color = FireflyReceived)
        Text(
            text = stringResource(copy.bodyRes, outcome.payload.size, outcome.module.jarChannel),
            style = JarType.SectionLabel,
            color = FireflyReceived,
        )
        Text(
            text = outcome.payload.decodeToString(),
            style = JarType.Body,
            color = JarTextPrimary,
        )
    }

    IncomingActionRow(
        label = stringResource(R.string.receive_caught_open_action, outcome.module.jarName),
        onClick = { onOpenJar(outcome.module) },
    )
}

/** The shared layout for every non-[IncomingOutcome.Caught] outcome: a title, then the
 *  explanatory sentence, no action row of its own -- just the [IncomingBackRow] the caller
 *  already renders above this. */
@Composable
private fun OutcomeContent(outcome: IncomingOutcome) {
    val copy = incomingOutcomeCopyFor(outcome)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(copy.titleRes), style = JarType.ScreenTitle, color = JarTextPrimary)
        Text(text = stringResource(copy.bodyRes), style = JarType.Body, color = JarTextSecondary)
    }
}

/** One tinted, rounded action row in this screen's own cyan "receiving" tint
 *  ([JarActionLookFill]/[JarActionLookBorder] -- the same tokens "look for fireflies" already
 *  uses everywhere else in the app; "cyan = received"), matching
 *  every jar-flow action row's existing shape (8dp radius, 16dp/14dp padding) rather than
 *  inventing a new one for this screen. */
@Composable
private fun IncomingActionRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(JarActionLookFill)
            .border(width = 1.dp, color = JarActionLookBorder, shape = RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = label, style = JarType.TileTitle, color = JarTextPrimary)
    }
}

/** Same shape as [dev.herakles.nightjar.modules.fireflyjar.JarDetailScreen]'s own private
 *  `BackRow` (44dp, 16dp horizontal padding, "← " prefix, [JarType.BackLink]/[JarTextSecondary])
 *  -- not reused directly since that one is `private` to its own file, but deliberately identical
 *  so this screen's back affordance reads as the same control everywhere else in the app already
 *  renders one. */
@Composable
private fun IncomingBackRow(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = "← ${stringResource(R.string.receive_back_to_jar)}",
            style = JarType.BackLink,
            color = JarTextSecondary,
        )
    }
}
