package dev.herakles.nightjar

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Correctness bar for the "save phase-inversion stego audio as a .wav file" path: proves the
 * save step doesn't silently corrupt the embedded payload, the same guarantee
 * `ImageStegoSaveRoundTripTest` establishes for the image codec's save-as-PNG path. Unlike that
 * test, no Robolectric/Android `Bitmap` machinery is needed here — [AudioStegoCarrier] and
 * [WavFile] are both pure byte/short-array arithmetic (see their class KDocs), so this is a plain
 * JUnit4 test, matching `WavFileTest`'s own no-Android-dependency convention.
 *
 * [AudioStegoCarrier.encode]'s PHASE_INVERSION output is interleaved stereo (see that class's
 * KDoc), so the save path must go through [WavFile.encodePcm16Stereo] (`NumChannels = 2`), not
 * [WavFile.encodePcm16Mono] — this test proves that choice round-trips correctly through the
 * already-existing, channel-count-generic [WavFile.decodePcm16] (which returns `numChannels` from
 * the file's own header rather than assuming mono, precisely so a test like this one can exist
 * without needing a stereo-specific decode path).
 */
class AudioStegoSaveRoundTripTest {

    private val sampleRateHz = NightjarAcoustics.SAMPLE_RATE_HZ

    @Test
    fun savedWavBytesRoundTripATextPayloadThroughDecodePcm16AndAudioStegoCarrier() {
        val cover = noiseCover(numSamples = SEGMENT_SAMPLES * 2000, seed = 17)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_INVERSION)
        val payload = "nightjar audio stego save-path round trip - the ravens have landed."
            .toByteArray(Charsets.UTF_8)

        val stego = carrier.encode(payload)
        val wavBytes = WavFile.encodePcm16Stereo(stego, sampleRateHz)
        val parsed = WavFile.decodePcm16(wavBytes)

        assertTrue("a WAV this app itself produced must parse back", parsed != null)
        requireNotNull(parsed)
        assertEquals("stereo channel count must survive the WAV round trip", 2, parsed.numChannels)
        assertEquals("sample rate must survive the WAV round trip", sampleRateHz, parsed.sampleRateHz)
        assertEquals(
            "interleaved sample count must survive the WAV round trip",
            stego.size,
            parsed.samples.size,
        )

        val result = carrier.decode(parsed.samples)
        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
        assertEquals(0, result.correctedByteErrors)
    }

    @Test
    fun savedWavBytesRoundTripAnEmptyPayload() {
        val cover = noiseCover(numSamples = SEGMENT_SAMPLES * 200, seed = 23)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_INVERSION)

        val stego = carrier.encode(ByteArray(0))
        val wavBytes = WavFile.encodePcm16Stereo(stego, sampleRateHz)
        val parsed = WavFile.decodePcm16(wavBytes)

        assertTrue("a WAV this app itself produced must parse back", parsed != null)
        requireNotNull(parsed)
        assertEquals(2, parsed.numChannels)

        val result = carrier.decode(parsed.samples)
        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertEquals(0, (result as DecodeResult.Success).payload.size)
    }

    /**
     * Same noise-cover convention as `AudioStegoCarrierTest` — half-full-scale amplitude or below
     * so PHASE_INVERSION's saturating negate/mix-amplitude-add steps never clip, keeping this
     * round trip deterministic per the codec's documented near-full-scale fidelity caveat.
     */
    private fun noiseCover(numSamples: Int, seed: Long): PcmAudio {
        val rng = Random(seed)
        return ShortArray(numSamples) { rng.nextInt(-16_000, 16_001).toShort() }
    }

    companion object {
        /** Mirrors AudioStegoCarrier's documented segment size (10 ms @ 48 kHz). */
        private const val SEGMENT_SAMPLES = 480
    }
}
