package dev.herakles.nightjar.incoming

import dev.herakles.nightjar.R

/**
 * Which string resources render one [IncomingOutcome] in the jar's voice (design/screen-flow.md
 * v6 § "The five outcomes", design/firefly-jar-identity.md v6 addendum § Send/receive tone,
 * INV-12). A pure, total mapping from outcome to resource ids -- no [android.content.Context],
 * no `@Composable` -- so [IncomingScreen] (`IncomingScreen.kt`) and
 * `IncomingOutcomeCopyTest` (`app/src/test/.../incoming/IncomingOutcomeCopyTest.kt`) resolve the
 * exact same mapping rather than two independently-hand-written copies of it.
 *
 * Eight distinct cases, not five: INV-12's five outcomes, plus [IncomingOutcome.Squeezed]'s own
 * two-container split (screen-flow.md: "distinct copy for lossy image vs compressed audio"), plus
 * [IncomingOutcome.TooLarge] -- split out of [IncomingOutcome.Unsupported] (gate-41 safety
 * re-audit, finding F-3) so a file that's simply too big for the size caps isn't told it's an
 * unrecognized format -- plus [IncomingOutcome.OutOfSpace] (finding F-4), a verified-but-unstorable
 * firefly, distinct from both.
 *
 * [titleRes] is a short, static headline (no format args, every outcome). [bodyRes] is the
 * outcome's explanatory sentence -- for every outcome except [IncomingOutcome.Caught] it takes
 * no arguments; for `Caught` it carries two (`%1$d` the payload byte count, `%2$s` the carrier
 * channel, e.g. "a recording", [dev.herakles.nightjar.picker.Module.jarChannel]) -- resolve with
 * `stringResource(bodyRes, bytes, channel)` in a composable, or
 * `context.getString(bodyRes, bytes, channel)` outside one (as this file's own test does, via
 * Robolectric -- matching [dev.herakles.nightjar.trail.TrailVoiceTest]'s precedent for testing
 * resource copy without a Compose test harness).
 */
data class IncomingOutcomeCopy(val titleRes: Int, val bodyRes: Int)

/** [IncomingOutcomeCopy] never guesses either -- every [IncomingOutcome] maps to exactly one
 *  case below, mirroring INV-12's own "never guesses" contract for the outcome itself. */
fun incomingOutcomeCopyFor(outcome: IncomingOutcome): IncomingOutcomeCopy = when (outcome) {
    is IncomingOutcome.Caught -> IncomingOutcomeCopy(
        titleRes = R.string.receive_caught_title,
        bodyRes = R.string.receive_caught_body,
    )

    is IncomingOutcome.Squeezed -> when (outcome.container) {
        SqueezedContainer.LOSSY_IMAGE -> IncomingOutcomeCopy(
            titleRes = R.string.receive_squeezed_title,
            bodyRes = R.string.receive_squeezed_image_body,
        )
        SqueezedContainer.COMPRESSED_AUDIO -> IncomingOutcomeCopy(
            titleRes = R.string.receive_squeezed_title,
            bodyRes = R.string.receive_squeezed_audio_body,
        )
    }

    is IncomingOutcome.Damaged -> IncomingOutcomeCopy(
        titleRes = R.string.receive_damaged_title,
        bodyRes = R.string.receive_damaged_body,
    )

    is IncomingOutcome.NoFirefly -> IncomingOutcomeCopy(
        titleRes = R.string.receive_no_firefly_title,
        bodyRes = R.string.receive_no_firefly_body,
    )

    is IncomingOutcome.Unsupported -> IncomingOutcomeCopy(
        titleRes = R.string.receive_unsupported_title,
        bodyRes = R.string.receive_unsupported_body,
    )

    is IncomingOutcome.TooLarge -> IncomingOutcomeCopy(
        titleRes = R.string.receive_too_large_title,
        bodyRes = R.string.receive_too_large_body,
    )

    is IncomingOutcome.OutOfSpace -> IncomingOutcomeCopy(
        titleRes = R.string.receive_out_of_space_title,
        bodyRes = R.string.receive_out_of_space_body,
    )
}
