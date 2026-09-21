package dev.herakles.nightjar.modules.fireflyjar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import dev.herakles.nightjar.LsbBitPlane
import dev.herakles.nightjar.modules.imagestego.SampleCover
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * U-02. Grounds [imageBitPlaneCaption]'s claim -- an untouched photo's bit-plane already looks
 * like static, not a picture -- in a real measurement against the app's own two bundled
 * [SampleCover] images (`stego_cover_gradient.png`/`stego_cover_mosaic.png`), the exact
 * resources [dev.herakles.nightjar.ImageStegoCarrier] embeds into and
 * [dev.herakles.nightjar.modules.imagestego.ImageStegoScreen] offers as a cover. Same
 * Robolectric setup [dev.herakles.nightjar.ImageStegoCarrierTest] already uses to get a real,
 * pixel-accurate `Bitmap` (`BitmapFactory.decodeResource`) rather than the "not mocked" stub
 * `android.jar` gives a plain JVM unit test -- `LsbBitPlaneTest` stays Robolectric-free on
 * purpose (its own KDoc), so this grounding lives here instead of there.
 *
 * "Looks like static, not a picture" is operationalized as: no run of same-color pixels in
 * [LsbBitPlane.compute]'s output is longer than [MAX_ALLOWED_RUN] in any row or column. A smooth
 * gradient or a legible shape surviving into the bit-plane would show up as long runs; a
 * genuinely noise-like plane doesn't. Measured directly on both bundled covers before picking
 * the bound: the longest run either one actually produces is 6 (of 100) pixels, so
 * [MAX_ALLOWED_RUN] leaves better than 3x headroom rather than being tuned to just clear the
 * measurement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageBitPlaneCaptionTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun loadCover(resId: Int): Bitmap =
        checkNotNull(BitmapFactory.decodeResource(context.resources, resId)) {
            "failed to decode sample cover image resource $resId"
        }

    private fun longestRun(values: IntArray): Int {
        var longest = 1
        var current = 1
        for (i in 1 until values.size) {
            if (values[i] == values[i - 1]) {
                current++
                if (current > longest) longest = current
            } else {
                current = 1
            }
        }
        return longest
    }

    /** Longest same-color run found in any single row, or any single column, of [bitPlane]. */
    private fun longestRowOrColumnRun(bitPlane: Bitmap): Int {
        val width = bitPlane.width
        val height = bitPlane.height
        val pixels = IntArray(width * height)
        bitPlane.getPixels(pixels, 0, width, 0, 0, width, height)

        var longest = 1
        for (y in 0 until height) {
            val row = IntArray(width) { x -> pixels[y * width + x] }
            longest = maxOf(longest, longestRun(row))
        }
        for (x in 0 until width) {
            val column = IntArray(height) { y -> pixels[y * width + x] }
            longest = maxOf(longest, longestRun(column))
        }
        return longest
    }

    @Test
    fun `the gradient cover's bit-plane has no long same-color run in any row or column`() {
        val bitPlane = LsbBitPlane.ofBitmap(loadCover(SampleCover.GRADIENT.resId))
        val run = longestRowOrColumnRun(bitPlane)
        assertTrue("longest run was $run, expected <= $MAX_ALLOWED_RUN", run <= MAX_ALLOWED_RUN)
    }

    @Test
    fun `the mosaic cover's bit-plane has no long same-color run in any row or column`() {
        val bitPlane = LsbBitPlane.ofBitmap(loadCover(SampleCover.MOSAIC.resId))
        val run = longestRowOrColumnRun(bitPlane)
        assertTrue("longest run was $run, expected <= $MAX_ALLOWED_RUN", run <= MAX_ALLOWED_RUN)
    }

    @Test
    fun `the caption never implies the eye can spot a bright or dark patch`() {
        val caption = imageBitPlaneCaption()
        // The honesty rule this caption exists to enforce (U-02's brief): it must not claim, or
        // even hint, that a message shows up as a distinguishable region -- a short deny-list of
        // phrases a "you can spot it here" version of this caption would need.
        val forbidden = listOf("bright", "dark patch", "you can see", "shows where", "points to", "reveals where")
        forbidden.forEach { phrase ->
            assertTrue("caption unexpectedly contains \"$phrase\": $caption", !caption.contains(phrase))
        }
    }

    private companion object {
        const val MAX_ALLOWED_RUN = 20
    }
}
