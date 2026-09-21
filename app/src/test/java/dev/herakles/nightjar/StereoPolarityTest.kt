package dev.herakles.nightjar

import dev.herakles.nightjar.modules.audiostego.AudioSampleCover
import dev.herakles.nightjar.modules.audiostego.synthesizeSampleCover
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification for [stereoPolarity] (design-v5.md §4, V5-2). Plain JUnit4, no Robolectric —
 * [stereoPolarity] is pure `ShortArray`-in arithmetic, matching how `SpectrogramTest`/
 * `AudioStegoCarrierTest` in this same package keep DSP math verifiable with no device.
 *
 * The real-stego cases below build carriers through the actual [AudioStegoCarrier] +
 * `AudioSampleCover` bundled covers (the same construction `AudioStegoCarrierTest` and
 * `SpectrogramTest` use), rather than hand-rolled stereo arrays, so a drift between this file's
 * assumptions and the codec's real output would show up as a failing test here, not just in
 * `AudioStegDetector`'s own calibration suite later. Measured margins (design-v5.md §7 asks for
 * correlation <= -0.999 and sumToDifferenceDb <= -30 on real PI stego): worst observed
 * correlation -0.9994074 (SPOKEN_WORD, full 51B payload), worst observed sumToDifferenceDb
 * -34.795 dB (same case) -- both comfortably inside the required bound, recorded here so a future
 * regression has a number to compare against.
 */
class StereoPolarityTest {

    // --- Real PI stego on both bundled covers, payload {0, 20, full} ---

    @Test
    fun realPhaseInversionStegoMeasuresStrongNegativeCorrelationAndSumSuppression() {
        for (cover in AudioSampleCover.entries) {
            val pcm = synthesizeSampleCover(cover)
            val carrier = AudioStegoCarrier(pcm, AudioStegoTechnique.PHASE_INVERSION)
            for (payloadLength in intArrayOf(0, 20, carrier.maxPayloadBytes)) {
                val payload = ByteArray(payloadLength) { it.toByte() }
                val stego = carrier.encode(payload)

                val polarity = stereoPolarity(stego)

                assertTrue(
                    "$cover len=$payloadLength: expected correlation <= -0.999, was ${polarity.correlation}",
                    polarity.correlation <= -0.999,
                )
                assertTrue(
                    "$cover len=$payloadLength: expected sumToDifferenceDb <= -30, was ${polarity.sumToDifferenceDb}",
                    polarity.sumToDifferenceDb <= -30.0,
                )
            }
        }
    }

    @Test
    fun realPhaseInversionStegoHasExactlyOneFlaggedWindowPerEmbeddedBitAndZeroElsewhere() {
        // (len + 11) * 8 -- AudioStegoCarrier's own header(7) + trailer(4) framing overhead, one
        // MIX_AMPLITUDE-sized (64) offset embedded per 480-sample segment, recomputed inline the
        // same way AudioStegoCarrierTest.kt recomputes the carrier's private framing constants
        // rather than reaching into them.
        val headerTrailerOverheadBytes = 11
        val mixAmplitude = 64.0f

        for (cover in AudioSampleCover.entries) {
            val pcm = synthesizeSampleCover(cover)
            val carrier = AudioStegoCarrier(pcm, AudioStegoTechnique.PHASE_INVERSION)
            for (payloadLength in intArrayOf(0, 20, carrier.maxPayloadBytes)) {
                val payload = ByteArray(payloadLength) { it.toByte() }
                val stego = carrier.encode(payload)
                val expectedFlaggedWindows = (payloadLength + headerTrailerOverheadBytes) * 8

                val polarity = stereoPolarity(stego)

                assertEquals(500, polarity.residualWindowRms.size) // 240,000 samples / 480
                for (w in polarity.residualWindowMean.indices) {
                    if (w < expectedFlaggedWindows) {
                        assertEquals(
                            "$cover len=$payloadLength window $w should carry the embedded offset",
                            mixAmplitude,
                            kotlin.math.abs(polarity.residualWindowMean[w]),
                            0.01f,
                        )
                        assertEquals(mixAmplitude, polarity.residualWindowRms[w], 0.01f)
                    } else {
                        assertEquals(
                            "$cover len=$payloadLength window $w should be untouched",
                            0.0f,
                            polarity.residualWindowMean[w],
                            0.01f,
                        )
                        assertEquals(0.0f, polarity.residualWindowRms[w], 0.01f)
                    }
                }
            }
        }
    }

