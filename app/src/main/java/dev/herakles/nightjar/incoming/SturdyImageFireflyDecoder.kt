package dev.herakles.nightjar.incoming

import android.graphics.Bitmap
import dev.herakles.nightjar.BitmapPixelSurface
import dev.herakles.nightjar.DecodeResult
import dev.herakles.nightjar.SturdyImageCarrier

/**
 * [ImageFireflyDecoder] registration for the v6 sturdy image technique (task W1-1, spec.md
 * gate-27/gate-31). Wraps [SturdyImageCarrier] (pure Kotlin, no `android.*`) with
 * [BitmapPixelSurface] so [IncomingRouter.routeImage] can try it against the same already-decoded
 * [Bitmap] it hands the built-in exact-LSB codec -- same never-crash, never-false-positive
 * contract as [ImageFireflyDecoder] documents (RS + CRC-32 inside [SturdyImageCarrier.decode]
 * make that guarantee, not this wrapper).
 */
class SturdyImageFireflyDecoder : ImageFireflyDecoder {

    override val technique: String = STURDY_TECHNIQUE

    override fun decode(bitmap: Bitmap): DecodeResult {
        val surface = BitmapPixelSurface(bitmap)
        return SturdyImageCarrier(surface).decode(surface)
    }

    companion object {
        /** [IncomingOutcome.Caught.technique] for the sturdy image codec -- same SCREAMING_SNAKE
         *  style as [IncomingRouter.EXACT_LSB_TECHNIQUE] / [IncomingRouter.ACOUSTIC_MODEM_TECHNIQUE]
         *  and the audio techniques' own `AudioStegoTechnique.name` values. */
        const val STURDY_TECHNIQUE = "STURDY"
    }
}
