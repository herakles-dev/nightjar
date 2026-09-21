package dev.herakles.nightjar.modules.imagestego

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.herakles.nightjar.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verification for S-01 ([downsampleFactor]) and codec-M01 ([hasTransparency]/[flattenToOpaque])
 * — both `internal` top-level functions in `ImageStegoScreen.kt`, same test-visibility precedent
 * as [encodePngBytes] (`ImageStegoSaveRoundTripTest`).
 *
 * Uses Robolectric so `Bitmap` is a real, pixel-accurate implementation (`setPixel`/`getPixel`/
 * `getPixels`/`BitmapFactory.decodeResource`) — the same reasoning `ImageStegoCarrierTest`'s own
 * KDoc gives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageStegoScreenTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // --- S-01: downsampleFactor's contract is "post-scale long edge <= maxDimension, an
    // exactly-maxDimension edge stays at sample size 1" -- the off-by-one-power-of-two bug
    // compared the *already-halved* w/h against maxDimension instead of the current value, so
    // any cover up to ~2x maxDimension on its long edge skipped downsampling entirely. ---

    @Test
    fun downsampleFactorFor8000x6000IsTwo() {
        assertEquals(2, downsampleFactor(8000, 6000, 4096))
    }

    @Test
    fun downsampleFactorFor5712x4284IsTwo() {
        assertEquals(2, downsampleFactor(5712, 4284, 4096))
    }

    @Test
    fun downsampleFactorForExactly4096x3072IsOne() {
        // An edge exactly at maxDimension is already within budget -- must NOT downsample.
        assertEquals(1, downsampleFactor(4096, 3072, 4096))
    }

    @Test
    fun downsampleFactorFor9000x9000IsFour() {
        assertEquals(4, downsampleFactor(9000, 9000, 4096))
    }

    @Test
    fun downsampleFactorForASmallImageIsOne() {
        assertEquals(1, downsampleFactor(100, 100, 4096))
    }

    // --- codec-M01: premultiplied-alpha LSB fidelity. A transparent cover gets flattened onto
    // an opaque background at import time; an already-opaque cover (including both bundled
    // sample covers) is returned untouched. ---

    @Test
    fun hasTransparencyIsFalseForAFullyOpaqueBitmap() {
        val opaque = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        opaque.eraseColor(0xFF112233.toInt()) // alpha = 0xFF throughout

        assertFalse(hasTransparency(opaque))
    }

    @Test
    fun hasTransparencyIsTrueWhenAtLeastOnePixelIsNotFullyOpaque() {
        val mixed = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        mixed.eraseColor(0xFF112233.toInt())
        mixed.setPixel(2, 2, 0x80112233.toInt()) // alpha = 0x80, not fully opaque

        assertTrue(hasTransparency(mixed))
    }

    @Test
    fun flattenToOpaqueReturnsTheSameInstanceWhenAlreadyOpaque() {
        val opaque = Bitmap.createBitmap(6, 6, Bitmap.Config.ARGB_8888)
        opaque.eraseColor(0xFF445566.toInt())

        val result = flattenToOpaque(opaque)

        assertTrue("an already-opaque bitmap should be returned unchanged, by reference", result === opaque)
    }

    @Test
    fun flattenToOpaqueProducesAFullyOpaqueBitmapFromATransparentOne() {
        val transparent = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        // A mix of fully transparent, half transparent, and fully opaque pixels.
        transparent.eraseColor(0x00000000)
        for (x in 0 until 4) {
            for (y in 0 until 8) {
                transparent.setPixel(x, y, 0x80FF0000.toInt()) // half-transparent red
            }
        }
        for (y in 0 until 8) {
            transparent.setPixel(7, y, 0xFF00FF00.toInt()) // fully opaque green
        }
        assertTrue("test fixture should itself have transparency", hasTransparency(transparent))

        val flattened = flattenToOpaque(transparent)

        assertTrue("flattening a transparent bitmap must return a new instance", flattened !== transparent)
        assertEquals(transparent.width, flattened.width)
        assertEquals(transparent.height, flattened.height)
        val pixels = IntArray(flattened.width * flattened.height)
        flattened.getPixels(pixels, 0, flattened.width, 0, 0, flattened.width, flattened.height)
        for (pixel in pixels) {
            val alpha = (pixel ushr 24) and 0xFF
            assertEquals("every pixel of a flattened bitmap must be fully opaque", 0xFF, alpha)
        }
    }

    @Test
    fun bundledSampleCoversAreAlreadyOpaque() {
        // codec-M01 explicitly asks to verify this, not assume it -- the flatten step should
        // never actually run for either bundled sample cover.
        for (cover in SampleCover.entries) {
            val bitmap = checkNotNull(BitmapFactory.decodeResource(context.resources, cover.resId)) {
                "failed to decode sample cover resource for ${cover.name}"
            }
            assertFalse("${cover.name} should already be fully opaque", hasTransparency(bitmap))
        }
    }
}
