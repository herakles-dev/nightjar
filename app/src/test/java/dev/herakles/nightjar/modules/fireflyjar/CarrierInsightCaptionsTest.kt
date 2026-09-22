package dev.herakles.nightjar.modules.fireflyjar

import dev.herakles.nightjar.AudioStegoCarrier
import dev.herakles.nightjar.AudioStegoTechnique
import dev.herakles.nightjar.modules.audiostego.AudioSampleCover
import dev.herakles.nightjar.modules.audiostego.synthesizeSampleCover
import dev.herakles.nightjar.stegoDifference
import dev.herakles.nightjar.stereoPolarity
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v5 addition (design-v5.md §3.4, §4.2, §7, gate-24/25/26). Plain JUnit4, no Robolectric — every
 * function under test here is a plain `String`-returning function, matching how
 * `StegoDifferenceTest`/`StereoPolarityTest` verify their own pure-math counterparts.
 *
 * The honest-labels doctrine (design-v5.md C1, gate-26: "every v5 caption is backed by a test")
 * means every branch these caption/readout builders can take is exercised here, and every branch
 * that states a fact about the real codec (a dB figure, a gain factor, a correlation value,
 * whether "created" cells exist) is driven by real [AudioStegoCarrier] output — the same
 * discipline `StegoDifferenceTest`/`StereoPolarityTest` already use — rather than a hand-built
 * [dev.herakles.nightjar.StegoDifferenceMap]/[dev.herakles.nightjar.StereoPolarity].
 */
class CarrierInsightCaptionsTest {

    // ---------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------

    private fun fillerPayload(size: Int): ByteArray {
        val text = "the ravens have landed at dawn, bring the lantern and the map. "
        return ByteArray(size) { text[it % text.length].code.toByte() }
    }

    /** Independent re-derivation of [CarrierInsightViews.kt]'s private `NATS_TO_DB` -- this test
     *  measures the same conversion from first principles rather than importing the production
     *  constant, so a broken conversion in the production file can't also break this check. */
    private fun natsToDb(nats: Double): Double = nats * (20.0 / ln(10.0))

    // ---------------------------------------------------------------------
    // stegoDifferenceCaption: branches on createdCells, driven by real SPECTROGRAM_LSB output
    // ---------------------------------------------------------------------

    @Test
    fun `stegoDifferenceCaption mentions the pale created cells only when created cells exist`() {
        val spokenWord = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)
        val softSynth = synthesizeSampleCover(AudioSampleCover.SOFT_SYNTH)

        val spokenWord5 = stegoDifference(
            spokenWord,
            AudioStegoCarrier(spokenWord, AudioStegoTechnique.SPECTROGRAM_LSB).encode(fillerPayload(5)),
        )
        // 200 B, not 40 B: AudioStegoCarrier's v6 near-silent-frame-skip (follow-up A) means a
        // 40 B payload no longer reaches any created cell on SPOKEN_WORD (its gap frames are now
        // genuinely skipped, not embedded into -- StegoDifferenceTest's own
        // "SPOKEN_WORD has no created cells at 5 or 40 bytes post-v2" measurement). 200 B still
        // produces created cells from ordinary spectral variation in loud content, matching
        // StegoDifferenceTest's "classifies cells into all three DiffCell kinds" test.
        val spokenWord200 = stegoDifference(
            spokenWord,
            AudioStegoCarrier(spokenWord, AudioStegoTechnique.SPECTROGRAM_LSB).encode(fillerPayload(200)),
        )
        val softSynth5 = stegoDifference(
            softSynth,
            AudioStegoCarrier(softSynth, AudioStegoTechnique.SPECTROGRAM_LSB).encode(fillerPayload(5)),
        )

        // Preconditions this test actually depends on -- mirrors StegoDifferenceTest's own
        // measurements.
        assertEquals(0, spokenWord5.createdCells)
        assertTrue(spokenWord200.createdCells > 0)
        assertTrue(softSynth5.createdCells > 0)

        assertFalse(
            "no created cells: caption must not mention adding faint sound",
            stegoDifferenceCaption(spokenWord5).contains("add faint"),
        )
        assertTrue(
            "created cells present: caption must mention the pale cells",
            stegoDifferenceCaption(spokenWord200).contains("pale cells"),
        )
        assertTrue(
            "SOFT_SYNTH's fade-in also creates cells: caption must mention them",
            stegoDifferenceCaption(softSynth5).contains("pale cells"),
        )

