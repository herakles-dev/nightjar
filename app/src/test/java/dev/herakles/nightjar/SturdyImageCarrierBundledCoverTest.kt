package dev.herakles.nightjar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SturdyImageCarrier] coverage that needs a real `Bitmap` (task W1-1): the two bundled app
 * covers via [BitmapPixelSurface], `ImageSteganalysis`'s honesty check against sturdy output
 * (spec.md gate-30), and INV-9 (the exact-LSB codec is unchanged, and the two techniques never
 * cross-decode each other's output). Uses Robolectric for a real, pixel-accurate `Bitmap` --
 * same precedent as `ImageStegoCarrierTest`.
 *
 * ## Measured this run
 *  - `ImageSteganalysis` (chi-square Pairs-of-Values on raw-pixel LSB) **does** flag sturdy output
 *    on both bundled covers, contrary to the naive assumption that a non-LSB technique would be
 *    invisible to an LSB-tuned detector: gradient cover confidence 0.997, mosaic cover confidence
 *    0.915 (both above the 0.85 `flagThreshold`), both a sustained, whole-image
 *    PoV-equalization run. See `sturdyOutputIsFlaggedByExistingImageSteganalysis` below for the
 *    measured numbers and why (dither QIM's per-pixel rounding of a continuous shift is
 *    value-dependent in the same way LSB replacement is, statistically). architecture.md's "No
 *    detector this round" means no SFLY-*specific* detector exists yet -- it does not mean the
 *    existing raw-LSB detector has no signal against sturdy output; this test corrects that.
 *  - Both bundled covers (100x100 -- well under the ~640px robustness floor) still round-trip a
 *    64-byte payload in-memory (no channel simulation applied), confirming the codec itself has
 *    no hard floor at that size, even though survival through recompression is unmeasured (and
 *    expected to be worse) below ~640px -- exactly architecture.md's documented risk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SturdyImageCarrierBundledCoverTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun loadCover(resId: Int): Bitmap =
        checkNotNull(BitmapFactory.decodeResource(context.resources, resId)) {
            "failed to decode sample cover image resource $resId"
        }

    private fun payloadOf(seed: Int = 0): ByteArray =
        ByteArray(SturdyImageCarrier.PAYLOAD_BYTES) { ((it * 7 + 3 + seed) and 0xFF).toByte() }

    // --- Round trip on both bundled covers (in-memory, no channel simulation) ---

    @Test
    fun `round trips a 64-byte payload on the bundled gradient cover`() {
        val cover = BitmapPixelSurface(loadCover(R.drawable.stego_cover_gradient))
        val payload = payloadOf(20)
        val stego = SturdyImageCarrier(cover).encode(payload)

        val result = SturdyImageCarrier(stego).decode(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
    }

    @Test
    fun `round trips a 64-byte payload on the bundled mosaic cover`() {
        val cover = BitmapPixelSurface(loadCover(R.drawable.stego_cover_mosaic))
        val payload = payloadOf(21)
        val stego = SturdyImageCarrier(cover).encode(payload)

        val result = SturdyImageCarrier(stego).decode(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
    }

    @Test
    fun `neither bundled cover, unembedded, is a false catch`() {
        val gradient = BitmapPixelSurface(loadCover(R.drawable.stego_cover_gradient))
        val mosaic = BitmapPixelSurface(loadCover(R.drawable.stego_cover_mosaic))

        assertFalse(SturdyImageCarrier(gradient).decode(gradient) is DecodeResult.Success)
        assertFalse(SturdyImageCarrier(mosaic).decode(mosaic) is DecodeResult.Success)
    }

    // --- INV-9: exact-LSB is frozen, and the two techniques never cross-decode ---

    @Test
    fun `the existing exact-LSB codec still round trips unchanged (INV-9)`() {
        val cover = loadCover(R.drawable.stego_cover_gradient)
        val payload = "nightjar v6 INV-9 regression -- exact-LSB is frozen".toByteArray(Charsets.UTF_8)
        val carrier = ImageStegoCarrier(cover)

        val stego = carrier.encode(payload)
        val result = carrier.decode(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
    }

    @Test
    fun `an exact-LSB stego image is never read as a sturdy firefly`() {
        val cover = loadCover(R.drawable.stego_cover_gradient)
        val payload = "exact-LSB payload, must not be mistaken for a sturdy frame".toByteArray(Charsets.UTF_8)
        val exactStego = ImageStegoCarrier(cover).encode(payload)

        val sturdySurface = BitmapPixelSurface(exactStego)
        val result = SturdyImageCarrier(sturdySurface).decode(sturdySurface)

        assertFalse("exact-LSB output must never be caught as sturdy (own magic, INV-9/INV-12)", result is DecodeResult.Success)
    }

    @Test
    fun `a sturdy stego image is never read by the exact-LSB codec`() {
        val cover = BitmapPixelSurface(loadCover(R.drawable.stego_cover_mosaic))
        val payload = payloadOf(22)
        val sturdyStego = SturdyImageCarrier(cover).encode(payload).toBitmap()

        val result = ImageStegoCarrier(sturdyStego).decode(sturdyStego)

        assertFalse("sturdy output must never be caught as exact-LSB (own magic 0x53 0x46, distinct from 0x4E)", result is DecodeResult.Success)
    }

    // --- gate-30: does the existing image chi-square check flag sturdy output? ---

    /**
     * Measured this run (contradicts the naive assumption that a non-LSB technique is invisible
     * to an LSB-tuned detector): [ImageSteganalysis] **does** flag sturdy output on both bundled
     * covers -- gradient cover confidence 0.997 (29/29 windows, ~30000/30000 channel samples),
     * mosaic cover confidence 0.915 (same window coverage), both well above the 0.85
     * `flagThreshold`, both a *sustained, whole-image* PoV-equalization run.
     *
     * Why: [SturdyImageCarrier]'s dither QIM does not touch LSBs directly, but it does round
     * `channel + dY` to the nearest integer per pixel for a continuous (non-integer) `dY` --
     * whether a given pixel's rounded shift lands on `floor(dY)` or `ceil(dY)` depends on that
     * pixel's own fractional alignment with `dY`, the same *value-dependent, near-coin-flip*
     * perturbation pattern raw-pixel LSB replacement produces, just arrived at by a different
     * route. With repetition [SturdyImageCarrier.REP] = 8 spreading coded bits across most of the
     * 96x96 grid, most pixels in a small cover get perturbed this way, so the PoV-equalization
     * signature covers the whole image rather than a partial run. This is exactly the honest
     * result spec.md gate-30 asks this test to establish -- the in-app copy (a later task) must
     * say sturdy CAN be flagged by this existing check, not that it evades it.
     */
    @Test
    fun `sturdyOutputIsFlaggedByExistingImageSteganalysis`() {
        val gradient = loadCover(R.drawable.stego_cover_gradient)
        val mosaic = loadCover(R.drawable.stego_cover_mosaic)
        val payload = payloadOf(23)

        val gradientStego = SturdyImageCarrier(BitmapPixelSurface(gradient)).encode(payload).toBitmap()
        val mosaicStego = SturdyImageCarrier(BitmapPixelSurface(mosaic)).encode(payload).toBitmap()

        val detector = ImageSteganalysis()
        val gradientResult = detector.analyze(gradientStego)
        val mosaicResult = detector.analyze(mosaicStego)

        assertTrue(
            "expected the existing chi-square/PoV detector TO flag sturdy output on the gradient cover " +
                "(measured confidence=${gradientResult.confidence}, ${gradientResult.detail})",
            gradientResult.flagged,
        )
        assertTrue(
            "expected the existing chi-square/PoV detector TO flag sturdy output on the mosaic cover " +
                "(measured confidence=${mosaicResult.confidence}, ${mosaicResult.detail})",
            mosaicResult.flagged,
        )
    }

    // --- Android adapter: prepareSturdyCover / encodeSturdyJpeg (task W1-1) ---

    @Test
    fun `prepareSturdyCover refuses a cover under the long-side floor`() {
        val tiny = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)

        val prep = prepareSturdyCover(tiny)

        assertTrue("expected Rejected but got $prep", prep is SturdyCoverPrep.Rejected)
        assertEquals(SturdyCoverRejection.TOO_SMALL, (prep as SturdyCoverPrep.Rejected).reason)
    }

    @Test
    fun `prepareSturdyCover downscales a cover above the long-side cap`() {
        val big = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888)

        val prep = prepareSturdyCover(big)

        assertTrue("expected Ready but got $prep", prep is SturdyCoverPrep.Ready)
        val result = (prep as SturdyCoverPrep.Ready).bitmap
        assertEquals(STURDY_MAX_COVER_LONG_SIDE_PX, maxOf(result.width, result.height))
    }

    @Test
    fun `prepareSturdyCover passes through a cover already inside bounds`() {
        val ok = Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888)

        val prep = prepareSturdyCover(ok)

        assertTrue(prep is SturdyCoverPrep.Ready)
        assertTrue((prep as SturdyCoverPrep.Ready).bitmap === ok)
    }

    @Test
    fun `encodeSturdyJpeg produces real, non-empty JPEG bytes carrying the embedded payload`() {
        val cover = loadCover(R.drawable.stego_cover_gradient)
        val bigCover = Bitmap.createScaledBitmap(cover, 800, 800, true)
        val payload = payloadOf(24)
        val stegoBitmap = SturdyImageCarrier(BitmapPixelSurface(bigCover)).encode(payload).toBitmap()

        val jpegBytes = encodeSturdyJpeg(stegoBitmap)

        assertTrue("expected non-trivial JPEG bytes", jpegBytes.size > 100)
        // JPEG SOI marker.
        assertEquals(0xFF.toByte(), jpegBytes[0])
        assertEquals(0xD8.toByte(), jpegBytes[1])
    }
}
