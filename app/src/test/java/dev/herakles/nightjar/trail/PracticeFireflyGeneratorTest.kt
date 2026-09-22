package dev.herakles.nightjar.trail

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import dev.herakles.nightjar.AcousticCarrier
import dev.herakles.nightjar.AudioStegoCarrier
import dev.herakles.nightjar.AudioStegoTechnique
import dev.herakles.nightjar.DecodeResult
import dev.herakles.nightjar.ImageStegoCarrier
import dev.herakles.nightjar.NightjarAcoustics
import dev.herakles.nightjar.R
import dev.herakles.nightjar.WavFile
import dev.herakles.nightjar.modules.audiostego.AudioSampleCover
import dev.herakles.nightjar.modules.audiostego.synthesizeSampleCover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rule: "a JVM test proves each riddle fits its carrier's capacity at default settings" plus
 * `decode(encode(riddle)) == riddle bytes` through the real production decoders -- the same
 * round-trip discipline [dev.herakles.nightjar.ImageStegoCarrierTest] /
 * [dev.herakles.nightjar.AudioStegoCarrierTest] already apply to their own codecs. Robolectric
 * (real `Bitmap`, real `Context.getString` against `strings_trail.xml`) for the same reason
 * `ImageStegoCarrierTest` needs it: a plain JVM unit test's stub android.jar can't decode a
 * resource or manipulate pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PracticeFireflyGeneratorTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // ==============================================================================
    // Art (Module 1, exact LSB) -- no fallback riddle exists for this jar.
    // ==============================================================================

    @Test
    fun artRiddleFitsAndRoundTripsThroughTheProductionCodec() {
        val riddle = context.getString(R.string.trail_riddle_art)
        val cover = checkNotNull(BitmapFactory.decodeResource(context.resources, R.drawable.stego_cover_gradient))
        val carrier = ImageStegoCarrier(cover)

        val riddleBytes = riddle.toByteArray(Charsets.UTF_8)
        assertTrue(
            "art riddle (${riddleBytes.size} B) must fit the gradient cover's capacity " +
                "(${carrier.maxPayloadBytes} B)",
            riddleBytes.size <= carrier.maxPayloadBytes,
        )

        val stego = PracticeFireflyGenerator.generateArt(cover, riddle)
        val result = carrier.decode(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(riddleBytes.contentEquals((result as DecodeResult.Success).payload))
        assertEquals(0, result.correctedByteErrors)
    }

    // ==============================================================================
    // Humming (Module 2, spectrogram-LSB) -- primary is expected to fit at default settings;
    // this also proves the fallback path if it ever doesn't (documented outcome below).
    // ==============================================================================

    @Test
    fun hummingPrimaryRiddleFitsAndRoundTripsThroughTheProductionCodec() {
        val riddles = PracticeFireflyGenerator.Riddles(
            primary = context.getString(R.string.trail_riddle_humming),
            fallback = context.getString(R.string.trail_riddle_humming_fallback),
        )
        val cover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB)
        val primaryBytes = riddles.primary.toByteArray(Charsets.UTF_8)

        // Documents the outcome this measured: at the bundled cover/strength combination, the
        // spectrogram-LSB capacity is 457 B at the SPOKEN_WORD cover and default stegoStrength --
        // see AudioStegoScreenTest's own measured figure.
        if (primaryBytes.size > carrier.maxPayloadBytes) {
            fail(
                "primary humming riddle (${primaryBytes.size} B) no longer fits the SPOKEN_WORD " +
                    "cover's spectrogram-LSB capacity (${carrier.maxPayloadBytes} B) -- this was " +
                    "measured to fit when this test was written; hummingFallbackRiddleFitsAndRoundTrips " +
                    "below already proves the fallback path is correct if this ever regresses",
            )
        }

        val result = PracticeFireflyGenerator.generateHumming(cover, riddles)
        assertFalse("expected the primary riddle to be used", result.usedFallback)

        val decodeResult = carrier.decode(result.carrier)
        assertTrue("expected Success but got $decodeResult", decodeResult is DecodeResult.Success)
        assertTrue(primaryBytes.contentEquals((decodeResult as DecodeResult.Success).payload))
        assertEquals(0, decodeResult.correctedByteErrors)
    }

    @Test
    fun hummingFallbackRiddleFitsAndRoundTripsWhenThePrimaryDoesNotFit() {
        // A deliberately short cover whose spectrogram-LSB capacity comfortably holds the short
        // fallback riddle but not an oversized synthetic "primary" -- proves the fallback branch
        // itself (PracticeFireflyGenerator.fittingRiddle) selects and round-trips correctly,
        // independent of whether the real bundled cover ever needs it. 32 frames * 1024
        // samples/frame at default stegoStrength=2 (16 bins/frame) -> capacity = 32*16/8 - 11 =
        // 53 payload bytes: room for the ~34-byte fallback, not for the oversized primary below.
        val shortCover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD).copyOfRange(0, 32 * 1024)
        val carrier = AudioStegoCarrier(shortCover, AudioStegoTechnique.SPECTROGRAM_LSB)
        val oversizedPrimary = "x".repeat(carrier.maxPayloadBytes + 50)
        val fallback = context.getString(R.string.trail_riddle_humming_fallback)
        val fallbackBytes = fallback.toByteArray(Charsets.UTF_8)
        assertTrue(
            "test setup: fallback (${fallbackBytes.size} B) must fit the short cover's capacity " +
                "(${carrier.maxPayloadBytes} B) for this test to be meaningful",
            fallbackBytes.size <= carrier.maxPayloadBytes,
        )

        val riddles = PracticeFireflyGenerator.Riddles(primary = oversizedPrimary, fallback = fallback)
        val result = PracticeFireflyGenerator.generateHumming(shortCover, riddles)
        assertTrue("expected the fallback riddle to be used", result.usedFallback)

        val decodeResult = carrier.decode(result.carrier)
        assertTrue("expected Success but got $decodeResult", decodeResult is DecodeResult.Success)
        assertTrue(fallbackBytes.contentEquals((decodeResult as DecodeResult.Success).payload))
    }

    // ==============================================================================
    // Singing (Module 3, acoustic FSK) -- decoded from a bundled WAV, not a live listen.
    // ==============================================================================

    @Test
    fun singingPrimaryRiddleFitsAndRoundTripsThroughTheProductionCodecAndAWavFile() {
        val riddles = PracticeFireflyGenerator.Riddles(
            primary = context.getString(R.string.trail_riddle_singing),
            fallback = context.getString(R.string.trail_riddle_singing_fallback),
        )
        val carrier = AcousticCarrier()
        val primaryBytes = riddles.primary.toByteArray(Charsets.UTF_8)

        assertTrue(
            "primary singing riddle (${primaryBytes.size} B) must fit AcousticCarrier's fixed " +
                "capacity (${carrier.maxPayloadBytes} B)",
            primaryBytes.size <= carrier.maxPayloadBytes,
        )

        val result = PracticeFireflyGenerator.generateSinging(riddles)
        assertFalse("expected the primary riddle to be used", result.usedFallback)

        // Round-trip through an actual .wav file, matching the rule: "the
        // practice firefly does not require [a live listen] -- the bundled asset is a WAV."
        val wavBytes = WavFile.encodePcm16Mono(result.carrier, NightjarAcoustics.SAMPLE_RATE_HZ)
        val parsed = checkNotNull(WavFile.decodePcm16(wavBytes)) { "failed to parse the singing practice WAV back" }
        assertEquals(NightjarAcoustics.SAMPLE_RATE_HZ, parsed.sampleRateHz)

        val decodeResult = carrier.decode(parsed.samples)
        assertTrue("expected Success but got $decodeResult", decodeResult is DecodeResult.Success)
        assertTrue(primaryBytes.contentEquals((decodeResult as DecodeResult.Success).payload))
        assertEquals(0, decodeResult.correctedByteErrors)
    }

    @Test
    fun singingFallbackRiddleFitsAndRoundTripsWhenThePrimaryDoesNotFit() {
        val carrier = AcousticCarrier()
        val oversizedPrimary = "x".repeat(carrier.maxPayloadBytes + 50)
        val fallback = context.getString(R.string.trail_riddle_singing_fallback)
        val fallbackBytes = fallback.toByteArray(Charsets.UTF_8)

        val riddles = PracticeFireflyGenerator.Riddles(primary = oversizedPrimary, fallback = fallback)
        val result = PracticeFireflyGenerator.generateSinging(riddles)
        assertTrue("expected the fallback riddle to be used", result.usedFallback)

        val decodeResult = carrier.decode(result.carrier)
        assertTrue("expected Success but got $decodeResult", decodeResult is DecodeResult.Success)
        assertTrue(fallbackBytes.contentEquals((decodeResult as DecodeResult.Success).payload))
    }
}