    @Test
    fun realPhaseInversionStegoZoomWindowShowsLeftMirroringRightWithinTheMixAmplitude() {
        val cover = synthesizeSampleCover(AudioSampleCover.SOFT_SYNTH)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_INVERSION)
        val payload = ByteArray(carrier.maxPayloadBytes) { it.toByte() }
        val stego = carrier.encode(payload)

        val polarity = stereoPolarity(stego)

        assertEquals(960, polarity.zoomLeft.size)
        assertEquals(960, polarity.zoomRight.size)
        // Normalized L and R were divided by the SAME peak, so if raw L[i] ~= -R[i] (mirror
        // images around the phase-cancelled dual-mono base), that relationship survives
        // normalization intact -- check it directly on the returned (already normalized) arrays.
        for (i in polarity.zoomLeft.indices) {
            assertEquals(
                "zoom sample $i should mirror: left=${polarity.zoomLeft[i]} right=${polarity.zoomRight[i]}",
                -polarity.zoomLeft[i],
                polarity.zoomRight[i],
                0.05f,
            )
        }

        // The raw (un-normalized) residual within the zoom window never exceeds MIX_AMPLITUDE
        // (64) in absolute value -- this is the "what survives cancellation" bound design-v5.md
        // §7 asks for, checked against the raw stego samples rather than the normalized view.
        var maxAbsResidual = 0
        for (i in 0 until 960) {
            val idx = polarity.zoomStartFrame + i
            val residual = stego[2 * idx] + stego[2 * idx + 1]
            if (kotlin.math.abs(residual) > maxAbsResidual) maxAbsResidual = kotlin.math.abs(residual)
        }
        assertTrue("expected max |L+R| <= 64 in the zoom window, was $maxAbsResidual", maxAbsResidual <= 64)
    }

    // --- Controls (design-v5.md §7) ---

    @Test
    fun dualMonoStereoMeasuresPositiveUnityCorrelation() {
        // "Mono duplicated to both channels" and "dual-mono" are the same construction for this
        // function's interleaved-stereo-only contract: L == R everywhere, a non-constant signal
        // so variance is nonzero (a real Pearson r is defined, not the zero-variance shortcut).
        val mono = noiseChannel(numSamples = 480 * 200, seed = 1)
        val interleaved = ShortArray(mono.size * 2)
        for (i in mono.indices) {
            interleaved[2 * i] = mono[i]
            interleaved[2 * i + 1] = mono[i]
        }

        val polarity = stereoPolarity(interleaved)

        assertEquals(1.0, polarity.correlation, 1e-9)
    }

    @Test
    fun pureInvertedStereoMeasuresNegativeUnityCorrelationWithAnAllZeroResidual() {
        // R = -L, nothing mixed in: this is anti-phase alone, one of design-v5.md's documented
        // false-positive classes for AudioStegDetector -- but for StereoPolarity itself it's
        // simply the noise-free case: perfect anti-correlation, and the L+R sum cancels to
        // exactly zero everywhere (no MIX_AMPLITUDE offset survives, because none was added).
        val mono = noiseChannel(numSamples = 480 * 200, seed = 2)
        val interleaved = ShortArray(mono.size * 2)
        for (i in mono.indices) {
            interleaved[2 * i] = mono[i]
            interleaved[2 * i + 1] = (-mono[i]).toShort()
        }

        val polarity = stereoPolarity(interleaved)

        assertEquals(-1.0, polarity.correlation, 1e-9)
        for (mean in polarity.residualWindowMean) assertEquals(0.0f, mean, 0.0f)
        for (rms in polarity.residualWindowRms) assertEquals(0.0f, rms, 0.0f)
    }

    @Test
    fun zeroVarianceChannelMeasuresZeroCorrelationWithoutNaN() {
        // Left is constant (a real but non-silent DC offset), right varies -- there is no linear
        // direction to correlate a constant against, so this must read as 0.0, not NaN.
        val right = noiseChannel(numSamples = 480 * 50, seed = 3)
        val interleaved = ShortArray(right.size * 2)
        for (i in right.indices) {
            interleaved[2 * i] = 5000
            interleaved[2 * i + 1] = right[i]
        }

        val polarity = stereoPolarity(interleaved)

        assertEquals(0.0, polarity.correlation, 0.0)
        assertFalse(polarity.correlation.isNaN())
    }

    @Test
    fun allZeroInputIsNanSafeEverywhere() {
        val interleaved = ShortArray(480 * 20 * 2) // digital silence, well past one window and one zoom window

        val polarity = stereoPolarity(interleaved)

        assertEquals(0.0, polarity.correlation, 0.0)
        assertFalse(polarity.correlation.isNaN())
        assertFalse(polarity.sumToDifferenceDb.isNaN())
        assertEquals(0.0, polarity.sumToDifferenceDb, 0.0) // 0/0 resolves to a defined "equal" ratio, not NaN
        for (mean in polarity.residualWindowMean) {
            assertFalse(mean.isNaN())
            assertEquals(0.0f, mean, 0.0f)
        }
        for (rms in polarity.residualWindowRms) {
            assertFalse(rms.isNaN())
            assertEquals(0.0f, rms, 0.0f)
        }
        for (v in polarity.zoomLeft) assertFalse(v.isNaN())
        for (v in polarity.zoomRight) assertFalse(v.isNaN())
        // Silent zoom window: normalization peak is 0, so both arrays stay zero-filled rather
        // than dividing by zero.
        assertEquals(960, polarity.zoomLeft.size)
        for (v in polarity.zoomLeft) assertEquals(0.0f, v, 0.0f)
        for (v in polarity.zoomRight) assertEquals(0.0f, v, 0.0f)
    }

    @Test
    fun oddLengthInterleavedArrayDropsTheTrailingUnpairedSampleInsteadOfThrowing() {
        val evenBase = noiseChannel(numSamples = 480 * 10, seed = 4) // used as a raw interleaved buffer
        val oddLength = ShortArray(evenBase.size + 1) { i -> if (i < evenBase.size) evenBase[i] else 999 }

        val fromOdd = stereoPolarity(oddLength)
        val fromEvenPrefix = stereoPolarity(evenBase)

        // oddLength is evenBase plus one trailing unpaired sample. If that trailing sample is
        // correctly dropped (rather than folded into a bogus final half-pair), both calls must
        // see identical L/R frame data and therefore produce identical results.
        assertEquals(fromEvenPrefix.correlation, fromOdd.correlation, 0.0)
        assertEquals(fromEvenPrefix.residualWindowMean.size, fromOdd.residualWindowMean.size)
    }

    @Test
    fun veryShortClipShorterThanOneWindowOrZoomWindowDoesNotThrow() {
        val interleaved = ShortArray(20) { i -> ((i - 10) * 50).toShort() } // 10 L/R frames, well under 480/960

        val polarity = stereoPolarity(interleaved)

        assertEquals(0, polarity.residualWindowMean.size)
        assertEquals(0, polarity.residualWindowRms.size)
        assertEquals(0, polarity.zoomStartFrame)
        assertEquals(10, polarity.zoomLeft.size) // whatever the clip has, never padded to 960
        assertEquals(10, polarity.zoomRight.size)
        assertFalse(polarity.correlation.isNaN())
        assertFalse(polarity.sumToDifferenceDb.isNaN())
    }

    @Test
    fun emptyInputDoesNotThrow() {
        val polarity = stereoPolarity(ShortArray(0))

        assertEquals(0.0, polarity.correlation, 0.0)
        // Unlike the "silence with samples" case (all-zero but nonzero length, where sum and
        // difference energy are equally -- zero -- present, a defined 0 dB "ratio"), a truly
        // empty clip has no data to measure a ratio from at all, so this falls to the floor
        // rather than claiming a specific (and untrue) 0 dB equality.
        assertEquals(-120.0, polarity.sumToDifferenceDb, 0.0)
        assertFalse(polarity.sumToDifferenceDb.isNaN())
        assertEquals(0, polarity.residualWindowMean.size)
        assertEquals(0, polarity.zoomLeft.size)
        assertEquals(0, polarity.zoomRight.size)
    }

    // --- Helpers ---

    /** Half-scale noise samples, matching AudioStegoCarrierTest's/SpectrogramTest's own
     *  synthetic-cover convention -- not important here since this file never runs anything
     *  through AudioStegoCarrier's saturating arithmetic, but kept for consistency. */
    private fun noiseChannel(numSamples: Int, seed: Long): ShortArray {
        val rng = Random(seed)
        return ShortArray(numSamples) { rng.nextInt(-16_000, 16_001).toShort() }
    }
}
