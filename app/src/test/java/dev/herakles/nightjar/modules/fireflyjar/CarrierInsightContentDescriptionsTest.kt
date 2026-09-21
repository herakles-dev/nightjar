package dev.herakles.nightjar.modules.fireflyjar

import dev.herakles.nightjar.AudioStegoCarrier
import dev.herakles.nightjar.AudioStegoTechnique
import dev.herakles.nightjar.DiffCell
import dev.herakles.nightjar.modules.audiostego.AudioSampleCover
import dev.herakles.nightjar.modules.audiostego.synthesizeSampleCover
import dev.herakles.nightjar.stegoDifference
import dev.herakles.nightjar.stereoPolarity
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * gate-38 (v6 addition, closes deferred follow-up #12). Plain JUnit4, no Robolectric — every
 * function under test here is a plain `String`-returning function, same shape
 * [CarrierInsightCaptionsTest] already verifies. Matches that file's own discipline: every branch
 * stating a measured fact is driven by real [AudioStegoCarrier] output, never a hand-built
 * [dev.herakles.nightjar.StegoDifferenceMap]/[dev.herakles.nightjar.StereoPolarity].
 */
class CarrierInsightContentDescriptionsTest {

    private fun fillerPayload(size: Int): ByteArray {
        val text = "the ravens have landed at dawn, bring the lantern and the map. "
        return ByteArray(size) { text[it % text.length].code.toByte() }
    }

    // ---------------------------------------------------------------------
    // stegoDifferenceContentDescription
    // ---------------------------------------------------------------------

    @Test
    fun `states the identical-cover case exactly, matching stegoDifferenceCaption's own honesty`() {
        val cover = synthesizeSampleCover(AudioSampleCover.SOFT_SYNTH)
        val identicalMap = stegoDifference(cover, cover.copyOf())
        assertEquals(-1, identicalMap.firstChangedFrame) // precondition this test depends on

        assertEquals(
            "difference view: cover and stego are identical, nothing changed.",
            stegoDifferenceContentDescription(identicalMap),
        )
    }

    @Test
    fun `counts exactly the frames with a lit cell, out of the clip's real total frame count`() {
        val cover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)
        val stego = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB).encode(fillerPayload(40))
        val map = stegoDifference(cover, stego)
        assertTrue(map.kinds.isNotEmpty()) // precondition: a real changed range exists

        val expectedChangedFrames = map.kinds.count { row -> row.any { it != DiffCell.UNCHANGED } }
        assertTrue(expectedChangedFrames > 0) // precondition: some frame actually shows a lit cell

        assertEquals(
            "difference view: $expectedChangedFrames of ${map.totalFrames} frames changed, all in the embedded region.",
            stegoDifferenceContentDescription(map),
        )
    }

    @Test
    fun `never names a specific bin index -- only a frame count, same restraint as the caption`() {
        val cover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)
        val stego = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB).encode(fillerPayload(200))
        val map = stegoDifference(cover, stego)

        assertFalse(stegoDifferenceContentDescription(map).contains("bin"))
    }

    // ---------------------------------------------------------------------
    // stereoPolarityOverlayContentDescription
    // ---------------------------------------------------------------------

    @Test
    fun `overlay description states the mirror-image shape and the exact 4-decimal correlation`() {
        val cover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)
        val stego = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_INVERSION).encode(ByteArray(20) { it.toByte() })
        val polarity = stereoPolarity(stego)

        val expectedCorrelation = String.format(Locale.US, "%.4f", polarity.correlation)
        val description = stereoPolarityOverlayContentDescription(polarity)

        assertTrue(description.contains("turned upside down"))
        assertTrue(
            "expected correlation $expectedCorrelation in: $description",
            description.contains("channel correlation $expectedCorrelation."),
        )
    }

    @Test
    fun `overlay description never states the anti-phase caveat -- that's the caption's own line`() {
        val cover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)
        val stego = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_INVERSION).encode(ByteArray(20) { it.toByte() })
        val polarity = stereoPolarity(stego)

        assertFalse(stereoPolarityOverlayContentDescription(polarity).contains("polarity-flipped"))
    }

    // ---------------------------------------------------------------------
    // stereoPolarityResidualContentDescription
    // ---------------------------------------------------------------------

    @Test
    fun `residual description states the hidden message when a real payload was mixed in`() {
        val cover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)
        val stego = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_INVERSION).encode(ByteArray(20) { it.toByte() })
        val polarity = stereoPolarity(stego)
        assertTrue(polarity.residualWindowMean.any { it != 0f }) // precondition: a real payload was mixed in

        assertEquals(
            "residual after adding left and right together: a step roughly every 10 milliseconds. " +
                "that's the hidden message.",
            stereoPolarityResidualContentDescription(polarity),
        )
    }

    @Test
    fun `residual description says nothing survives when the residual is exactly zero`() {
        // Pure anti-phase, nothing mixed in -- StereoPolarityTest's own
        // pureInvertedStereoMeasuresNegativeUnityCorrelationWithAnAllZeroResidual construction.
        val mono = ShortArray(480 * 50) { (it % 3000 - 1500).toShort() }
        val interleaved = ShortArray(mono.size * 2)
        for (i in mono.indices) {
            interleaved[2 * i] = mono[i]
            interleaved[2 * i + 1] = (-mono[i]).toShort()
        }
        val polarity = stereoPolarity(interleaved)
        assertTrue(polarity.residualWindowMean.all { it == 0f }) // precondition this test depends on

        assertEquals(
            "residual after adding left and right together: flat at zero. nothing survives the sum.",
            stereoPolarityResidualContentDescription(polarity),
        )
    }

    @Test
    fun `overlay and residual descriptions are never the identical string`() {
        val cover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)
        val stego = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_INVERSION).encode(ByteArray(20) { it.toByte() })
        val polarity = stereoPolarity(stego)

        assertFalse(
            stereoPolarityOverlayContentDescription(polarity) == stereoPolarityResidualContentDescription(polarity),
        )
    }

    // ---------------------------------------------------------------------
    // Determinism: same input, same output -- no hidden randomness (INV-8's spirit).
    // ---------------------------------------------------------------------

    @Test
    fun `content description builders are deterministic for the same inputs`() {
        val cover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)
        val stego = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB).encode(fillerPayload(40))
        val map = stegoDifference(cover, stego)
        assertEquals(stegoDifferenceContentDescription(map), stegoDifferenceContentDescription(map))

        val piStego = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_INVERSION).encode(ByteArray(5) { it.toByte() })
        val polarity = stereoPolarity(piStego)
        assertEquals(
            stereoPolarityOverlayContentDescription(polarity),
            stereoPolarityOverlayContentDescription(polarity),
        )
        assertEquals(
            stereoPolarityResidualContentDescription(polarity),
            stereoPolarityResidualContentDescription(polarity),
        )
    }
}