        // gate-8: the owner heard the added sound on headphones as a faint crackle on the
        // SOFT_SYNTH cover's near-silent fade-in. The caption states that fact, gated on the
        // same createdCells > 0 condition as the rest of the created-cells sentence.
        assertFalse(
            "no created cells: caption must not claim an audible crackle",
            stegoDifferenceCaption(spokenWord5).contains("crackle"),
        )
        assertTrue(
            "created cells present: caption must mention the audible headphone crackle",
            stegoDifferenceCaption(spokenWord200).contains("faint crackle"),
        )
        assertTrue(
            "SOFT_SYNTH's fade-in also creates cells: caption must mention the audible headphone crackle",
            stegoDifferenceCaption(softSynth5).contains("faint crackle"),
        )
    }

    @Test
    fun `stegoDifferenceCaption never claims an exact changed-bin span`() {
        val cover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)
        val stego = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB).encode(fillerPayload(200))
        val map = stegoDifference(cover, stego)

        val caption = stegoDifferenceCaption(map)

        // The honesty constraint this test exists to enforce: the caption may say a lit cell is
        // "a frequency bin" (a description of what's drawn), but must never name a specific bin
        // index or numeric range -- design-v5.md §3.2's exact span is only calibrated for
        // specific strength/payload combinations (StegoDifferenceTest's own KDoc), so a caption
        // stating one as fact would overclaim.
        assertFalse(
            "must not name a specific bin index or numeric range",
            caption.contains(Regex("bin\\s*\\d|\\d+\\s*[-–]\\s*\\d+|\\d+\\.\\.\\d+")),
        )
        assertTrue(caption.contains("a frequency bin the codec nudged"))
        assertTrue(caption.contains("never more than"))
        assertTrue(caption.contains("bit-for-bit the cover"))
        assertTrue("last sentence must state the re-derivation caveat", caption.contains("someone holding just this clip"))
    }

    @Test
    fun `stegoDifferenceCaption states the cover-matches-exactly case honestly`() {
        val cover = synthesizeSampleCover(AudioSampleCover.SOFT_SYNTH)
        val identicalMap = stegoDifference(cover, cover.copyOf())

        assertEquals(-1, identicalMap.firstChangedFrame) // precondition this test depends on

        val caption = stegoDifferenceCaption(identicalMap)

        assertEquals("this firefly's cover matches it exactly, so there's nothing to subtract.", caption)
    }

    @Test
    fun `stegoDifferenceCaption reports the real measured max nudge in decibels`() {
        val cover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)
        val stego = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB).encode(fillerPayload(40))
        val map = stegoDifference(cover, stego)

        val expectedDb = natsToDb(map.maxNudgeNats)
        val caption = stegoDifferenceCaption(map)

        assertTrue(
            "expected the caption to state $expectedDb db (formatted), was: $caption",
            caption.contains(String.format(java.util.Locale.US, "%.2f db", expectedDb)),
        )
        // design-v5.md §3.2's own QIM ceiling -- 1.5 * QUANTIZATION_STEP (0.12) = 0.18 nats =
        // 1.56 dB. A real measured max should sit at or under that bound.
        assertTrue("measured max nudge exceeded the QIM ceiling", expectedDb <= 1.6)
    }

    // ---------------------------------------------------------------------
    // stegoDifferenceReadout: cover label, timing, and the fixed 0..binCount khz span
    // ---------------------------------------------------------------------

    @Test
    fun `stegoDifferenceReadout names the matched cover and the full analyzed band, not the changed span`() {
        val cover = synthesizeSampleCover(AudioSampleCover.SOFT_SYNTH)
        val stego = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB).encode(fillerPayload(40))
        val map = stegoDifference(cover, stego)

        val readout = stegoDifferenceReadout(map, AudioSampleCover.SOFT_SYNTH.label)

        assertTrue(readout.contains("the original ${AudioSampleCover.SOFT_SYNTH.label} cover, subtracted"))
        // binCount defaults to 128 bins at FRAME_SIZE=1024/48kHz -> 128 * 48000 / 1024 = 6000 Hz.
        assertTrue("expected the full 0-6 khz analyzed band, was: $readout", readout.contains("0–6 khz"))
        // 240,000 samples / 48,000 Hz = 5.0 s total, regardless of how much of it changed.
        assertTrue(readout.contains("of 5.0 s"))
    }

    // ---------------------------------------------------------------------
    // STEGO_DIFFERENCE_WITHHELD_CAPTION: the constant matchCover-withheld line
    // ---------------------------------------------------------------------

    @Test
    fun `the withheld caption states there is nothing honest to subtract`() {
        assertEquals(
            "can't rebuild this firefly's original cover anymore, so there's nothing honest to subtract.",
            STEGO_DIFFERENCE_WITHHELD_CAPTION,
        )
    }

    // ---------------------------------------------------------------------
    // stereoPolarityReadout: 4-decimal correlation, real PI stego, sum/difference direction
    // ---------------------------------------------------------------------

    @Test
    fun `stereoPolarityReadout prints correlation at 4 decimals for real phase-inversion stego`() {
        for (cover in AudioSampleCover.entries) {
            val pcm = synthesizeSampleCover(cover)
            val carrier = AudioStegoCarrier(pcm, AudioStegoTechnique.PHASE_INVERSION)
            val stego = carrier.encode(ByteArray(20) { it.toByte() })
            val polarity = stereoPolarity(stego)

            val readout = stereoPolarityReadout(polarity)

            val expectedCorrelation = String.format(java.util.Locale.US, "%.4f", polarity.correlation)
            assertTrue(
                "$cover: expected readout to contain 4-decimal correlation $expectedCorrelation, was: $readout",
                readout.contains("channel correlation $expectedCorrelation"),
            )
            // Real PI stego measures strongly negative sumToDifferenceDb (design-v5.md §4.2,
            // §7: <= -30 db) -- the readout must phrase this as "below", and must NOT quote the
            // design doc's illustrative "42 db": it states the real measured magnitude.
            assertTrue(polarity.sumToDifferenceDb <= -30.0) // precondition this test depends on
            val expectedMagnitude = String.format(java.util.Locale.US, "%.1f", abs(polarity.sumToDifferenceDb))
            assertTrue(readout.contains("the sum is $expectedMagnitude db below the difference"))
            assertFalse("must not quote the design doc's illustrative figure", readout.contains("42"))
        }
    }

    @Test
    fun `stereoPolarityReadout phrases a non-negative sumToDifferenceDb as above, not below`() {
        // Dual-mono (L == R everywhere): difference energy is exactly zero, so the ratio clamps
        // to sumToDifferenceDb's positive floor (+120.0) -- the mirror-image case this readout's
        // sign branch exists for, per StereoPolarityTest's own dual-mono control.
        val mono = ShortArray(480 * 50) { (it % 4000 - 2000).toShort() }
        val interleaved = ShortArray(mono.size * 2)
        for (i in mono.indices) {
            interleaved[2 * i] = mono[i]
            interleaved[2 * i + 1] = mono[i]
        }
        val polarity = stereoPolarity(interleaved)
        assertTrue(polarity.sumToDifferenceDb > 0.0) // precondition this test depends on

        val readout = stereoPolarityReadout(polarity)

        assertTrue(readout.contains(" above the difference"))
    }

    // ---------------------------------------------------------------------
    // stereoPolarityCaption: the anti-phase caveat, in both the "steps" and "silent" branches
    // ---------------------------------------------------------------------

    @Test
    fun `stereoPolarityCaption names the steps and the anti-phase caveat for real stego with a payload`() {
        val cover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)
        val stego = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_INVERSION).encode(ByteArray(20) { it.toByte() })
        val polarity = stereoPolarity(stego)
        assertTrue(polarity.residualWindowMean.any { it != 0f }) // precondition: a real payload was mixed in

        val caption = stereoPolarityCaption(polarity)

        assertTrue("must state the honesty caveat", caption.contains("a plain polarity-flipped recording"))
        assertTrue("must describe the steps as the hidden message", caption.contains("the steps left over are the hidden message"))
    }

    @Test
    fun `stereoPolarityCaption says nothing is hiding when the residual is exactly zero`() {
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

        val caption = stereoPolarityCaption(polarity)

        assertTrue("must still state the honesty caveat", caption.contains("a plain polarity-flipped recording"))
        assertTrue("must say nothing is hiding", caption.contains("nothing is hiding in the sum"))
        assertFalse("must not claim a hidden message that isn't there", caption.contains("hidden message"))
    }

    // ---------------------------------------------------------------------
    // residualMagnificationLabel: the real MIX_AMPLITUDE-derived gain, and the null cases
    // ---------------------------------------------------------------------

    @Test
    fun `residualMagnificationLabel reports the real measured gain for phase-inversion stego`() {
        val cover = synthesizeSampleCover(AudioSampleCover.SOFT_SYNTH)
        val stego = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_INVERSION).encode(ByteArray(20) { it.toByte() })
        val polarity = stereoPolarity(stego)
        val maxAbs = polarity.residualWindowMean.maxOf { abs(it) }
        assertEquals(64.0f, maxAbs, 0.01f) // precondition: MIX_AMPLITUDE, per StereoPolarityTest

        val label = residualMagnificationLabel(polarity.residualWindowMean)

        // 32767 / 64 = 511.98... -> rounds to 512, design-v5.md §4.2's own worked figure.
        assertEquals("magnified ×512", label)
        val gain = ((32767.0 / maxAbs)).roundToInt()
        assertEquals(512, gain)
    }

    @Test
    fun `residualMagnificationLabel is null when there is nothing to magnify`() {
        assertNull(residualMagnificationLabel(FloatArray(0)))
        assertNull(residualMagnificationLabel(floatArrayOf(0f, 0f, 0f)))
    }

    // ---------------------------------------------------------------------
    // Determinism: a caption/readout for the same map/polarity is the same string every time
    // (INV-8's spirit applied to captions -- no hidden randomness).
    // ---------------------------------------------------------------------

    @Test
    fun `caption and readout builders are deterministic for the same inputs`() {
        val cover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)
        val stego = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB).encode(fillerPayload(40))
        val map = stegoDifference(cover, stego)

        assertEquals(stegoDifferenceCaption(map), stegoDifferenceCaption(map))
        assertEquals(
            stegoDifferenceReadout(map, "x"),
            stegoDifferenceReadout(map, "x"),
        )

        val piStego = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_INVERSION).encode(ByteArray(5) { it.toByte() })
        val polarity = stereoPolarity(piStego)
        assertEquals(stereoPolarityCaption(polarity), stereoPolarityCaption(polarity))
        assertEquals(stereoPolarityReadout(polarity), stereoPolarityReadout(polarity))
    }
}
