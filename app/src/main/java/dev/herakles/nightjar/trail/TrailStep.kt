package dev.herakles.nightjar.trail

/**
 * The six steps of the v6 first-use riddle trail (design/riddle-trail.md § Step sequence,
 * spec.md gate-36): art -> humming -> singing -> meadow -> send -> workshop. [id] is the stable,
 * ordinal-independent identifier persisted by [TrailStateStore] and reported by the `COVERT_DEBUG`
 * probe (design/riddle-trail.md § Probe surface reminder) -- gate-39's "nothing persists an enum
 * ordinal" rule, already applied to `Module`, applies here for the same reason: a future reorder
 * of [ORDER] must never silently remap anyone's in-progress persisted state.
 */
enum class TrailStep(val id: String) {
    ART("art"),
    HUMMING("humming"),
    SINGING("singing"),
    MEADOW("meadow"),

    /**
     * design/riddle-trail.md § Step 5: glows the "send this firefly" row on the art jar's
     * practice firefly's detail popup. That row does not exist yet as of W2-3 (task W2-2 adds
     * it) -- **the integration hook for W2-2**: while `TrailStateStore.state.value.currentStep
     * == SEND`, the send row should
     * (1) apply `Modifier.trailHighlight(active = true, description = "send this firefly")`
     *     (`trail/TrailHighlight.kt`) so it glows, matching every other action-row target, and
     * (2) call `TrailStateStore.advance(SEND)` the moment the share sheet actually opens
     *     (`ACTION_SEND` fires) -- not before, and not conditioned on what the user does with it
     *     afterward (design doc: "the trail doesn't wait to find out what the user actually does
     *     with it once it's off-device").
     * Until W2-2 lands, this step still can't strand the user: [trailWordmarkHintRes]
     * resolves to `trail_hint_send` ("send what you caught") on the shelf, and "skip the trail"
     * is available the same as every other active step -- W2-3 wires both of those generically,
     * with no dependency on the send row existing yet.
     */
    SEND("send"),
    WORKSHOP("workshop"),
    ;

    companion object {
        /** Trail order (design/riddle-trail.md § Why this order, not the shelf order). */
        val ORDER: List<TrailStep> = listOf(ART, HUMMING, SINGING, MEADOW, SEND, WORKSHOP)

        /** Resolves a persisted [id] back to its [TrailStep], or null for an unrecognized id
         *  (a future format change fails safe to "no step" rather than crashing restore). */
        fun fromId(id: String): TrailStep? = entries.firstOrNull { it.id == id }

        /** The step after [step] in [ORDER], or null if [step] is the last one (trail complete). */
        fun after(step: TrailStep): TrailStep? {
            val index = ORDER.indexOf(step)
            return ORDER.getOrNull(index + 1)
        }
    }
}
