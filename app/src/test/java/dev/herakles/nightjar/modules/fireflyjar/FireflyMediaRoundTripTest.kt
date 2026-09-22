package dev.herakles.nightjar.modules.fireflyjar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import dev.herakles.nightjar.AcousticCarrier
import dev.herakles.nightjar.AudioStegoCarrier
import dev.herakles.nightjar.AudioStegoTechnique
import dev.herakles.nightjar.DecodeResult
import dev.herakles.nightjar.ImageStegoCarrier
import dev.herakles.nightjar.NightjarAcoustics
import dev.herakles.nightjar.PcmAudio
import dev.herakles.nightjar.R
import dev.herakles.nightjar.WavFile
import dev.herakles.nightjar.modules.imagestego.encodePngBytes
import java.io.File
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verification that "carrier media is captured and
 * provably intact ... a round-trip test shows the bytes the media store wrote decode back to a
 * bitmap/PCM that still yields the original payload through that carrier's own `decode`."
 *
 * The three creating jars (image LSB, audio-stego, acoustic modem) each now write their carrier
 * media through [FireflyMediaStore] at catch time (see
 * `ImageStegoScreen.kt`/`AudioStegoScreen.kt`/`AcousticModemScreen.kt`'s `insertFireflyWithCarrier`
 * helpers). This suite proves the FULL chain those helpers depend on, one test per carrier:
 * `carrier.encode(payload)` -> the exact file-bytes function the catch site calls
 * ([dev.herakles.nightjar.modules.imagestego.encodePngBytes] /
 * [WavFile.encodePcm16Mono] / [WavFile.encodePcm16Stereo]) -> [FireflyMediaStore.write] ->
 * [FireflyMediaStore.read] -> the exact parse-back function the detail screen will eventually
 * call (`BitmapFactory.decodeByteArray` / [WavFile.decodePcm16]) -> that SAME carrier's own
 * `decode()` -> assert the original payload comes back out. A shallow "did `write`/`read` return
 * without throwing" check would miss a codec whose save path silently picks a lossy format or a
 * wrong channel count, exactly the way `ImageStegoSaveRoundTripTest`'s KDoc explains for the
 * (separate) MediaStore-gallery save path this class does NOT exercise — [FireflyMediaStore] is
 * the app-private catch-time store (`FireflyMediaStoreTest.kt`), a different code path with the
 * same "prove it round-trips, don't just prove it wrote" correctness bar.
 *
 * Robolectric setup mirrors [FireflyMediaStoreTest] (real `Context.filesDir`, `fireflies`
 * directory cleaned in [setUp]/[tearDown] for deterministic reruns).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FireflyMediaRoundTripTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var directory: File
    private lateinit var store: FireflyMediaStore

    private val sampleRateHz = NightjarAcoustics.SAMPLE_RATE_HZ

    @Before
    fun setUp() {
        directory = File(context.filesDir, "fireflies")
        directory.deleteRecursively()
        store = FireflyMediaStore(context)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    // -----------------------------------------------------------------------------------------
    // 1. IMAGE — encodePngBytes -> store -> read -> BitmapFactory.decodeByteArray ->
    // ImageStegoCarrier.decode. PNG is lossless; if the LSB payload doesn't survive, this is the
    // test that says so (same rationale as ImageStegoSaveRoundTripTest, applied to the
    // app-private FireflyMediaStore path instead of the MediaStore-gallery save path).
    // -----------------------------------------------------------------------------------------

    @Test
    fun imageCarrierPayloadSurvivesTheFireflyMediaStoreRoundTrip() {
        val cover = checkNotNull(BitmapFactory.decodeResource(context.resources, R.drawable.stego_cover_gradient)) {
            "failed to decode sample cover image resource"
        }
        val carrier = ImageStegoCarrier(cover)
        val payload = "firefly image round trip - the ravens have landed."
            .toByteArray(Charsets.UTF_8)

        val stego = carrier.encode(payload)
        val pngBytes = encodePngBytes(stego)

        val filename = store.write(pngBytes, "png")
        val readBack = checkNotNull(store.read(filename)) {
            "FireflyMediaStore.read returned null for a file it just wrote"
        }
        assertTrue("stored PNG bytes must round-trip byte-exact", pngBytes.contentEquals(readBack))

        val reloaded = checkNotNull(BitmapFactory.decodeByteArray(readBack, 0, readBack.size)) {
            "failed to decode the FireflyMediaStore-read bytes back into a Bitmap"
        }

        val result = ImageStegoCarrier(reloaded).decode(reloaded)
        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
        assertEquals(0, result.correctedByteErrors)
    }

    // -----------------------------------------------------------------------------------------
    // 2. AUDIO-STEGO MONO — SPECTROGRAM_LSB, the technique that (like MFSK) leaves
    // AudioStegoController.workingChannelCount at 1: AudioStegoScreen.kt's
    // insertFireflyWithCarrier picks WavFile.encodePcm16Mono for exactly this case ("every
    // technique except PHASE_INVERSION"). encodePcm16Mono -> store -> read -> decodePcm16 ->
    // AudioStegoCarrier.decode.
    // -----------------------------------------------------------------------------------------

    @Test
    fun audioStegoMonoCarrierPayloadSurvivesTheFireflyMediaStoreRoundTrip() {
        val cover = spectrogramNoiseCover(numFrames = 200, seed = 11)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB, stegoStrength = 2)
        val payload = "firefly audio-stego mono round trip".toByteArray(Charsets.UTF_8)

        val stego = carrier.encode(payload)
        val wavBytes = WavFile.encodePcm16Mono(stego, sampleRateHz)

        val filename = store.write(wavBytes, "wav")
        val readBack = checkNotNull(store.read(filename)) {
            "FireflyMediaStore.read returned null for a file it just wrote"
        }
        val parsed = checkNotNull(WavFile.decodePcm16(readBack)) {
            "a WAV FireflyMediaStore itself round-tripped must still parse back"
        }
        assertEquals("mono channel count must survive the store round trip", 1, parsed.numChannels)
        assertEquals("sample rate must survive the store round trip", sampleRateHz, parsed.sampleRateHz)

        val result = carrier.decode(parsed.samples)
        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
        assertEquals(0, result.correctedByteErrors)
    }

    // -----------------------------------------------------------------------------------------
    // 3. AUDIO-STEGO STEREO (PRIORITY) — PHASE_INVERSION's encode() output is interleaved
    // stereo (AudioStegoCarrier's own class KDoc); AudioStegoScreen.kt's insertFireflyWithCarrier
    // reads controller.workingChannelCount == 2 for exactly this technique and calls
    // WavFile.encodePcm16Stereo, NOT encodePcm16Mono. encodePcm16Stereo had zero production call
    // sites before it was wired in, so this is the test that would catch a wrong-channel-count
    // regression (a WAV that "writes and reads successfully" but plays at the wrong speed) --
    // hence the explicit channel-count assertion below, not just a payload-decodes check.
    // -----------------------------------------------------------------------------------------

    @Test
    fun audioStegoStereoCarrierPayloadSurvivesTheFireflyMediaStoreRoundTrip() {
        val cover = noiseCover(numSamples = SEGMENT_SAMPLES * 2000, seed = 17)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_INVERSION)
        val payload = "firefly audio-stego STEREO round trip - the ravens have landed."
            .toByteArray(Charsets.UTF_8)

        val stego = carrier.encode(payload)
        val wavBytes = WavFile.encodePcm16Stereo(stego, sampleRateHz)

        val filename = store.write(wavBytes, "wav")
        val readBack = checkNotNull(store.read(filename)) {
            "FireflyMediaStore.read returned null for a file it just wrote"
        }
        val parsed = checkNotNull(WavFile.decodePcm16(readBack)) {
            "a WAV FireflyMediaStore itself round-tripped must still parse back"
        }
        // The header assertion (task requirement): sample rate AND channel count must survive
        // the FireflyMediaStore round trip, independent of whether the payload happens to decode
        // -- a payload can decode correctly from a WAV whose header lies about its channel count,
        // and that WAV will still play back at the wrong speed on the detail screen later.
        assertEquals("stereo channel count must survive the store round trip", 2, parsed.numChannels)
        assertEquals("sample rate must survive the store round trip", sampleRateHz, parsed.sampleRateHz)
        assertEquals(
            "interleaved sample count must survive the store round trip",
            stego.size,
            parsed.samples.size,
        )

        val result = carrier.decode(parsed.samples)
        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
        assertEquals(0, result.correctedByteErrors)
    }

    // -----------------------------------------------------------------------------------------
    // 4. ACOUSTIC — AcousticCarrier.encode -> WavFile.encodePcm16Mono -> store -> read ->
    // decodePcm16 -> AcousticCarrier.decode. Full encode/decode, not scoped down: WavFileTest's
    // own "decodePcm16 round-trips a full AcousticCarrier encode-decode cycle" test already
    // proves a frame-0-aligned encode()->decode() cycle at this payload scale runs fine inside
    // the existing suite (no speaker/mic, no leading-silence search cost worth mentioning), so
    // adding one more FireflyMediaStore write/read hop in the middle is not a meaningful cost --
    // no scoping down was needed for this case.
    // -----------------------------------------------------------------------------------------

    @Test
    fun acousticCarrierPayloadSurvivesTheFireflyMediaStoreRoundTrip() {
        val carrier = AcousticCarrier(NightjarAcoustics.Protocol.AUDIBLE, NightjarAcoustics.SymbolRate.NORMAL)
        val payload = "firefly acoustic round trip".toByteArray(Charsets.US_ASCII)

        val pcm = carrier.encode(payload)
        val wavBytes = WavFile.encodePcm16Mono(pcm, sampleRateHz)

        val filename = store.write(wavBytes, "wav")
        val readBack = checkNotNull(store.read(filename)) {
            "FireflyMediaStore.read returned null for a file it just wrote"
        }
        val parsed = checkNotNull(WavFile.decodePcm16(readBack)) {
            "a WAV FireflyMediaStore itself round-tripped must still parse back"
        }
        assertEquals("mono channel count must survive the store round trip", 1, parsed.numChannels)
        assertEquals("sample rate must survive the store round trip", sampleRateHz, parsed.sampleRateHz)

        val result = carrier.decode(parsed.samples)
        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
        assertEquals(0, result.correctedByteErrors)
    }

    // -----------------------------------------------------------------------------------------
    // Fixtures — mirror AudioStegoCarrierTest's own noiseCover/spectrogramNoiseCover conventions
    // (half-full-scale or [-16000,16000] amplitude so saturating arithmetic never clips and the
    // round trip stays deterministic).
    // -----------------------------------------------------------------------------------------

    private fun noiseCover(numSamples: Int, seed: Long): PcmAudio {
        val rng = Random(seed)
        return ShortArray(numSamples) { rng.nextInt(-16_000, 16_001).toShort() }
    }

    private fun spectrogramNoiseCover(numFrames: Int, seed: Long): PcmAudio {
        val rng = Random(seed)
        return ShortArray(SPECTROGRAM_FRAME_SIZE * numFrames) { rng.nextInt(-16_000, 16_001).toShort() }
    }

    companion object {
        /** Mirrors AudioStegoCarrier's documented phase-inversion segment size (10 ms @ 48 kHz). */
        private const val SEGMENT_SAMPLES = 480

        /** Mirrors AudioStegoCarrier's documented spectrogram-LSB frame size. */
        private const val SPECTROGRAM_FRAME_SIZE = 1024
    }
}
