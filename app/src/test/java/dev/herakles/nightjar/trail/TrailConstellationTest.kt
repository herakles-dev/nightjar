package dev.herakles.nightjar.trail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the shelf's welcome-card/progress-constellation visibility rules and
 * the trail's finale
 * condition -- no Compose/Robolectric harness needed, same reasoning [TrailTargetsTest] is
 * plain JUnit against [TrailState] values built directly rather than through [TrailStateStore].
 */
class TrailConstellationTest {

    private val fresh = TrailState.INITIAL

    // --- trailWelcomeCardVisible ---

    @Test
    fun `a fresh install shows the welcome card`() {
        assertTrue(trailWelcomeCardVisible(fresh))
    }

    @Test
    fun `begin (welcomeSeen) hides the welcome card`() {
        assertFalse(trailWelcomeCardVisible(fresh.copy(welcomeSeen = true)))
    }

    @Test
    fun `skip hides the welcome card even if welcomeSeen never fired`() {
        assertFalse(trailWelcomeCardVisible(fresh.copy(skipped = true)))
    }

    // --- isTrailComplete ---

    @Test
    fun `not complete until every step is in completedSteps`() {
        assertFalse(isTrailComplete(fresh))
        assertFalse(isTrailComplete(fresh.copy(completedSteps = TrailStep.ORDER.toSet() - TrailStep.WORKSHOP)))
    }

    @Test
    fun `complete once every step in ORDER is completed`() {
        assertTrue(isTrailComplete(fresh.copy(completedSteps = TrailStep.ORDER.toSet())))
    }

    // --- trailConstellationVisible ---

    @Test
    fun `hidden before welcomeSeen even with a step nominally active`() {
        // TrailState.INITIAL already has currentStep == ART; the constellation still must not
        // show until the welcome card's own "begin" action has fired.
        assertFalse(trailConstellationVisible(fresh))
    }

    @Test
    fun `visible once welcomeSeen and a step is active`() {
        assertTrue(trailConstellationVisible(fresh.copy(welcomeSeen = true)))
    }

    @Test
    fun `hidden once skipped, even with welcomeSeen and partial progress`() {
        val state = fresh.copy(
            welcomeSeen = true,
            skipped = true,
            currentStep = null,
            completedSteps = setOf(TrailStep.ART),
        )
        assertFalse(state.let(::trailConstellationVisible))
    }

    @Test
    fun `visible at the finale until dismissed`() {
        val complete = fresh.copy(
            welcomeSeen = true,
            currentStep = null,
            completedSteps = TrailStep.ORDER.toSet(),
            finaleDismissed = false,
        )
        assertTrue(trailConstellationVisible(complete))
    }

    @Test
    fun `hidden once the finale is dismissed`() {
        val dismissed = fresh.copy(
            welcomeSeen = true,
            currentStep = null,
            completedSteps = TrailStep.ORDER.toSet(),
            finaleDismissed = true,
        )
        assertFalse(trailConstellationVisible(dismissed))
    }
}
