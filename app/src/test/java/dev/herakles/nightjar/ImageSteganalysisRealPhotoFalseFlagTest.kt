package dev.herakles.nightjar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Properly measures [ImageSteganalysis]'s false-flag rate on CLEAN, never-embedded real photos,
 * per container -- the question an earlier n=3 sample (`SturdyImageSteganalysisRealPhotoTest`)
 * first raised ("the same flagged/clear verdict lands on an unembedded copy of that photo... the
 * run this detector finds tracks that photo's own JPEG-quantization pattern") but was too small to
 * size honestly.
 *
 * ## The full measurement (n=26, offline this session)
 * 26 distinct real, public-domain photographs (Lorem Picsum, `id` 1..100), each at native
 * 800x600 (their own served resolution, no resize applied), run as (a) the lossless PNG decode
 * and (b) that same PNG re-encoded as JPEG q75/90/95 ([encodeSturdyJpeg]) -- never embedded into
 * at any stage:
 * ```
 * PNG (lossless)   5/26 flagged (19.2%)
 * JPEG q75         5/26 flagged (19.2%)
 * JPEG q90         6/26 flagged (23.1%)
 * JPEG q95         6/26 flagged (23.1%)
 * ```
 * **The honest, size-corrected finding.** An earlier pass of this measurement (this session, not
 * committed) used a much smaller downscale (220px long side, chosen only to fit a small resource
 * budget) and found a wildly higher, near-universal false-flag rate (22-23 of 26, ~85-88%,
 * *every* container including PNG) -- a sweep across intermediate sizes on the same 8 source
 * photos (320/400/480/640/800px long side) showed *why*: this detector's false-flag rate is
 * strongly, monotonically SIZE-sensitive (100% -> 87.5% -> 75% -> 50% -> 12.5% flagged as long
 * side grows 320 -> 800px), independent of JPEG at all -- a small image's coarser per-pixel
 * sampling smooths adjacent values together in a way the chi-square PoV test reads as
 * LSB-equalization, regardless of container. Measuring at a size that small would have reported a
 * real number for the wrong question (an artifact of the test's own downscale, not of what this
 * check actually does to a real photo at a size an operator would actually pick). This test uses
 * native, un-resized 800x600 photos instead, matching this app's own established "real photo"
 * scale ([dev.herakles.nightjar.SturdyImageSteganalysisRealPhotoTest]'s 900x700 real_photo_*.jpg,
 * and the 768x512/512x768 Kodak set) -- committed test resources stay small by using
 * FEWER, full-size photos rather than MORE, artificially shrunk ones.
 *
 * **The measured fact is more nuanced than "JPEG causes this."** PNG's own baseline false-flag
 * rate (19.2%) is already material (this test's own ">10%" bar) -- this chi-square/PoV check
 * mistakes a real photo's own natural low-frequency detail for hidden data reasonably often, with
 * no compression involved at all. JPEG re-encoding adds a real but modest increment on top of
 * that baseline (+1 photo of 26 flagged at q90/q95 versus PNG/q75 in this run -- id 92 in the full
 * set, `confidence` 0.776/0.744 clear at PNG/q75, 0.877/0.856 flagged at q90/q95, `flagThreshold`
 * = 0.85). `ImageStegoScreen.kt`'s honest caveat is worded from this fuller picture --
 * it does not claim JPEG alone is responsible, since the measured baseline says otherwise.
 *
 * ## This committed test (n=6, curated, not a random/representative sample)
 * A deliberately small, curated subset of the same 26 native photos: `clean_01.png`/`clean_03.png`
 * are the "boring" always-clear anchors, `clean_02.png`/`clean_04.png`/`clean_06.png` are always
 * flagged under every container, and `clean_05.png` (`id` 92 in the full set above) is the one
 * photo whose flagged/clear verdict actually changes between PNG/q75 (clear) and q90/q95
 * (flagged) -- kept small (~3MB total PNG) precisely so the committed regression cost stays low
 * while still covering all three measured behaviors. This is a corroborating regression check on
 * real, deterministic data (same "SturdyImageCarrierBundledCoverTest corroborates the offline
 * sturdy harness without literally reproducing it" precedent this codebase already follows) --
 * the authoritative, statistically meaningful rate is the n=26 table above, not this file's own
 * smaller counts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageSteganalysisRealPhotoFalseFlagTest {

    private val detector = ImageSteganalysis()

    private fun loadCleanPhoto(index: Int): Bitmap {
        val name = "clean_%02d.png".format(index)
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("clean_photos/$name")) {
            "missing test resource clean_photos/$name"
        }
        return stream.use { checkNotNull(BitmapFactory.decodeStream(it)) { "failed to decode $name" } }
    }

    private fun jpegRoundTrip(bitmap: Bitmap, quality: Int): Bitmap {
        val bytes = encodeSturdyJpeg(bitmap, quality)
        return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
            "failed to decode re-encoded q$quality JPEG bytes"
        }
    }

    @Test
    fun `false-flag rate per container on clean, never-embedded real photos`() {
        var pngFlagged = 0
        var q75Flagged = 0
        var q90Flagged = 0
        var q95Flagged = 0

        for (i in 1..PHOTO_COUNT) {
            val png = loadCleanPhoto(i)

            if (detector.analyze(png).flagged) pngFlagged++
            if (detector.analyze(jpegRoundTrip(png, 75)).flagged) q75Flagged++
            if (detector.analyze(jpegRoundTrip(png, 90)).flagged) q90Flagged++
            if (detector.analyze(jpegRoundTrip(png, 95)).flagged) q95Flagged++
        }

        // Measured truth on this committed 6-photo subset (see class KDoc for the full n=26
        // table/margins) -- exact counts, not a bound, since this whole pipeline is deterministic
        // (no RNG anywhere: committed PNG bytes in, real Bitmap.compress JPEG encoder, same
        // detector every run).
        assertEquals("PNG (lossless) false-flag count", 3, pngFlagged)
        assertEquals("JPEG q75 false-flag count", 3, q75Flagged)
        assertEquals("JPEG q90 false-flag count", 4, q90Flagged)
        assertEquals("JPEG q95 false-flag count", 4, q95Flagged)
    }

    private companion object {
        const val PHOTO_COUNT = 6
    }
}
