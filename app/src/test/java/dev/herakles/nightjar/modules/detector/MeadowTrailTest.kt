package dev.herakles.nightjar.modules.detector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W2-3 (design/riddle-trail.md § Step 4 Branch B, spec.md gate-36): the meadow's honest-fallback
 * decision is a plain threshold check, pulled out of [jarWatchFlow]'s own coroutine loop
 * ([meadowHonestFallbackDue]'s KDoc) purely so it's testable without a Compose harness.
 */
class MeadowTrailTest {

    @Test
    fun `well under the timeout is not due`() {
        assertFalse(meadowHonestFallbackDue(elapsedMillisSinceWatchStarted = 0L))
        assertFalse(meadowHonestFallbackDue(elapsedMillisSinceWatchStarted = 5_000L))
        assertFalse(meadowHonestFallbackDue(elapsedMillisSinceWatchStarted = MEADOW_HONEST_FALLBACK_TIMEOUT_MS - 1L))
    }

    @Test
    fun `exactly at the timeout is due`() {
        assertTrue(meadowHonestFallbackDue(elapsedMillisSinceWatchStarted = MEADOW_HONEST_FALLBACK_TIMEOUT_MS))
    }

    @Test
    fun `past the timeout stays due`() {
        assertTrue(meadowHonestFallbackDue(elapsedMillisSinceWatchStarted = MEADOW_HONEST_FALLBACK_TIMEOUT_MS + 10_000L))
    }

    @Test
    fun `the configured timeout matches the design doc's 20s candidate`() {
        // design/riddle-trail.md § Step 4 Branch B: "candidate: 20s, matching the acoustic
        // modem's own MAX_LISTEN_SECONDS precedent" -- AcousticModemScreen.kt's own
        // MAX_LISTEN_SECONDS = 20.0.
        assertTrue(MEADOW_HONEST_FALLBACK_TIMEOUT_MS == 20_000L)
    }

    @Test
    fun `the practice playback delay is shorter than the fallback timeout`() {
        // The auto-play has to actually happen before the honest fallback could ever show, or
        // the "the meadow can't always hear its own song" copy would be showing before the
        // meadow even tried to play anything.
        assertTrue(MEADOW_PRACTICE_PLAYBACK_DELAY_MS < MEADOW_HONEST_FALLBACK_TIMEOUT_MS)
    }
}
