package dev.herakles.nightjar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import dev.herakles.nightjar.modules.imagestego.encodePngBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task #34 correctness bar for the save-as-PNG / share-via-MediaStore action pair added to
 * `ImageStegoScreen`: the save path must never silently corrupt the embedded LSB payload by
 * defaulting to a lossy format.
 *
 * [encodePngBytes] (in `ImageStegoScreen.kt`) is the *exact* function the MediaStore save/share
 * path calls to turn the working stego [Bitmap] into the bytes it writes to disk — this test
 * calls that same function (not a hand-rolled substitute), then proves the round trip the hard
 * way per task #34's instructions: decode the produced PNG bytes back into a brand-new [Bitmap]
 * (as if a separate process had just re-opened the saved file from the gallery) and run *that*
 * reloaded bitmap through [ImageStegoCarrier.decode] — not the original in-memory stego bitmap,
 * which would only prove `encode`/`decode` round-trip, not that the save path preserves it. A
 * naive save path that defaulted to `Bitmap.CompressFormat.JPEG` (or any lossy format) would
 * still make `bitmap.compress(...)` return `true` — i.e. would pass a shallow "did compress()
 * report success" check — but would fail this one, since JPEG's chroma subsampling and DCT
 * quantization flip exactly the pixel LSBs this codec depends on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageStegoSaveRoundTripTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun loadCover(resId: Int): Bitmap =
        checkNotNull(BitmapFactory.decodeResource(context.resources, resId)) {
            "failed to decode sample cover image resource $resId"
        }

    private fun reloadAsPng(bitmap: Bitmap): Bitmap {
        val pngBytes = encodePngBytes(bitmap)
        return checkNotNull(BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)) {
            "failed to decode the PNG bytes produced by encodePngBytes back into a Bitmap"
        }
    }

    // --- First embed/save cycle: a bundled sample cover, a text payload ---

    @Test
    fun savedPngBytesRoundTripATextPayloadOnGradientCover() {
        val cover = loadCover(R.drawable.stego_cover_gradient)
        val carrier = ImageStegoCarrier(cover)
        val payload = "nightjar task #34 save-path round trip — the ravens have landed."
            .toByteArray(Charsets.UTF_8)

        val stego = carrier.encode(payload)
        val reloaded = reloadAsPng(stego)

        assertEquals(stego.width, reloaded.width)
        assertEquals(stego.height, reloaded.height)

        val result = ImageStegoCarrier(reloaded).decode(reloaded)
        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
        assertEquals(0, result.correctedByteErrors)
    }

    // --- Full byte-range binary payload (not just text) on the other bundled sample cover ---

    @Test
    fun savedPngBytesRoundTripABinaryPayloadOnMosaicCover() {
        val cover = loadCover(R.drawable.stego_cover_mosaic)
        val carrier = ImageStegoCarrier(cover)
        val payload = ByteArray(256) { it.toByte() } // exercises 0x00/0xFF, not just printable text

        val stego = carrier.encode(payload)
        val reloaded = reloadAsPng(stego)

        val result = ImageStegoCarrier(reloaded).decode(reloaded)
        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
    }

    // --- Empty payload: the degenerate "header+trailer only, zero payload bytes" edge case ---

    @Test
    fun savedPngBytesRoundTripAnEmptyPayload() {
        val cover = loadCover(R.drawable.stego_cover_gradient)
        val carrier = ImageStegoCarrier(cover)

        val stego = carrier.encode(ByteArray(0))
        val reloaded = reloadAsPng(stego)

        val result = ImageStegoCarrier(reloaded).decode(reloaded)
        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertEquals(0, (result as DecodeResult.Success).payload.size)
    }

    // --- A CoverSource.Picked-style cover (task #33): not a bundled resource, embedded at
    // exactly max capacity — the largest-payload-per-image case, through the save path. ---

    @Test
    fun savedPngBytesRoundTripAMaxCapacityPayloadOnAPickedPhotoStyleCover() {
        val cover = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val r = (x * 4) and 0xFF
                    val g = (y * 4) and 0xFF
                    setPixel(x, y, (0xFF shl 24) or (r shl 16) or (g shl 8) or 0x7F)
                }
            }
        }
        val carrier = ImageStegoCarrier(cover)
        val payload = ByteArray(carrier.maxPayloadBytes) { (it % 251).toByte() }

        val stego = carrier.encode(payload)
        val reloaded = reloadAsPng(stego)

        val result = ImageStegoCarrier(reloaded).decode(reloaded)
        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
    }
}
