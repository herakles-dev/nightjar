package dev.herakles.nightjar.trail

import android.content.Context
import android.content.SharedPreferences
import dev.herakles.nightjar.modules.fireflyjar.FireflyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the riddle trail's persisted progress (design/riddle-trail.md, spec.md gate-36/INV-9).
 * SharedPreferences-backed -- no new dependency (no DataStore, no extra Room table) -- since the
 * trail's whole state is four small, flat fields (design/riddle-trail.md § Probe surface
 * reminder's own "current step, steps done, skipped" list, plus the practice-record-id set INV-9
 * requires living outside Room).
 *
 * Every mutation is persisted synchronously to [prefs] (`apply()`, async disk flush but
 * synchronous in-memory commit) and reflected in [state] before the call returns, so a caller
 * that reads [state] immediately after e.g. [advance] always sees the new value -- and a freshly
 * constructed [TrailStateStore] against the same [Context] (simulating process death: the
 * `SharedPreferences` file itself survives, only the in-memory [TrailState]/[StateFlow] object
 * does not) reloads exactly what the last instance wrote.
 */
class TrailStateStore(
    context: Context,
    private val fireflyRepository: FireflyRepository,
) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadState())

    /** Observable trail progress -- UI (W2-3) collects this to decide which step glows. */
    val state: StateFlow<TrailState> = _state.asStateFlow()

    /**
     * Marks [step] complete and moves the glow to [TrailStep.after] it (null once [step] is the
     * last step in [TrailStep.ORDER] -- the trail is then fully complete, same terminal shape as
     * [skip] but with every step in [TrailState.completedSteps] rather than none/some).
     */
    fun advance(step: TrailStep) {
        updateState { current ->
            current.copy(
                currentStep = TrailStep.after(step),
                completedSteps = current.completedSteps + step,
                // W2-5: tells the shelf's progress constellation which dot should play its
                // one-shot completion pulse next time it composes -- see acknowledgeCompletion().
                lastCompletedStep = step,
            )
        }
    }

    /**
     * Ends the trail immediately (design/riddle-trail.md § Skip: "the whole thing, not
     * step-by-step dismissal"). Not destructive: [TrailState.completedSteps] and
     * [TrailState.practiceRecordIds] are left exactly as they were -- "every practice firefly
     * already caught stays exactly where it is."
     */
    fun skip() {
        updateState { current -> current.copy(currentStep = null, skipped = true) }
    }

    /**
     * "Start the trail again" (design/riddle-trail.md § Start the trail again): regenerates all
     * three practice carrier files (fresh riddle text baked in, in case a translation/wording
     * update landed) via [PracticeFireflies.generate], then resets the glow to step 1 and clears
     * [TrailState.completedSteps]/[TrailState.skipped] -- and, per the W2-5 "Welcome + game
     * layer" addendum's explicit "resets everything, including welcomeSeen," also clears
     * [TrailState.welcomeSeen] (the welcome card shows again) and [TrailState.finaleDismissed]
     * (the finale panel is ready to show again once all six steps are re-completed).
     *
     * [TrailState.practiceRecordIds] is deliberately NOT cleared: per the design doc, a previous
     * run's practice fireflies that were never released stay in their jar's swarm, still
     * correctly labelled practice, after a restart -- "left as-is rather than deduplicated," a
     * documented rough edge rather than a bug. Regenerating the on-disk carrier files does not
     * touch any already-caught `FireflyRecord` (those are independent copies made at catch time).
     */
    suspend fun restart() {
        PracticeFireflies.generate(appContext)
        updateState { current ->
            TrailState(
                currentStep = TrailStep.ORDER.first(),
                completedSteps = emptySet(),
                skipped = false,
                practiceRecordIds = current.practiceRecordIds,
                welcomeSeen = false,
                finaleDismissed = false,
                lastCompletedStep = null,
            )
        }
    }

    /** Records that [recordId] (a [dev.herakles.nightjar.modules.fireflyjar.FireflyRecord.id])
     *  was caught from a practice carrier -- called once per jar, right after that jar's "look
     *  for fireflies" tap successfully inserts a row (W2-3). */
    fun markPractice(recordId: Long) {
        updateState { current -> current.copy(practiceRecordIds = current.practiceRecordIds + recordId) }
    }

    /** True if [id] was caught from a practice carrier -- drives the "a practice firefly from
     *  the trail" label (design/riddle-trail.md § Release practice fireflies). */
    fun isPractice(id: Long): Boolean = _state.value.practiceRecordIds.contains(id)

    /**
     * W2-5 (design/riddle-trail.md § Welcome card): the welcome card's `begin` action. Dismisses
     * the card -- it only shows while [TrailState.welcomeSeen] is false and the trail isn't
     * [TrailState.skipped] -- and, since every shelf-tile glow/wordmark hint this app already
     * has is gated on the same flag (`JarShelfScreen.kt`'s own `trailStarted` gate), this is also
     * the moment "the art jar starts glowing" per the design doc's own parenthetical.
     */
    fun markWelcomeSeen() {
        updateState { current -> current.copy(welcomeSeen = true) }
    }

    /**
     * W2-5 (design/riddle-trail.md § Progress constellation): the finale panel's `close` action,
     * once every step in [TrailStep.ORDER] is in [TrailState.completedSteps]. Hides the
     * constellation entirely until the next [restart].
     */
    fun dismissFinale() {
        updateState { current -> current.copy(finaleDismissed = true) }
    }

    /**
     * W2-5: called by the shelf's progress constellation once it has started (or, under
     * reduce-motion, skipped) [TrailState.lastCompletedStep]'s one-shot pulse, so the same
     * completion never pulses twice.
     */
    fun acknowledgeCompletion() {
        updateState { current -> current.copy(lastCompletedStep = null) }
    }

    /**
     * Releases the practice firefly at [recordId]: deletes its row (and attached media, if any)
     * via [FireflyRepository.deleteFirefly] -- the same delete path any other firefly's
     * long-press/"let this firefly go" gesture already uses (design/riddle-trail.md § Release
     * practice fireflies: "No new UI ... the existing per-firefly delete gesture") -- then stops
     * tracking [recordId] as practice.
     */
    suspend fun releasePractice(recordId: Long) {
        fireflyRepository.deleteFirefly(recordId)
        updateState { current -> current.copy(practiceRecordIds = current.practiceRecordIds - recordId) }
    }

    private fun updateState(transform: (TrailState) -> TrailState) {
        val next = transform(_state.value)
        persist(next)
        _state.value = next
    }

    private fun persist(trailState: TrailState) {
        val editor = prefs.edit()
        if (trailState.currentStep != null) {
            editor.putString(KEY_CURRENT_STEP, trailState.currentStep.id)
        } else {
            editor.remove(KEY_CURRENT_STEP)
        }
        editor.putStringSet(KEY_COMPLETED_STEPS, trailState.completedSteps.map { it.id }.toSet())
        editor.putBoolean(KEY_SKIPPED, trailState.skipped)
        editor.putStringSet(KEY_PRACTICE_IDS, trailState.practiceRecordIds.map { it.toString() }.toSet())
        editor.putBoolean(KEY_WELCOME_SEEN, trailState.welcomeSeen)
        editor.putBoolean(KEY_FINALE_DISMISSED, trailState.finaleDismissed)
        // lastCompletedStep is deliberately never persisted -- see TrailState's own KDoc.
        editor.apply()
    }

    private fun loadState(): TrailState {
        // No prior state at all (fresh install, nothing ever persisted) -- start at step 1,
        // exactly INITIAL, rather than an empty-but-inactive (currentStep = null) state.
        if (!prefs.contains(KEY_CURRENT_STEP) && !prefs.contains(KEY_SKIPPED)) {
            return TrailState.INITIAL
        }
        val currentStep = prefs.getString(KEY_CURRENT_STEP, null)?.let(TrailStep::fromId)
        val completedSteps = (prefs.getStringSet(KEY_COMPLETED_STEPS, emptySet()) ?: emptySet())
            .mapNotNull(TrailStep::fromId)
            .toSet()
        val skipped = prefs.getBoolean(KEY_SKIPPED, false)
        val practiceRecordIds = (prefs.getStringSet(KEY_PRACTICE_IDS, emptySet()) ?: emptySet())
            .mapNotNull { it.toLongOrNull() }
            .toSet()
        val welcomeSeen = prefs.getBoolean(KEY_WELCOME_SEEN, false)
        val finaleDismissed = prefs.getBoolean(KEY_FINALE_DISMISSED, false)
        return TrailState(
            currentStep = currentStep,
            completedSteps = completedSteps,
            skipped = skipped,
            practiceRecordIds = practiceRecordIds,
            welcomeSeen = welcomeSeen,
            finaleDismissed = finaleDismissed,
            // Never persisted -- always starts a fresh load with no pulse pending.
            lastCompletedStep = null,
        )
    }

    private companion object {
        const val PREFS_NAME = "nightjar_trail"
        const val KEY_CURRENT_STEP = "current_step"
        const val KEY_COMPLETED_STEPS = "completed_steps"
        const val KEY_SKIPPED = "skipped"
        const val KEY_PRACTICE_IDS = "practice_record_ids"
        const val KEY_WELCOME_SEEN = "welcome_seen"
        const val KEY_FINALE_DISMISSED = "finale_dismissed"
    }
}
