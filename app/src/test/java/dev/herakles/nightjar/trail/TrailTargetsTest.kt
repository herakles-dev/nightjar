package dev.herakles.nightjar.trail

import dev.herakles.nightjar.R
import dev.herakles.nightjar.picker.Module
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * W2-3 (design/riddle-trail.md § Step sequence, spec.md gate-36/gate-38): the step -> shelf-tile
 * / wordmark-hint / practice-gloss mappings are the one place a future trail reorder has to stay
 * consistent (`TrailStep.kt`'s own KDoc: "nothing persists an enum ordinal"), so they're pulled
 * out of every consumer composable into plain functions this test exercises directly.
 */
class TrailTargetsTest {

    // --- trailShelfTargetModule ---

    @Test
    fun `each creating jar's step points at its own module`() {
        assertEquals(Module.IMAGE_STEGANOGRAPHY, trailShelfTargetModule(TrailStep.ART))
        assertEquals(Module.AUDIO_STEGANOGRAPHY, trailShelfTargetModule(TrailStep.HUMMING))
        assertEquals(Module.ACOUSTIC_MODEM, trailShelfTargetModule(TrailStep.SINGING))
    }

    @Test
    fun `the meadow step points at the detector module`() {
        assertEquals(Module.DETECTOR, trailShelfTargetModule(TrailStep.MEADOW))
    }

    @Test
    fun `send, workshop and no active step glow no shelf tile`() {
        assertNull(trailShelfTargetModule(TrailStep.SEND))
        assertNull(trailShelfTargetModule(TrailStep.WORKSHOP))
        assertNull(trailShelfTargetModule(null))
    }

    // --- trailWordmarkHintRes ---

    @Test
    fun `every step resolves to its own distinct wordmark hint string`() {
        val resolved = TrailStep.entries.associateWith { trailWordmarkHintRes(it) }
        assertEquals(TrailStep.entries.size, resolved.values.toSet().size)
        assertEquals(R.string.trail_hint_art, resolved.getValue(TrailStep.ART))
        assertEquals(R.string.trail_hint_humming, resolved.getValue(TrailStep.HUMMING))
        assertEquals(R.string.trail_hint_singing, resolved.getValue(TrailStep.SINGING))
        assertEquals(R.string.trail_hint_meadow, resolved.getValue(TrailStep.MEADOW))
        assertEquals(R.string.trail_hint_send, resolved.getValue(TrailStep.SEND))
        assertEquals(R.string.trail_hint_workshop, resolved.getValue(TrailStep.WORKSHOP))
    }

    // --- trailPracticeGlossRes ---

    @Test
    fun `each creating jar has its own gloss`() {
        val artGloss = trailPracticeGlossRes(Module.IMAGE_STEGANOGRAPHY)
        val hummingGloss = trailPracticeGlossRes(Module.AUDIO_STEGANOGRAPHY)
        val singingGloss = trailPracticeGlossRes(Module.ACOUSTIC_MODEM)

        assertEquals(R.string.trail_gloss_art, artGloss)
        assertEquals(R.string.trail_gloss_humming, hummingGloss)
        assertEquals(R.string.trail_gloss_singing, singingGloss)
        assertEquals(3, setOfNotNull(artGloss, hummingGloss, singingGloss).size)
    }

    @Test
    fun `the meadow never holds a practice firefly, so it has no gloss`() {
        assertNull(trailPracticeGlossRes(Module.DETECTOR))
    }

    // --- trailQuestRes (W2-5) ---

    @Test
    fun `every step resolves to its own distinct quest line`() {
        val resolved = TrailStep.entries.associateWith { trailQuestRes(it) }
        assertEquals(TrailStep.entries.size, resolved.values.toSet().size)
        assertEquals(R.string.trail_quest_art, resolved.getValue(TrailStep.ART))
        assertEquals(R.string.trail_quest_humming, resolved.getValue(TrailStep.HUMMING))
        assertEquals(R.string.trail_quest_singing, resolved.getValue(TrailStep.SINGING))
        assertEquals(R.string.trail_quest_meadow, resolved.getValue(TrailStep.MEADOW))
        assertEquals(R.string.trail_quest_send, resolved.getValue(TrailStep.SEND))
        assertEquals(R.string.trail_quest_workshop, resolved.getValue(TrailStep.WORKSHOP))
    }

    // --- trailRewardRes (W2-5) ---

    @Test
    fun `every step resolves to its own distinct reward line`() {
        val resolved = TrailStep.entries.associateWith { trailRewardRes(it) }
        assertEquals(TrailStep.entries.size, resolved.values.toSet().size)
        assertEquals(R.string.trail_reward_art, resolved.getValue(TrailStep.ART))
        assertEquals(R.string.trail_reward_humming, resolved.getValue(TrailStep.HUMMING))
        assertEquals(R.string.trail_reward_singing, resolved.getValue(TrailStep.SINGING))
        assertEquals(R.string.trail_reward_meadow, resolved.getValue(TrailStep.MEADOW))
        assertEquals(R.string.trail_reward_send, resolved.getValue(TrailStep.SEND))
    }

    @Test
    fun `the workshop's reward is the finale line`() {
        assertEquals(R.string.trail_reward_finale, trailRewardRes(TrailStep.WORKSHOP))
    }
}
