package dev.herakles.nightjar.trail

import dev.herakles.nightjar.R
import dev.herakles.nightjar.picker.Module

/**
 * Pure step -> UI-target mappings shared by every trail consumer (`JarShelfScreen.kt`,
 * `JarDetailScreen.kt`, the three jar catch flows, `DetectorScreen.kt`). Kept in one file and
 * `internal` rather than duplicated as a per-file `when` so a future trail reorder
 * ("nothing persists an enum ordinal ... a
 * future reorder of the trail shouldn't silently remap anyone's in-progress state") only ever
 * needs updating in one place, and so `TrailTargetsTest` can exercise the mapping directly
 * without a Compose test harness.
 */

/**
 * Which shelf tile glows for [step] -- null once the target isn't a shelf tile at all: [TrailStep.SEND] points at a
 * firefly detail popup, [TrailStep.WORKSHOP] points at the wordmark's own long-press area, and a
 * null [step] (trail inactive/complete/skipped) glows nothing.
 */
internal fun trailShelfTargetModule(step: TrailStep?): Module? = when (step) {
    TrailStep.ART -> Module.IMAGE_STEGANOGRAPHY
    TrailStep.HUMMING -> Module.AUDIO_STEGANOGRAPHY
    TrailStep.SINGING -> Module.ACOUSTIC_MODEM
    TrailStep.MEADOW -> Module.DETECTOR
    TrailStep.SEND, TrailStep.WORKSHOP, null -> null
}

/** The wordmark subtitle's step-specific hint string resource -- replaces, never appends to,
 *  `JarShelfScreen.kt`'s default "hold to open workshop" line while a step is active. */
internal fun trailWordmarkHintRes(step: TrailStep): Int = when (step) {
    TrailStep.ART -> R.string.trail_hint_art
    TrailStep.HUMMING -> R.string.trail_hint_humming
    TrailStep.SINGING -> R.string.trail_hint_singing
    TrailStep.MEADOW -> R.string.trail_hint_meadow
    TrailStep.SEND -> R.string.trail_hint_send
    TrailStep.WORKSHOP -> R.string.trail_hint_workshop
}

/**
 * Which plain-gloss string resource accompanies a practice firefly caught in [module] -- null
 * for a module that never holds one ([Module.DETECTOR], "the meadow": it holds no fireflies at
 * all, practice or otherwise). The gloss is "shown beside the decoded riddle and labelled as a
 * gloss" -- ordinary UI copy, distinct from the riddle payload text itself, which is the record's own decoded
 * [dev.herakles.nightjar.modules.fireflyjar.FireflyRecord.payloadPreview].
 */
internal fun trailPracticeGlossRes(module: Module): Int? = when (module) {
    Module.IMAGE_STEGANOGRAPHY -> R.string.trail_gloss_art
    Module.AUDIO_STEGANOGRAPHY -> R.string.trail_gloss_humming
    Module.ACOUSTIC_MODEM -> R.string.trail_gloss_singing
    Module.DETECTOR -> null
}

/**
 * The one-line hint shown on [step]'s own active
 * screen, above its actions -- distinct from [trailWordmarkHintRes], which only ever replaces
 * the shelf's short wordmark subtitle.
 */
internal fun trailQuestRes(step: TrailStep): Int = when (step) {
    TrailStep.ART -> R.string.trail_quest_art
    TrailStep.HUMMING -> R.string.trail_quest_humming
    TrailStep.SINGING -> R.string.trail_quest_singing
    TrailStep.MEADOW -> R.string.trail_quest_meadow
    TrailStep.SEND -> R.string.trail_quest_send
    TrailStep.WORKSHOP -> R.string.trail_quest_workshop
}

/**
 * The one-line celebration shown once [step]
 * completes, where it completed. [TrailStep.WORKSHOP]'s reward is the finale line -- shown on
 * the shelf alongside the constellation's own all-six-lit state, not on a jar screen.
 */
internal fun trailRewardRes(step: TrailStep): Int = when (step) {
    TrailStep.ART -> R.string.trail_reward_art
    TrailStep.HUMMING -> R.string.trail_reward_humming
    TrailStep.SINGING -> R.string.trail_reward_singing
    TrailStep.MEADOW -> R.string.trail_reward_meadow
    TrailStep.SEND -> R.string.trail_reward_send
    TrailStep.WORKSHOP -> R.string.trail_reward_finale
}
