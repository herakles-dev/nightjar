package dev.herakles.nightjar.incoming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import dev.herakles.nightjar.DecodeResult
import dev.herakles.nightjar.ImageStegoCarrier
import dev.herakles.nightjar.R
import dev.herakles.nightjar.picker.Module
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * [IncomingRouter.routeImage] coverage (spec.md v6 receive-plumbing task W1-2, gate-31/32).
 * Needs a real `Bitmap` (both `ImageStegoCarrier` and `ImageFireflyDecoder` operate on one), so
 * this runs under Robolectric -- same precedent as `ImageStegoSaveRoundTripTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IncomingRouterImageTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val payload = "nightjar receive-plumbing test payload".toByteArray(Charsets.UTF_8)

    private fun loadCover(): Bitmap =
        checkNotNull(BitmapFactory.decodeResource(context.resources, R.drawable.stego_cover_gradient)) {
            "failed to decode sample cover image resource"
        }

    private fun pngBytes(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG compress failed" }
        return out.toByteArray()
    }

    /**
     * Re-encodes [bitmap] as a real, lossily-recompressed JPEG -- destroying the exact-LSB
     * codec's embedded bits, matching gate-28's own "JPEG recompression" survival-testing
     * methodology.
     *
     * Deviation from the task brief's "via javax.imageio": `java.awt`/`javax.imageio` are not on
     * this Android Gradle module's Kotlin unit-test compile classpath (AGP restricts it to
     * Android-compatible JDK APIs even for `testDebugUnitTest`, which runs on a plain JVM) --
     * `Unresolved reference` at compile time confirmed this before falling back to
     * `Bitmap.compress(JPEG)`, Robolectric's own (Skia-backed, real-codec) JPEG encoder, which
     * already backs this same test class's [pngBytes] for PNG. Same effect either way: real
     * lossy DCT/chroma-subsampling recompression that flips exactly the pixel LSBs this codec
     * depends on.
     */
    private fun jpegBytes(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) { "JPEG compress failed" }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): Bitmap =
        checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "failed to decode test PNG/JPEG bytes" }

    @Test
    fun `an exact-LSB PNG is caught in the image jar reporting EXACT_LSB`() {
        val stego = ImageStegoCarrier(loadCover()).encode(payload)
        val bytes = pngBytes(stego)

        val outcome = IncomingRouter.routeImage(bytes, decode(bytes), SniffedType.PNG, "png")

        assertTrue("expected Caught but got $outcome", outcome is IncomingOutcome.Caught)
        outcome as IncomingOutcome.Caught
        assertEquals(Module.IMAGE_STEGANOGRAPHY, outcome.module)
        assertEquals(IncomingRouter.EXACT_LSB_TECHNIQUE, outcome.technique)
        assertTrue(payload.contentEquals(outcome.payload))
        assertTrue(bytes.contentEquals(outcome.carrierBytes))
        assertEquals("png", outcome.extension)
    }

    @Test
    fun `the same stego re-encoded as JPEG resolves to Squeezed, not a false Caught or a crash`() {
        val stego = ImageStegoCarrier(loadCover()).encode(payload)
        val jpeg = jpegBytes(stego)

        val outcome = IncomingRouter.routeImage(jpeg, decode(jpeg), SniffedType.JPEG, "jpg")

        assertEquals(IncomingOutcome.Squeezed(SqueezedContainer.LOSSY_IMAGE), outcome)
    }

    @Test
    fun `a clean, unmodified PNG cover resolves to NoFirefly`() {
        val cover = loadCover()
        val bytes = pngBytes(cover)

        val outcome = IncomingRouter.routeImage(bytes, decode(bytes), SniffedType.PNG, "png")

        assertEquals(IncomingOutcome.NoFirefly, outcome)
    }

    @Test
    fun `a PNG with one corrupted payload bit resolves to Damaged, not Caught or NoFirefly`() {
        val stego = ImageStegoCarrier(loadCover()).encode(payload)
        // Flip exactly one payload bit -- the LSB-embedded frame's HEADER_BYTES is 7 (56 bits),
        // so bit index 56 is the first PAYLOAD bit: pixelIndex = 56 / 3 = 18, channel = 56 % 3 ==
        // 2 (blue) -- XOR-ing the packed pixel int's bit 0 flips exactly that one blue LSB and
        // nothing else, leaving the header's own magic/version/header-CRC untouched so it still
        // verifies, while the payload CRC-32 (or, for an empty payload, the trailer) no longer
        // matches.
        val x = 18 % stego.width
        val y = 18 / stego.width
        stego.setPixel(x, y, stego.getPixel(x, y) xor 0x01)
        val bytes = pngBytes(stego)

        val outcome = IncomingRouter.routeImage(bytes, decode(bytes), SniffedType.PNG, "png")

        assertTrue("expected Damaged but got $outcome", outcome is IncomingOutcome.Damaged)
    }

    @Test
    fun `a registered ImageFireflyDecoder catches what the exact-LSB codec misses`() {
        val cover = loadCover()
        val bytes = pngBytes(cover) // an ordinary, unmodified cover -- exact-LSB must miss it
        val fakePayload = byteArrayOf(1, 2, 3, 4, 5)
        val fakeDecoder = object : ImageFireflyDecoder {
            override val technique = "FAKE_STURDY"
            override fun decode(bitmap: Bitmap): DecodeResult = DecodeResult.Success(fakePayload)
        }

        val outcome = IncomingRouter.routeImage(bytes, decode(bytes), SniffedType.PNG, "png", decoders = listOf(fakeDecoder))

        assertTrue("expected Caught but got $outcome", outcome is IncomingOutcome.Caught)
        outcome as IncomingOutcome.Caught
        assertEquals("FAKE_STURDY", outcome.technique)
        assertTrue(fakePayload.contentEquals(outcome.payload))
    }

    @Test
    fun `a decoder that throws is treated as a miss, not a crash`() {
        val cover = loadCover()
        val bytes = pngBytes(cover)
        val throwingDecoder = object : ImageFireflyDecoder {
            override val technique = "THROWS"
            override fun decode(bitmap: Bitmap): DecodeResult = error("simulated malformed-input failure")
        }

        val outcome = IncomingRouter.routeImage(bytes, decode(bytes), SniffedType.PNG, "png", decoders = listOf(throwingDecoder))

        assertEquals(IncomingOutcome.NoFirefly, outcome)
    }
}
