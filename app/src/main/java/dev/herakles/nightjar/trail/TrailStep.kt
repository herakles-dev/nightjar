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
