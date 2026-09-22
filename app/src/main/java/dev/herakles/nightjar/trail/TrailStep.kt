package dev.herakles.nightjar.trail

/**
 * The six steps of the v6 first-use riddle trail: art -> humming -> singing -> meadow -> send ->
 * workshop. [id] is the stable, ordinal-independent identifier persisted by [TrailStateStore] and
 * reported by the `COVERT_DEBUG` probe -- the "nothing persists an enum ordinal" rule, already
 * applied to `Module`, applies here for the same reason: a future reorder of [ORDER] must never
 * silently remap anyone's in-progress persisted state.
 */
enum class TrailStep(val id: String) {
    ART("art"),
    HUMMING("humming"),
    SINGING("singing"),
    MEADOW("meadow"),

    /**
     * This step (owner-revised 2026-09-22, commit 6b9cedf) now teaches CREATING one of your own,
     * not re-sending the art jar's practice firefly. Wired:
     * (1) `Modifier.trailHighlight` (`trail/TrailHighlight.kt`) glows the art jar's "hide one in
     *     a photo" row (`ImageStegoScreen.kt`'s `JarActionRow`) while this step is active --
     *     never a firefly's own "send this firefly" row.
     * (2) `TrailStateStore.advance(SEND)` fires the moment a share sheet actually opens from
     *     "hide one in a photo" (`HideInPhotoFlow.kt`) -- and, as a safety net so nobody gets
     *     stuck, also from "send this firefly" on ANY firefly (`JarDetailScreen.kt`'s
     *     `FireflyDetailContent`), both guarded on this step actually being the active one.
     * Neither call is conditioned on what the user does with the share sheet afterward once it's
     * off-device (design doc: "the trail doesn't wait to find out").
     */
    SEND("send"),
    WORKSHOP("workshop"),
    ;

    companion object {
        /** Trail order (why this order, not the shelf order). */
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
