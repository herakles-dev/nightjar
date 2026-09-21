package dev.herakles.nightjar.trail

import android.graphics.Bitmap
import dev.herakles.nightjar.AcousticCarrier
import dev.herakles.nightjar.AudioStegoCarrier
import dev.herakles.nightjar.AudioStegoTechnique
import dev.herakles.nightjar.ImageStegoCarrier
import dev.herakles.nightjar.PcmAudio

/**
 * Generation core for the three practice fireflies (design/riddle-trail.md § Owner decisions
 * "generated at runtime ... by the production encoders"). Takes riddle text and already-decoded
 * cover samples in, hands carrier data out -- no `Context`, no file I/O, no resource lookup.
 * [PracticeFireflies] is the thin Android adapter around this object: it resolves
 * `R.drawable`/`R.string` resources and writes the results to `filesDir`, and is the only place
 * in this package that touches either.
 *
 * INV-11: every function below takes riddle text IN and returns carrier data (a stego [Bitmap],
 * or stego [PcmAudio] samples) OUT -- never the reverse. [TrailSourceScanTest] asserts no public
 * API in this package returns riddle text.
 */
internal object PracticeFireflyGenerator {

    /** A riddle's primary wording plus its shorter fallback, for the two carriers
     *  (humming/singing) whose capacity the design doc flags as close enough to need one. */
    data class Riddles(val primary: String, val fallback: String)

    /** Which of [Riddles.primary]/[Riddles.fallback] a generator actually used, and the resulting
     *  carrier. The art carrier has no fallback (design/riddle-trail.md: its cover's capacity is
     *  never in question), so only [generateHumming]/[generateSinging] return this. */
    data class Result<T>(val carrier: T, val usedFallback: Boolean)

    /**
     * Exact-LSB (Module 1) encode of [riddle] into [coverImage] via the real
     * [ImageStegoCarrier] -- the art jar's practice firefly. No fallback: both bundled sample
     * cover images (100x100, 3739-byte capacity) comfortably exceed the ~142-byte art riddle, so
     * this only throws (surfacing a real regression) if a future cover or riddle change ever
     * makes that stop being true.
     */
    fun generateArt(coverImage: Bitmap, riddle: String): Bitmap {
        val carrier = ImageStegoCarrier(coverImage)
        return carrier.encode(riddle.toByteArray(Charsets.UTF_8))
    }

    /**
     * Spectrogram-LSB (Module 2) encode into [cover] via the real [AudioStegoCarrier] -- the
     * humming jar's practice firefly. Tries [riddles].primary first; falls back to
     * [riddles].fallback only if the primary doesn't fit this cover's
     * [AudioStegoCarrier.maxPayloadBytes] at default [dev.herakles.nightjar.AudioStegoTechnique]
     * strength (design/riddle-trail.md § Step 2).
     */
    fun generateHumming(cover: PcmAudio, riddles: Riddles): Result<PcmAudio> {
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB)
        val (text, usedFallback) = fittingRiddle(carrier.maxPayloadBytes, riddles)
        return Result(carrier.encode(text.toByteArray(Charsets.UTF_8)), usedFallback)
    }

    /**
     * Acoustic FSK (Module 3) encode via the real [AcousticCarrier] (default AUDIBLE protocol,
     * default NORMAL symbol rate) -- the singing jar's practice firefly. [AcousticCarrier] needs
     * no cover of its own (it synthesizes its tone sequence directly); the "bundled WAV" the
     * design doc describes is this carrier's own [AcousticCarrier.encode] output, written to a
     * .wav file by the [PracticeFireflies] adapter. Tries [riddles].primary first; falls back to
     * [riddles].fallback only if the primary exceeds [AcousticCarrier.maxPayloadBytes] (the fixed
     * architecture-wide 1024-byte ceiling -- design/riddle-trail.md § Step 3).
     */
    fun generateSinging(riddles: Riddles): Result<PcmAudio> {
        val carrier = AcousticCarrier()
        val (text, usedFallback) = fittingRiddle(carrier.maxPayloadBytes, riddles)
        return Result(carrier.encode(text.toByteArray(Charsets.UTF_8)), usedFallback)
    }

    /** [riddles].primary if it fits in [maxPayloadBytes], else [riddles].fallback (with a flag
     *  saying which was used). */
    private fun fittingRiddle(maxPayloadBytes: Int, riddles: Riddles): Pair<String, Boolean> {
        val primaryBytes = riddles.primary.toByteArray(Charsets.UTF_8)
        return if (primaryBytes.size <= maxPayloadBytes) {
            riddles.primary to false
        } else {
            riddles.fallback to true
        }
    }
}
