package dev.herakles.nightjar

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
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
}
