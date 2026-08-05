package dev.herakles.nightjar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage D/3 (gate-19). Plain JVM verification of [spectrogram] -- no Android/Bitmap dependency,
 * since the transform itself is pure `ShortArray`/`DoubleArray` arithmetic (see that function's
 * KDoc). The queryable proof this stage rests on: a pure sine at a known, bin-exact frequency
 * must peak at the FFT bin `round(freq * frameSize / sampleRateHz)` predicts -- not a screenshot
 * judgment call.
 */
class SpectrogramTest {

    private val sampleRateHz = 48_000
    private val frameSize = 1024

    private fun sineWave(freqHz: Double, durationSamples: Int, amplitude: Int = 20_000): ShortArray =
        ShortArray(durationSamples) { n ->
            (amplitude * sin(2.0 * PI * freqHz * n / sampleRateHz)).roundToInt().toShort()
        }

    private fun peakBinIndex(column: DoubleArray): Int {
        var bestIndex = 0
        var bestValue = column[0]
        for (i in column.indices) {
            if (column[i] > bestValue) {
                bestValue = column[i]
                bestIndex = i
            }
        }
        return bestIndex
    }

    // ---------------------------------------------------------------------
    // Core proof: a pure sine at a known frequency peaks at the predicted bin.
    // ---------------------------------------------------------------------

    @Test
    fun `a pure sine tone peaks at the FFT bin its frequency maps to`() {
        // 1875 Hz -- the AUDIBLE protocol's own F0 (architecture.md Tone plan), bin-exact by
        // design: 1875 * 1024 / 48000 == 40.0 exactly, no rounding slop to account for.
        val freqHz = 1875.0
        val expectedBin = (freqHz * frameSize / sampleRateHz).roundToInt()

        val pcm = sineWave(freqHz, durationSamples = frameSize * 4)
        val data = spectrogram(pcm, channels = 1, frameSize = frameSize, sampleRateHz = sampleRateHz)

        assertTrue("expected at least one column", data.columns.isNotEmpty())
        val middleColumn = data.columns[data.columns.size / 2]
        assertEquals(expectedBin, peakBinIndex(middleColumn))
    }

    // ---------------------------------------------------------------------
    // Grounds gate-19's own MFSK honesty claim: AudioStegoCarrier.kt's MFSK_BASE_BIN (420,
    // ~19.7 kHz) really is where a tone at that bin lands -- the exact band the AUDIO carrier's
    // spectrogram caption claims is visible for the MFSK technique.
    // ---------------------------------------------------------------------

    @Test
    fun `a tone at MFSK_BASE_BIN peaks at bin 420`() {
        val mfskBaseBin = 420
        val freqHz = mfskBaseBin * sampleRateHz.toDouble() / frameSize
        val expectedBin = (freqHz * frameSize / sampleRateHz).roundToInt()

        val pcm = sineWave(freqHz, durationSamples = frameSize * 4)
        val data = spectrogram(pcm, channels = 1, frameSize = frameSize, sampleRateHz = sampleRateHz)

        val middleColumn = data.columns[data.columns.size / 2]
        assertEquals(mfskBaseBin, expectedBin)
        assertEquals(mfskBaseBin, peakBinIndex(middleColumn))
    }

    // ---------------------------------------------------------------------
    // Stereo input is mono-mixed before windowing -- a tone on both channels must still peak at
    // the same bin as the mono case, not vanish or shift.
    // ---------------------------------------------------------------------

    @Test
    fun `stereo interleaved pcm carrying the same tone on both channels peaks at the same bin as mono`() {
        val freqHz = 1875.0
        val expectedBin = (freqHz * frameSize / sampleRateHz).roundToInt()
        val mono = sineWave(freqHz, durationSamples = frameSize * 4)
        val stereo = ShortArray(mono.size * 2)
        for (i in mono.indices) {
            stereo[i * 2] = mono[i]
            stereo[i * 2 + 1] = mono[i]
        }

        val data = spectrogram(stereo, channels = 2, frameSize = frameSize, sampleRateHz = sampleRateHz)

        val middleColumn = data.columns[data.columns.size / 2]
        assertEquals(expectedBin, peakBinIndex(middleColumn))
    }

    // ---------------------------------------------------------------------
    // Edge case: an empty clip produces zero columns rather than throwing or dividing by zero.
    // ---------------------------------------------------------------------

    @Test
    fun `an empty pcm array produces no columns`() {
        val data = spectrogram(ShortArray(0), frameSize = frameSize, sampleRateHz = sampleRateHz)
        assertTrue(data.columns.isEmpty())
        assertEquals(frameSize / 2 + 1, data.binCount)
    }

