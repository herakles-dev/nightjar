package dev.herakles.nightjar.modules.fireflyjar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v5 addition (design-v5.md §5, gate-24/25). Plain JUnit4, no Robolectric — [audioCarrierViewOptions]
 * is pure `String?`/`Int` -> `List<AudioCarrierView>`, no Compose or Android type involved.
 *
 * Gate-25's own requirement, restated as a test: "difference" only ever appears for a
 * spectrogram-LSB mono clip, "polarity" only ever for a phase-inversion stereo clip, and every
 * other combination — MFSK, the acoustic modem's `null` technique, and the two "technique
 * matches but the channel count doesn't" edge cases the real codec never produces but this
 * function doesn't get to assume away — offers only the two baseline views (gate-19) with no new
 * option.
 */
class AudioCarrierViewOptionsTest {

    @Test
    fun `MFSK offers only the two baseline views`() {
        assertEquals(
            listOf(AudioCarrierView.WAVEFORM, AudioCarrierView.SPECTROGRAM),
            audioCarrierViewOptions(technique = "MFSK", numChannels = 1),
        )
    }

    @Test
    fun `the acoustic modem's null technique offers only the two baseline views`() {
        assertEquals(
            listOf(AudioCarrierView.WAVEFORM, AudioCarrierView.SPECTROGRAM),
            audioCarrierViewOptions(technique = null, numChannels = 1),
        )
    }

    @Test
    fun `spectrogram-LSB mono adds difference, not polarity`() {
        assertEquals(
            listOf(AudioCarrierView.WAVEFORM, AudioCarrierView.SPECTROGRAM, AudioCarrierView.DIFFERENCE),
            audioCarrierViewOptions(technique = "SPECTROGRAM_LSB", numChannels = 1),
        )
    }

    @Test
    fun `spectrogram-LSB stereo -- unreached by the real codec -- adds no new option`() {
        assertEquals(
            listOf(AudioCarrierView.WAVEFORM, AudioCarrierView.SPECTROGRAM),
            audioCarrierViewOptions(technique = "SPECTROGRAM_LSB", numChannels = 2),
        )
    }

    @Test
    fun `phase-inversion stereo adds polarity, not difference`() {
        assertEquals(
            listOf(AudioCarrierView.WAVEFORM, AudioCarrierView.SPECTROGRAM, AudioCarrierView.POLARITY),
            audioCarrierViewOptions(technique = "PHASE_INVERSION", numChannels = 2),
        )
    }

    @Test
    fun `phase-inversion mono -- unreached by the real codec -- adds no new option`() {
        assertEquals(
            listOf(AudioCarrierView.WAVEFORM, AudioCarrierView.SPECTROGRAM),
            audioCarrierViewOptions(technique = "PHASE_INVERSION", numChannels = 1),
        )
    }

    @Test
    fun `a pre-migration record's null technique and mono channel count adds no new option`() {
        assertEquals(
            listOf(AudioCarrierView.WAVEFORM, AudioCarrierView.SPECTROGRAM),
            audioCarrierViewOptions(technique = null, numChannels = 1),
        )
    }

    @Test
    fun `an unrecognized technique string adds no new option`() {
        // Defensive: audioCarrierViewOptions never throws on a technique it doesn't recognize --
        // same "require, don't guess ... but don't crash on the unexpected either" discipline
        // this package's other pure builders follow.
        assertEquals(
            listOf(AudioCarrierView.WAVEFORM, AudioCarrierView.SPECTROGRAM),
            audioCarrierViewOptions(technique = "SOMETHING_ELSE", numChannels = 2),
        )
    }
}
