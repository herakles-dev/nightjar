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
 * This measurement checks whether the existing image chi-square/PoV check
 * ([ImageSteganalysis]) gives a *meaningful* reading against real, natural-photo sturdy output --
 * not just the two bundled 100x100 synthetic covers [SturdyImageCarrierBundledCoverTest] already
 * measured (gradient/mosaic, both flagged: 0.997/0.915). Three real photographs (public-domain
 * Unsplash sources via Lorem Picsum, `sturdy_photos/real_photo_{1,2,3}.jpg`, ~114-135KB each,
 * 900x700, well above the 640px sturdy floor) go through the exact pipeline the app itself ships:
 * [SturdyImageCarrier.encode] -> [encodeSturdyJpeg] (real `Bitmap.compress`, JPEG q90, this app's
 * `STURDY_JPEG_QUALITY`) -> decode back to a `Bitmap` -- plus the two real-channel simulated
 * chains already used elsewhere ([simulateFacebookLikeChannel]/[simulateMmsLikeChannel],
 * `JpegLumaSim.kt`), with an unembedded copy of each photo through the identical pipelines as the
 * false-positive baseline.
 *
 * ## Measured this run (mean confidence, `flagThreshold` = 0.85)
 * ```
 *                          photo1    photo2    photo3
 * sturdy, pristine (no JPEG)  0.905     0.943     0.492  -- flagged: yes, yes, NO
 * sturdy, JPEG q90            0.868     0.985     0.565  -- flagged: yes, yes, NO
 * clean,  JPEG q90            0.966     0.989     0.469  -- flagged: yes, yes, NO  (same per photo!)
 * sturdy, after Facebook-like 0.00009   0.008     0.065  -- flagged: no, no, no
 * clean,  after Facebook-like 0.00001   0.007     0.037  -- flagged: no, no, no
 * sturdy, after MMS-like      0.0003    0.013     0.057  -- flagged: no, no, no
 * clean,  after MMS-like      0.010     0.107     0.027  -- flagged: no, no, no
 * ```
 * (`payload survives the real q90 JPEG round trip`: true on all three photos -- STURDY_JPEG_QUALITY
 * costs nothing against those margins.)
 *
 * ## The honest finding (contradicts the bundled-cover result generalizing)
 * On the two tiny synthetic bundled covers, sturdy output was flagged 2/2 with wide margin. On
 * three real photos it is flagged 2/3 at every pre-send stage (pristine and after this app's own
 * q90 JPEG export) -- **and the flagged/clear verdict is identical, photo for photo, to what an
 * untouched copy of the same photo gets** at that same stage (`sturdyJpegQ90.flagged ==
 * cleanJpegQ90.flagged` for all three). The chi-square/PoV run this detector finds is driven by
 * *that photo's own JPEG-quantization pattern*, not by whether anything is hidden in it -- exactly
 * the risk already flagged ahead of time: "any flag
 * on sturdy output may come from JPEG or the cell shifts rather than a recognised technique."
 * Once a photo goes through an actual send-shaped recompression pass (Facebook-like or MMS-like),
 * confidence collapses near zero for sturdy AND clean alike -- the check essentially never flags
 * either one post-send. `ImageStegoScreen.kt`'s sturdy check caption is written from these numbers,
 * not the bundled-cover ones, since real photos are what the operator actually embeds into.
 *
 * Margins: `flagThreshold` = 0.85 ([ImageSteganalysis.FLAG_THRESHOLD]); the widest observed
 * per-photo (sturdy - clean) gap at the JPEG q90 stage is 0.10 (photo1: -0.098, photo2: -0.005,
 * photo3: +0.097) -- small next to the ~0.30-0.95 gaps the *bundled* covers showed between clean
 * and embedded, confirming real photos are the harder, more honest measurement surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SturdyImageSteganalysisRealPhotoTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val detector = ImageSteganalysis()

    private fun loadRealPhoto(name: String): Bitmap {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("sturdy_photos/$name")) {
            "missing test resource sturdy_photos/$name"
        }
        return stream.use { checkNotNull(BitmapFactory.decodeStream(it)) { "failed to decode $name" } }
    }

    private fun payloadOf(seed: Int): ByteArray =
        ByteArray(SturdyImageCarrier.PAYLOAD_BYTES) { ((it * 7 + 3 + seed) and 0xFF).toByte() }

    private val realPhotos = listOf("real_photo_1.jpg", "real_photo_2.jpg", "real_photo_3.jpg")

    @Test
    fun `sturdy payload survives the real q90 JPEG round trip on every real photo`() {
        for (name in realPhotos) {
            val cover = loadRealPhoto(name)
            val payload = payloadOf(name.hashCode())
            val embedded = SturdyImageCarrier(BitmapPixelSurface(cover)).encode(payload).toBitmap()
            val jpegBytes = encodeSturdyJpeg(embedded, quality = STURDY_JPEG_QUALITY)
            val decoded = checkNotNull(BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size))
            val surface = BitmapPixelSurface(decoded)

            val result = SturdyImageCarrier(surface).decode(surface)

            assertTrue("expected Success for $name but got $result", result is DecodeResult.Success)
            assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
        }
    }

    @Test
    fun `the check's flagged verdict on saved q90 JPEG output matches the same photo unembedded (not a real signal)`() {
        for (name in realPhotos) {
            val cover = loadRealPhoto(name)
            val payload = payloadOf(name.hashCode())
            val embedded = SturdyImageCarrier(BitmapPixelSurface(cover)).encode(payload).toBitmap()

            val sturdyJpeg = encodeSturdyJpeg(embedded, quality = STURDY_JPEG_QUALITY)
            val sturdyDecoded = checkNotNull(BitmapFactory.decodeByteArray(sturdyJpeg, 0, sturdyJpeg.size))
            val sturdyResult = detector.analyze(sturdyDecoded)

            val cleanJpeg = encodeSturdyJpeg(cover, quality = STURDY_JPEG_QUALITY)
            val cleanDecoded = checkNotNull(BitmapFactory.decodeByteArray(cleanJpeg, 0, cleanJpeg.size))
            val cleanResult = detector.analyze(cleanDecoded)

            assertEquals(
                "expected $name's sturdy-embedded and clean JPEG q90 exports to agree on " +
                    "flagged/clear (sturdy=${sturdyResult.confidence}, clean=${cleanResult.confidence}) -- " +
                    "if they don't, the check may actually be reacting to the embed, not just this " +
                    "photo's own JPEG quantization pattern",
                cleanResult.flagged,
                sturdyResult.flagged,
            )
        }
    }

    @Test
    fun `at least one real photo is NOT flagged even in the pristine, never-exported sturdy state`() {
        // Demonstrates the unreliability isn't only a JPEG-export artifact -- real_photo_3 stays
        // under flagThreshold even on the raw embedded bitmap, unlike either bundled synthetic
        // cover (both flagged 100% pristine, gradient 0.997/mosaic 0.915).
        val cover = loadRealPhoto("real_photo_3.jpg")
        val payload = payloadOf("real_photo_3.jpg".hashCode())
        val embedded = SturdyImageCarrier(BitmapPixelSurface(cover)).encode(payload).toBitmap()

        val result = detector.analyze(embedded)

        assertFalse(
            "expected real_photo_3's pristine sturdy embed NOT to be flagged (measured " +
                "confidence=${result.confidence}) -- the bundled synthetic covers always flagged, real " +
                "photos don't reliably",
            result.flagged,
        )
    }

    @Test
    fun `after a Facebook-like or MMS-like recompression pass, neither sturdy nor clean is flagged`() {
        for (name in realPhotos) {
            val cover = loadRealPhoto(name)
            val payload = payloadOf(name.hashCode())
            val embeddedSurface = SturdyImageCarrier(BitmapPixelSurface(cover)).encode(payload)
            val cleanSurface = BitmapPixelSurface(cover)

            val sturdyFb = detector.analyze(simulateFacebookLikeChannel(embeddedSurface).toBitmap())
            val cleanFb = detector.analyze(simulateFacebookLikeChannel(cleanSurface).toBitmap())
            val sturdyMms = detector.analyze(simulateMmsLikeChannel(embeddedSurface).toBitmap())
            val cleanMms = detector.analyze(simulateMmsLikeChannel(cleanSurface).toBitmap())

            assertFalse("$name sturdy after Facebook-like was flagged (${sturdyFb.confidence})", sturdyFb.flagged)
            assertFalse("$name clean after Facebook-like was flagged (${cleanFb.confidence})", cleanFb.flagged)
            assertFalse("$name sturdy after MMS-like was flagged (${sturdyMms.confidence})", sturdyMms.flagged)
            assertFalse("$name clean after MMS-like was flagged (${cleanMms.confidence})", cleanMms.flagged)
        }
    }
}