    // ---------------------------------------------------------------------
    // A clip shorter than one frame still yields exactly one (zero-padded) column, not zero.
    // ---------------------------------------------------------------------

    @Test
    fun `a clip shorter than one frame still produces exactly one column`() {
        val pcm = sineWave(1875.0, durationSamples = frameSize / 4)
        val data = spectrogram(pcm, frameSize = frameSize, sampleRateHz = sampleRateHz)
        assertEquals(1, data.columns.size)
    }

    // ---------------------------------------------------------------------
    // rev-t2 HIGH (gate-19): AudioStegoCarrier's own class KDoc says mono-summing a
    // phase-inversion carrier IS its decode step ("the identical original content
    // phase-cancels out, leaving only the secondary signal audible") -- so spectrogram()'s
    // mono-mix could expose, not hide, that technique's payload. Both tests below encode a
    // real payload through the real codec (same construction AudioStegoCarrierTest.kt's own
    // round-trip tests use) and measure the actual rendered visibility, rather than trusting
    // hand-math either way.
    // ---------------------------------------------------------------------

    private fun fillerPayload(size: Int): ByteArray {
        val text = "nightjar phase-inversion mono-mix exposure check payload bytes "
        return ByteArray(size) { text[it % text.length].code.toByte() }
    }

    /** Same shape as AudioStegoCarrierTest.kt's own private `noiseCover` helper: half-scale
     *  amplitude so the phase-inversion saturating negate/mix-add never clips (that carrier's
     *  own class KDoc's documented caveat), matching the convention every codec test in this
     *  package already follows. */
    private fun noiseCover(numSamples: Int, seed: Long): ShortArray {
        val rng = Random(seed)
        return ShortArray(numSamples) { rng.nextInt(-16_000, 16_001).toShort() }
    }

    /**
     * MEASURED: encoding a payload that fills a cover's phase-inversion capacity, then running
     * [spectrogram] on the resulting interleaved-stereo carrier (exactly what
     * `FireflyCarrierBlock` does), shows the mono-mixed low-frequency residual sitting at
     * `worstNormalizedBrightness = 0.880` of the clip's own per-clip brightness range even in
     * the WORST (dimmest) analysis column -- `minDb=-180.0` (silent-tail/high-bin floor),
     * `maxDb=-6.03`. That's not a borderline case: the payload-carrying residual is the
     * brightest thing in the clip almost everywhere, because mono-summing is exactly what
     * cancels the cover (`AudioStegoCarrier`'s own class KDoc), leaving nothing else in the
     * mix to compete with it for the per-clip normalization ceiling. `0.6` below keeps a
     * comfortable margin under the measured `0.880` for other seeds/cover lengths while still
     * being an unambiguous "clearly visible" bar, nowhere near a coin-flip threshold.
     */
    @Test
    fun `phase-inversion's mono-mixed payload residual is the brightest thing in its own spectrogram`() {
        val segmentSamples = 480 // AudioStegoCarrier.kt's SEGMENT_SAMPLES, recomputed inline
        val cover = noiseCover(numSamples = segmentSamples * 1000, seed = 4242)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_INVERSION)
        val payload = fillerPayload(carrier.maxPayloadBytes)
        val stego = carrier.encode(payload)
        assertEquals(cover.size * 2, stego.size)

        // Same params the production renderer actually uses (FireflyCarrierBlock's caller
        // passes no frameSize/hop override -- default frameSize=1024, hop=512).
        val data = spectrogram(stego, channels = 2, frameSize = frameSize, sampleRateHz = sampleRateHz)
        assertTrue(data.columns.isNotEmpty())

        var minDb = Double.POSITIVE_INFINITY
        var maxDb = Double.NEGATIVE_INFINITY
        for (column in data.columns) {
            for (v in column) {
                if (v < minDb) minDb = v
                if (v > maxDb) maxDb = v
            }
        }
        val range = (maxDb - minDb).coerceAtLeast(1.0)

        // The surviving offset (AudioStegoCarrier's decode-step arithmetic: L+R == offset when
        // no saturation clipping occurred) is a slowly-segment-toggling, near-DC signal --
        // check the lowest few bins, per column, for the WORST (dimmest) case across the clip.
        val lowBins = 0..3
        var worstNormalizedBrightness = Double.POSITIVE_INFINITY
        for (column in data.columns) {
            val columnPeak = lowBins.maxOf { column[it] }
            val normalized = (columnPeak - minDb) / range
            if (normalized < worstNormalizedBrightness) worstNormalizedBrightness = normalized
        }

