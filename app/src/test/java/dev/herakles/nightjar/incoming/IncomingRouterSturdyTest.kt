package dev.herakles.nightjar.incoming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import dev.herakles.nightjar.BitmapPixelSurface
import dev.herakles.nightjar.DecodeResult
import dev.herakles.nightjar.ImageStegoCarrier
import dev.herakles.nightjar.R
import dev.herakles.nightjar.SturdyImageCarrier
import dev.herakles.nightjar.picker.Module
import dev.herakles.nightjar.toBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * [IncomingRouter.routeImage] end-to-end coverage for the sturdy technique's registration:
 * [ImageFireflyDecoderRegistry.decoders] now contains [SturdyImageFireflyDecoder], so a
 * shared/opened sturdy photo auto-detects and lands as [IncomingOutcome.Caught] with technique
 * [SturdyImageFireflyDecoder.STURDY_TECHNIQUE] -- exercised through the *default* registry
 * (`IncomingRouter.routeImage(bytes, bitmap, sniffed, extension)`, no explicit `decoders=`
 * override), unlike `IncomingRouterImageTest`'s fake-decoder tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IncomingRouterSturdyTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val payload = ByteArray(SturdyImageCarrier.PAYLOAD_BYTES) { (it * 7 + 3).toByte() }

    private fun loadCover(): Bitmap =
        checkNotNull(BitmapFactory.decodeResource(context.resources, R.drawable.stego_cover_gradient)) {
            "failed to decode sample cover image resource"
        }

    private fun pngBytes(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG compress failed" }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): Bitmap =
        checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "failed to decode test PNG bytes" }

    @Test
    fun `the registry is non-empty now that sturdy is registered`() {
        assertTrue(ImageFireflyDecoderRegistry.decoders.isNotEmpty())
        assertTrue(ImageFireflyDecoderRegistry.decoders.any { it is SturdyImageFireflyDecoder })
    }

    @Test
    fun `a sturdy PNG is caught in the image jar reporting technique STURDY via the default registry`() {
        val cover = BitmapPixelSurface(loadCover())
        val stego = SturdyImageCarrier(cover).encode(payload).toBitmap()
        val bytes = pngBytes(stego)

        val outcome = IncomingRouter.routeImage(bytes, decode(bytes), SniffedType.PNG, "png")

        assertTrue("expected Caught but got $outcome", outcome is IncomingOutcome.Caught)
        outcome as IncomingOutcome.Caught
        assertEquals(Module.IMAGE_STEGANOGRAPHY, outcome.module)
        assertEquals(SturdyImageFireflyDecoder.STURDY_TECHNIQUE, outcome.technique)
        assertTrue(payload.contentEquals(outcome.payload))
    }

    @Test
    fun `an exact-LSB PNG still resolves to EXACT_LSB, not STURDY, now that both are registered`() {
        val exactPayload = "exact-LSB still wins when it actually matches".toByteArray(Charsets.UTF_8)
        val stego = ImageStegoCarrier(loadCover()).encode(exactPayload)
        val bytes = pngBytes(stego)

        val outcome = IncomingRouter.routeImage(bytes, decode(bytes), SniffedType.PNG, "png")

        assertTrue("expected Caught but got $outcome", outcome is IncomingOutcome.Caught)
        outcome as IncomingOutcome.Caught
        assertEquals(IncomingRouter.EXACT_LSB_TECHNIQUE, outcome.technique)
        assertTrue(exactPayload.contentEquals(outcome.payload))
    }

    @Test
    fun `a clean, unmodified cover still resolves to NoFirefly with sturdy registered`() {
        val bytes = pngBytes(loadCover())

        val outcome = IncomingRouter.routeImage(bytes, decode(bytes), SniffedType.PNG, "png")

        assertEquals(IncomingOutcome.NoFirefly, outcome)
    }
}
