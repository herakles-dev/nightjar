package dev.herakles.nightjar.incoming

import android.graphics.Bitmap
import dev.herakles.nightjar.AcousticCarrier
import dev.herakles.nightjar.AudioStegoCarrier
import dev.herakles.nightjar.AudioStegoTechnique
import dev.herakles.nightjar.DecodeFailure
import dev.herakles.nightjar.DecodeResult
import dev.herakles.nightjar.ImageStegoCarrier
import dev.herakles.nightjar.NightjarAcoustics
import dev.herakles.nightjar.PcmAudio
import dev.herakles.nightjar.WavFile
import dev.herakles.nightjar.picker.Module

/**
 * Auto-detect-and-route pipeline core (spec.md v6 receive-plumbing, gate-31/32): given a file's
 * already-sniffed [SniffedType] and (for images) an already-decoded [Bitmap], tries every
 * technique nightjar knows and resolves to exactly one [IncomingOutcome] (INV-12).
 *
 * Deliberately free of file/network I/O -- [IncomingPipeline] is the Android-facing entry point
 * that reads bytes, sniffs them, decodes a [Bitmap] (bounded, `inJustDecodeBounds` first) or
 * demuxes compressed audio, and calls into here. [routeWav] in particular needs no Android type
 * at all ([PcmAudio] is a bare `ShortArray`, [WavFile] is pure byte-array parsing), so it's
 * testable with plain JUnit; [routeImage] takes an already-decoded [Bitmap] because
 * [ImageStegoCarrier] and [ImageFireflyDecoder] both operate on one, same as the rest of this
 * app's Module 1 code -- those tests run under Robolectric, matching
 * `ImageStegoSaveRoundTripTest`'s existing precedent for JVM tests that need a real `Bitmap`.
 *
 * ## Damaged vs. "keep trying" mapping
 * A [DecodeResult.Failure] whose [DecodeFailure] is [DecodeFailure.INTEGRITY_MISMATCH] or
 * [DecodeFailure.UNRECOVERABLE_FEC] means the frame's own header (magic + version + header-CRC,
 * or -- for the acoustic modem -- START marker + header RS block) verified: exactly
 * [IncomingOutcome.Damaged]'s definition ("a frame header verified ... but the payload CRC or FEC
 * failed"). [DecodeFailure.NO_PAYLOAD_FOUND], [DecodeFailure.HEADER_INVALID] and
 * [DecodeFailure.PAYLOAD_TOO_LARGE] all mean the header itself never fully verified (no magic/
 * marker match, a bad header-CRC, or a nonsensical declared length) -- INV-12's "never guesses"
 * means those keep falling through to the next technique rather than asserting Damaged on what
 * could just as easily be an ordinary, unrelated file.
 */
object IncomingRouter {

    /** [IncomingOutcome.Caught.technique] for the built-in exact-LSB image codec. */
    const val EXACT_LSB_TECHNIQUE = "EXACT_LSB"

    /** [IncomingOutcome.Caught.technique] for the acoustic modem, shared by the WAV and
     *  compressed-audio paths. */
    const val ACOUSTIC_MODEM_TECHNIQUE = "ACOUSTIC_MODEM"

    /**
     * Every (protocol, symbol-rate) combination [AcousticCarrier] supports. A bare incoming WAV
     * declares neither -- the header's own `mode` byte only disambiguates protocol *after* the
     * right protocol's tone bins have already located a START marker -- so an auto-detect receiver
     * has to try all four combinations rather than assuming the module screen's own AUDIBLE/NORMAL
     * default.
     */
    private val ACOUSTIC_COMBOS = listOf(
        NightjarAcoustics.Protocol.AUDIBLE to NightjarAcoustics.SymbolRate.NORMAL,
        NightjarAcoustics.Protocol.AUDIBLE to NightjarAcoustics.SymbolRate.FAST,
        NightjarAcoustics.Protocol.NEAR_ULTRASONIC to NightjarAcoustics.SymbolRate.NORMAL,
        NightjarAcoustics.Protocol.NEAR_ULTRASONIC to NightjarAcoustics.SymbolRate.FAST,
    )

    /**
     * Images: try the built-in exact-LSB codec first, then every [ImageFireflyDecoder] in
     * [decoders] in order (default [ImageFireflyDecoderRegistry.decoders] -- the sturdy
     * technique's future registration point), stopping at the first match. [sniffed] decides the
     * NoFirefly-vs-Squeezed fallback via [SniffedType.lossless]; [extension] is used to persist
     * [bytes] as the caught firefly's carrier on a [IncomingOutcome.Caught].
     */
    fun routeImage(
        bytes: ByteArray,
        bitmap: Bitmap,
        sniffed: SniffedType,
        extension: String,
        decoders: List<ImageFireflyDecoder> = ImageFireflyDecoderRegistry.decoders,
    ): IncomingOutcome {
        classify(
            safeDecode { ImageStegoCarrier(bitmap).decode(bitmap) },
            EXACT_LSB_TECHNIQUE,
            Module.IMAGE_STEGANOGRAPHY,
            bytes,
            extension,
        )?.let { return it }

        for (decoder in decoders) {
            classify(
                safeDecode { decoder.decode(bitmap) },
                decoder.technique,
                Module.IMAGE_STEGANOGRAPHY,
                bytes,
                extension,
            )?.let { return it }
        }

        return if (sniffed.lossless) {
            IncomingOutcome.NoFirefly
        } else {
            IncomingOutcome.Squeezed(SqueezedContainer.LOSSY_IMAGE)
        }
    }

