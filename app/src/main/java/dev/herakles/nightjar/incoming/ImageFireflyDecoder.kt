package dev.herakles.nightjar.incoming

import android.graphics.Bitmap
import dev.herakles.nightjar.DecodeResult

/**
 * Registration point for additional image-carrier decode techniques beyond the built-in
 * exact-LSB [dev.herakles.nightjar.ImageStegoCarrier], which [IncomingRouter.routeImage] always
 * tries first. The v6 sturdy technique (spec.md gate-27, task W1-1) registers itself here by
 * adding an instance to [ImageFireflyDecoderRegistry.decoders].
 *
 * Same never-crash, never-false-positive contract as
 * [dev.herakles.nightjar.CovertCarrier.decode]: implementers must never return
 * [DecodeResult.Success] for corrupt/coincidental data, and must never throw on malformed input
 * ([IncomingRouter] additionally wraps every call in a try/catch as defense in depth, but a
 * well-behaved decoder shouldn't need that safety net).
 */
interface ImageFireflyDecoder {
    /** Machine-readable technique name -- becomes [IncomingOutcome.Caught.technique] and the
     *  `COVERT_DEBUG` probe's `technique` field, e.g. `"STURDY"`. */
    val technique: String

    /** Attempt to recover a payload from [bitmap]. */
    fun decode(bitmap: Bitmap): DecodeResult
}

/**
 * The registration point itself (spec.md v6 receive-plumbing task W1-2): empty until task W1-1
 * lands the sturdy technique and adds its [ImageFireflyDecoder] here.
 * [IncomingRouter.routeImage] reads [decoders] by default; tests inject a fake list directly
 * (`routeImage(..., decoders = listOf(FakeDecoder))`) rather than mutating this object, since it
 * exposes an immutable `List`.
 */
object ImageFireflyDecoderRegistry {
    /** REGISTRATION POINT (task W1-1): add the sturdy technique's [ImageFireflyDecoder] here,
     *  e.g. `listOf(SturdyImageFireflyDecoder())`. */
    val decoders: List<ImageFireflyDecoder> = emptyList()
}
