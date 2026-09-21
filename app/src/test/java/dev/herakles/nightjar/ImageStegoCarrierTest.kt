package dev.herakles.nightjar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verification for task #11 (Module 1 image LSB codec). Uses Robolectric so `Bitmap` is a real,
 * pixel-accurate implementation (getPixel/setPixel/BitmapFactory.decodeResource) rather than the
 * "not mocked" stub android.jar gives a plain JVM unit test — this is what makes the round-trip
 * assertion below a genuine test of the embed/extract bit math, not a no-op.
 *
 * Covers the two things spec INV-3 and the `CovertCarrier` contract require of this codec:
 *  - `decode(encode(payload))` recovers the exact original bytes (round-trip losslessness).
 *  - `encode` throws `IllegalArgumentException` once `payload.size > maxPayloadBytes`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageStegoCarrierTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun loadCover(resId: Int): Bitmap =
        checkNotNull(BitmapFactory.decodeResource(context.resources, resId)) {
            "failed to decode sample cover image resource $resId"
        }

    // --- Capacity formula (width * height * 3 bits, minus the 11-byte header+trailer overhead) ---

    @Test
    fun maxPayloadBytesMatchesTheCapacityFormula() {
        val cover = loadCover(R.drawable.stego_cover_gradient)
        val carrier = ImageStegoCarrier(cover)

        val totalCapacityBytes = (cover.width * cover.height * 3) / 8
        val expectedMaxPayload = totalCapacityBytes - 11 // 7-byte header + 4-byte trailer

        assertEquals(expectedMaxPayload, carrier.maxPayloadBytes)
    }

    // --- Round-trip losslessness (INV-3), on both bundled sample images ---

    @Test
    fun roundTripsExactPayloadBytesOnGradientCover() {
        val cover = loadCover(R.drawable.stego_cover_gradient)
        val carrier = ImageStegoCarrier(cover)
        val payload = "nightjar Module 1 benign test payload — the quick brown fox jumps over the lazy dog."
            .toByteArray(Charsets.UTF_8)

        val stego = carrier.encode(payload)
        val result = carrier.decode(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
        assertEquals(0, result.correctedByteErrors)
    }

    @Test
    fun roundTripsExactPayloadBytesOnMosaicCover() {
        val cover = loadCover(R.drawable.stego_cover_mosaic)
        val carrier = ImageStegoCarrier(cover)
        // Benign synthetic binary payload (not text) to exercise the full byte range, incl. 0x00/0xFF.
        val payload = ByteArray(256) { it.toByte() }

        val stego = carrier.encode(payload)
        val result = carrier.decode(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
    }

    @Test
    fun roundTripsAnEmptyPayload() {
        val cover = loadCover(R.drawable.stego_cover_gradient)
        val carrier = ImageStegoCarrier(cover)

        val stego = carrier.encode(ByteArray(0))
        val result = carrier.decode(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertEquals(0, (result as DecodeResult.Success).payload.size)
    }

    @Test
    fun roundTripsAtExactlyMaxCapacity() {
        val cover = loadCover(R.drawable.stego_cover_gradient)
        val carrier = ImageStegoCarrier(cover)
        val payload = ByteArray(carrier.maxPayloadBytes) { (it % 251).toByte() }

        val stego = carrier.encode(payload)
        val result = carrier.decode(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
    }

    // --- Capacity enforcement ---

    @Test
    fun encodeThrowsWhenPayloadExceedsCapacity() {
        val cover = loadCover(R.drawable.stego_cover_gradient)
        val carrier = ImageStegoCarrier(cover)
        val tooBig = ByteArray(carrier.maxPayloadBytes + 1)

        try {
            carrier.encode(tooBig)
            fail("expected encode() to throw IllegalArgumentException for an over-capacity payload")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    // --- "No payload present" vs. "corrupt payload" distinction ---

    @Test
    fun decodeReturnsNoPayloadFoundForABlankCarrier() {
        // An all-zero bitmap's LSBs read as a 0x00 magic byte, which never matches the codec's
        // magic (0x4E) — this is the deterministic stand-in for "an ordinary image nobody ever
        // embedded a payload into."
        val blank = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        val carrier = ImageStegoCarrier(blank)

        val result = carrier.decode(blank)

        assertTrue("expected Failure but got $result", result is DecodeResult.Failure)
        assertEquals(DecodeFailure.NO_PAYLOAD_FOUND, (result as DecodeResult.Failure).reason)
    }

    @Test
    fun decodeReturnsIntegrityMismatchWhenPayloadBitsAreFlippedAfterEncoding() {
        val cover = loadCover(R.drawable.stego_cover_mosaic)
        val carrier = ImageStegoCarrier(cover)
        val payload = "benign payload that will be corrupted post-encode".toByteArray()

        val stego = carrier.encode(payload)
        // Flip one LSB at pixel 25 (bit index 75, comfortably past the 56-bit/7-byte header, and
        // well inside this ~50-byte payload) to simulate corruption without touching the header —
        // this must surface as INTEGRITY_MISMATCH, never as a silently-wrong Success (the
        // contract's "never deliver corrupt data" rule).
        val corrupted = stego.copy(Bitmap.Config.ARGB_8888, true)
        val cx = 25
        val cy = 0
        val pixel = corrupted.getPixel(cx, cy)
        val a = (pixel shr 24) and 0xFF
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val flippedR = r xor 0x01
        corrupted.setPixel(cx, cy, (a shl 24) or (flippedR shl 16) or (g shl 8) or b)

        val result = carrier.decode(corrupted)

        assertTrue("expected Failure but got $result", result is DecodeResult.Failure)
        assertEquals(DecodeFailure.INTEGRITY_MISMATCH, (result as DecodeResult.Failure).reason)
    }

    // --- PAYLOAD_TOO_LARGE: declared length exceeds the *decoding* carrier's own capacity ---

    @Test
    fun decodeReturnsPayloadTooLargeWhenDeclaredLengthExceedsTheCarrierCapacity() {
        // Encode a valid payload into a reasonably large cover image so the embedded header
        // declares a `length` that only fits a wide/tall carrier.
        val width = 50
        val height = 50
        val cover = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val carrier = ImageStegoCarrier(cover)
        val payload = ByteArray(carrier.maxPayloadBytes) { (it % 251).toByte() } // near-max declared length
        val stego = carrier.encode(payload)

        // Crop to just the first row (same width, height=1): the header (bits 0..55, i.e. pixel
        // indices 0..18) lives entirely within row 0 regardless of height, since pixelIndex < width
        // maps to y=0 — so the header decodes bit-identically, but the *cropped* carrier's own
        // capacity (width * 1 * 3 / 8 bytes) is now far smaller than the length the header declares.
        val tinyCarrier = Bitmap.createBitmap(width, 1, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            tinyCarrier.setPixel(x, 0, stego.getPixel(x, 0))
        }

        val result = carrier.decode(tinyCarrier)

        assertTrue("expected Failure but got $result", result is DecodeResult.Failure)
        assertEquals(DecodeFailure.PAYLOAD_TOO_LARGE, (result as DecodeResult.Failure).reason)
    }

    // --- codec-H01: canEmbed / tiny-cover contract self-consistency ---

    @Test
    fun canEmbedIsFalseForATinyCoverBelowFrameOverhead() {
        // 3x3 ARGB_8888: capacityBytes = floor(9*3/8) = 3, well under the 11-byte frame overhead.
        val tinyCover = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        val carrier = ImageStegoCarrier(tinyCover)

        assertEquals(0, carrier.maxPayloadBytes)
        assertTrue("a 3x3 cover (3-byte capacity) should not report canEmbed", !carrier.canEmbed)
    }

    @Test
    fun canEmbedIsTrueForAnOrdinaryCover() {
        val cover = loadCover(R.drawable.stego_cover_gradient)
        val carrier = ImageStegoCarrier(cover)

        assertTrue("an ordinary bundled cover should report canEmbed", carrier.canEmbed)
    }

    @Test
    fun encodeThrowsAClearMessageOnATinyCoverEvenForAnEmptyPayload() {
        // codec-H01's actual bug: maxPayloadBytes clamps to 0 (payload.size <= 0 passes for an
        // empty payload), but encode() used to still throw from a second, buried capacity check
        // with a message that never named canEmbed/maxPayloadBytes as the real cause. Now the
        // very first check does, and fires even for a 0-byte payload.
        val tinyCover = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        val carrier = ImageStegoCarrier(tinyCover)

        try {
            carrier.encode(ByteArray(0))
            fail("expected encode() to throw IllegalArgumentException for a cover too small to hold a frame")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "expected message to explain the cover is too small, was: ${expected.message}",
                expected.message.orEmpty().contains("too small"),
            )
        }
    }

    // --- HEADER_INVALID: unsupported version byte (zero prior coverage of this branch) ---

    @Test
    fun decodeReturnsHeaderInvalidWhenTheVersionByteIsCorrupted() {
        val cover = loadCover(R.drawable.stego_cover_gradient)
        val carrier = ImageStegoCarrier(cover)
        val payload = "benign payload, header version byte will be corrupted".toByteArray()

        val stego = carrier.encode(payload)
        // Flip the version byte's MSB (global bit index 8 = header byte[1], bit 7 within that
        // byte, per ImageStegoCarrier's documented bit layout: pixelIndex = bitIndex/3, channel =
        // bitIndex%3). That lands on pixel (2, 0)'s blue channel. header_crc is left untouched,
        // but the version check runs BEFORE the CRC check in decode(), so this alone must surface
        // as HEADER_INVALID.
        val corrupted = stego.copy(Bitmap.Config.ARGB_8888, true)
        val cx = 2
        val cy = 0
        val pixel = corrupted.getPixel(cx, cy)
        val a = (pixel shr 24) and 0xFF
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val flippedB = b xor 0x01
        corrupted.setPixel(cx, cy, (a shl 24) or (r shl 16) or (g shl 8) or flippedB)

        val result = carrier.decode(corrupted)

        assertTrue("expected Failure but got $result", result is DecodeResult.Failure)
        assertEquals(DecodeFailure.HEADER_INVALID, (result as DecodeResult.Failure).reason)
    }
}