    /**
     * WAV (lossless container): parses PCM16 directly ([WavFile.decodePcm16], no Android needed)
     * and tries the acoustic modem (every [ACOUSTIC_COMBOS] entry) then all three
     * [AudioStegoTechnique]s at the app's own [AudioStegoCarrier] default `stegoStrength` (the
     * workshop screen never exposes a strength selector, so every nightjar-produced
     * SPECTROGRAM_LSB file already uses that default), reporting whichever matched first. The
     * same raw [pcm] sample array is handed to every technique's `decode()` regardless of the
     * WAV's own declared channel count -- a technique reading the wrong shape of carrier (e.g.
     * PHASE_INVERSION against a genuinely mono file) fails its own header check harmlessly rather
     * than crashing, per every carrier's existing bounds-checked-before-read contract.
     */
    fun routeWav(bytes: ByteArray): IncomingOutcome {
        val parsed = WavFile.decodePcm16(bytes)
            ?: return IncomingOutcome.Unsupported("couldn't read this as audio")
        val pcm = parsed.samples

        for ((protocol, rate) in ACOUSTIC_COMBOS) {
            classify(
                safeDecode { AcousticCarrier(protocol, rate).decode(pcm) },
                ACOUSTIC_MODEM_TECHNIQUE,
                Module.ACOUSTIC_MODEM,
                bytes,
                "wav",
            )?.let { return it }
        }
        for (technique in AudioStegoTechnique.entries) {
            classify(
                safeDecode { AudioStegoCarrier(pcm, technique).decode(pcm) },
                technique.name,
                Module.AUDIO_STEGANOGRAPHY,
                bytes,
                "wav",
            )?.let { return it }
        }
        return IncomingOutcome.NoFirefly
    }

    /**
     * Compressed audio (m4a/ogg/opus/mp3/amr): audio-stego fireflies cannot survive lossy
     * recompression, so only the acoustic modem is tried, against [modemPcm] -- already demuxed/
     * decoded to PCM16 by
     * [dev.herakles.nightjar.incoming.IncomingAndroidAdapters.decodeCompressedAudioForModem]
     * (`null` if the file couldn't be read as audio at all; empty if it read but didn't match the
     * modem's required 48kHz-mono format). Anything short of a modem
     * [IncomingOutcome.Caught]/[IncomingOutcome.Damaged] resolves to
     * [IncomingOutcome.Squeezed] -- a compressed container is always lossy. [extension] persists
     * [bytes] as the caught firefly's carrier on a [IncomingOutcome.Caught].
     */
    fun routeCompressedAudio(bytes: ByteArray, modemPcm: PcmAudio?, extension: String): IncomingOutcome {
        if (modemPcm != null && modemPcm.isNotEmpty()) {
            for ((protocol, rate) in ACOUSTIC_COMBOS) {
                classify(
                    safeDecode { AcousticCarrier(protocol, rate).decode(modemPcm) },
                    ACOUSTIC_MODEM_TECHNIQUE,
                    Module.ACOUSTIC_MODEM,
                    bytes,
                    extension,
                )?.let { return it }
            }
        }
        return IncomingOutcome.Squeezed(SqueezedContainer.COMPRESSED_AUDIO)
    }

    /** `null` means "keep trying the next technique" -- not yet a final [IncomingOutcome]. */
    private fun classify(
        result: DecodeResult?,
        technique: String,
        module: Module,
        carrierBytes: ByteArray,
        extension: String,
    ): IncomingOutcome? = when (result) {
        null -> null
        is DecodeResult.Success -> IncomingOutcome.Caught(module, technique, result.payload, carrierBytes, extension)
        is DecodeResult.Failure -> when (result.reason) {
            DecodeFailure.INTEGRITY_MISMATCH, DecodeFailure.UNRECOVERABLE_FEC -> IncomingOutcome.Damaged(result.detail)
            DecodeFailure.NO_PAYLOAD_FOUND, DecodeFailure.HEADER_INVALID, DecodeFailure.PAYLOAD_TOO_LARGE -> null
        }
    }

    /** Never lets a malformed carrier crash the router (build notes: "never crash on malformed
     *  input") -- a decode attempt that throws is treated the same as one that found nothing. */
    private inline fun safeDecode(block: () -> DecodeResult): DecodeResult? =
        try {
            block()
        } catch (e: Exception) {
            null
        }
}