        assertTrue(
            "expected the phase-inversion payload's mono-mix residual to sit near the clip's own " +
                "brightness ceiling (measured worst-case normalized brightness " +
                "$worstNormalizedBrightness, minDb=$minDb maxDb=$maxDb) -- this is the mono-mix " +
                "EXPOSING the payload, the opposite of 'a spectrogram can't show it'",
            worstNormalizedBrightness > 0.6,
        )
    }

    /**
     * MEASURED: encoding a payload that fills a cover's spectrogram-LSB capacity, then comparing
     * [spectrogram] of the cover against [spectrogram] of the resulting stego (same production
     * params, `frameSize=1024, hop=512`) cell-by-cell finds a `maxAbsDeltaDb` up to ~28 dB at a
     * literal handful of cells (2 of 205,200 exceed 20 dB) -- NOT because the embedding itself
     * is loud (`embedBitInBin`'s own KDoc bounds a single bit-flip to one `QUANTIZATION_STEP`
     * (0.12 nats, about 1 dB) in the encoder's OWN un-windowed, block-aligned FFT basis), but
     * because this file's Hann-windowed, 50%-overlapping analysis grid straddles the encoder's
     * non-overlapping block boundaries at half of its columns, and a rare near-silent bin
     * (magnitude close to the QIM quantization floor) can swing a large RAW dB delta from a
     * small ABSOLUTE one. Measuring that the same way [spectrogramImageBitmap] actually renders
     * it -- per-clip-normalized brightness -- only 4 of 205,200 cells (0.002%) cross a `0.2`
     * (20%-of-range) normalized-brightness delta, and the worst is `0.463`. Four scattered
     * outlier pixels in a 400x513 image of already-noisy random-cover content is not a coherent,
     * eye-catching pattern the way MFSK's full-duration 8-bin band is -- this is what
     * "sub-perceptual" cashes out to. `0.01` (1%) below is two orders of magnitude above the
     * measured fraction, comfortable margin while still asserting "vanishingly small," not "zero."
     */
    @Test
    fun `spectrogram-LSB's QIM nudge affects only a vanishingly small, scattered fraction of pixels`() {
        val audioStegoFrameSize = 1024 // AudioStegoCarrier.kt's own FRAME_SIZE, recomputed inline
        val numFrames = 200
        val cover = noiseCover(numSamples = audioStegoFrameSize * numFrames, seed = 555)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB)
        val payload = fillerPayload(carrier.maxPayloadBytes)
        val stego = carrier.encode(payload)
        assertEquals(cover.size, stego.size)

        // Same params the production renderer actually uses (FireflyCarrierBlock's caller
        // passes no frameSize/hop override -- default frameSize=1024, hop=512, 50% overlap).
        val coverData = spectrogram(cover, channels = 1, frameSize = frameSize, sampleRateHz = sampleRateHz)
        val stegoData = spectrogram(stego, channels = 1, frameSize = frameSize, sampleRateHz = sampleRateHz)
        assertEquals(coverData.columns.size, stegoData.columns.size)

        // spectrogramImageBitmap's own per-clip normalization, applied to the STEGO data --
        // this is the actual pixel-alpha value a real rendered image would carry.
        var stegoMinDb = Double.POSITIVE_INFINITY
        var stegoMaxDb = Double.NEGATIVE_INFINITY
        for (column in stegoData.columns) {
            for (v in column) {
                if (v < stegoMinDb) stegoMinDb = v
                if (v > stegoMaxDb) stegoMaxDb = v
            }
        }
        val stegoRange = (stegoMaxDb - stegoMinDb).coerceAtLeast(1.0)

        var totalCells = 0
        var cellsOverVisibleThreshold = 0
        for (i in coverData.columns.indices) {
            val a = coverData.columns[i]
            val b = stegoData.columns[i]
            for (bin in a.indices) {
                totalCells++
                val coverNormalized = ((a[bin] - stegoMinDb) / stegoRange).coerceIn(0.0, 1.0)
                val stegoNormalized = ((b[bin] - stegoMinDb) / stegoRange).coerceIn(0.0, 1.0)
                if (abs(coverNormalized - stegoNormalized) > 0.2) cellsOverVisibleThreshold++
            }
        }

        val visibleFraction = cellsOverVisibleThreshold.toDouble() / totalCells
        assertTrue(
            "expected SPECTROGRAM_LSB's QIM nudge to affect a vanishingly small, scattered " +
                "fraction of pixels at a meaningfully visible (>0.2 normalized) brightness delta " +
                "(measured $cellsOverVisibleThreshold/$totalCells = $visibleFraction) -- a coherent " +
                "band would fail this the way MFSK's own band should",
            visibleFraction < 0.01,
        )
    }
}
