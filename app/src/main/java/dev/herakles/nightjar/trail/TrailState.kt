package dev.herakles.nightjar.trail

/**
 * Everything [TrailStateStore] persists (design/riddle-trail.md, spec.md gate-36 "progress
 * survives process death").
 *
 * @param currentStep The step currently glowing ("one next step glows at a time," gate-36). Null
 *   means the trail is not currently advancing -- either every step completed ([completedSteps]
 *   holds all of [TrailStep.ORDER]), or the user chose [TrailStateStore.skip] ([skipped] true).
 * @param completedSteps Steps the user has finished, in no particular order. Not cleared by
 *   [TrailStateStore.skip] (design/riddle-trail.md § Skip: "the trail state simply stops
 *   advancing" -- steps already done stay done).
 * @param skipped True once the user has tapped "skip the trail" for the current run. Reset to
 *   false by [TrailStateStore.restart].
 * @param practiceRecordIds [dev.herakles.nightjar.modules.fireflyjar.FireflyRecord.id] values
 *   caught from a practice carrier this trail run (or a prior, un-released one -- see
 *   [TrailStateStore.restart]'s KDoc). INV-9: this is the only place practice status lives: never
 *   a Room column, so `FireflyRecord`/the v2 schema need no change to carry it.
 * @param welcomeSeen W2-5 (design/riddle-trail.md § "Welcome + game layer"): true once the
 *   shelf's welcome card has been dismissed via `begin` -- the card shows while this is false
 *   (and the trail isn't [skipped]); `begin` also unlocks the tile-glow/wordmark-hint chrome that
 *   was already gated on [currentStep], per [TrailStateStore.markWelcomeSeen]'s KDoc. Persisted;
 *   [TrailStateStore.restart] clears it back to false, same as everything else.
 * @param finaleDismissed W2-5: true once the shelf's finale panel (all six dots lit, the
 *   finale line, `close`) has been dismissed. Meaningless before every step is done -- the
 *   finale only ever shows once [completedSteps] holds all of [TrailStep.ORDER]. Persisted;
 *   cleared by [TrailStateStore.restart].
 * @param lastCompletedStep W2-5: the step [TrailStateStore.advance] most recently completed,
 *   used purely to tell the shelf's progress constellation which dot should play its one-shot
 *   completion pulse -- cleared by [TrailStateStore.acknowledgeCompletion] once that pulse has
 *   run. Deliberately **not** persisted (see [TrailStateStore]'s persist/load pair): it is a
 *   transient "has this moment's pulse been shown yet" signal, not real trail progress, and
 *   losing it across a process death (no pulse plays for a completion that happened to straddle
 *   one) is an acceptable rough edge for a purely decorative animation cue.
 */
data class TrailState(
    val currentStep: TrailStep?,
    val completedSteps: Set<TrailStep>,
    val skipped: Boolean,
    val practiceRecordIds: Set<Long>,
    val welcomeSeen: Boolean = false,
    val finaleDismissed: Boolean = false,
    val lastCompletedStep: TrailStep? = null,
) {
    companion object {
        /** Fresh-install / newly-restarted state: step 1 (art) glowing, nothing else done yet,
         *  welcome card showing. */
        val INITIAL: TrailState = TrailState(
            currentStep = TrailStep.ORDER.first(),
            completedSteps = emptySet(),
            skipped = false,
            practiceRecordIds = emptySet(),
            welcomeSeen = false,
            finaleDismissed = false,
            lastCompletedStep = null,
        )
    }
}
