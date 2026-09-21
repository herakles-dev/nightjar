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
 */
data class TrailState(
    val currentStep: TrailStep?,
    val completedSteps: Set<TrailStep>,
    val skipped: Boolean,
    val practiceRecordIds: Set<Long>,
) {
    companion object {
        /** Fresh-install / newly-restarted state: step 1 (art) glowing, nothing else done yet. */
        val INITIAL: TrailState = TrailState(
            currentStep = TrailStep.ORDER.first(),
            completedSteps = emptySet(),
            skipped = false,
            practiceRecordIds = emptySet(),
        )
    }
}
