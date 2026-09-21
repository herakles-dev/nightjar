package dev.herakles.nightjar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Stage D/2 (gate-19). Plain JVM verification of [LsbBitPlane.compute] — no Bitmap/Robolectric
 * dependency, since the transform itself is pure `IntArray` arithmetic (see that object's KDoc).
 * [LsbBitPlane.ofBitmap], the thin `Bitmap` adapter `JarDetailScreen.kt` actually calls, has no
 * logic of its own beyond `getPixels`/`createBitmap` plumbing around [compute] and is left
 * uncovered here on purpose — the same "don't pull Robolectric in for the one line that doesn't
 * need it" call the pure-math split exists to enable.
 */
class LsbBitPlaneTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    // ---------------------------------------------------------------------
    // Known pixel input -> expected black/white output (all 8 R/G/B LSB combos)
    // ---------------------------------------------------------------------

    @Test
    fun `each of the eight R,G,B LSB combinations maps to the correct parity color`() {
        // (rBit, gBit, bBit) -> expected output. Every other bit in each channel is
        // deliberately nonzero/messy so only the LSB can be driving the result, matching what a
        // real stego image's channel values actually look like (arbitrary except for the one
        // embedded bit).
        data class Case(val rBit: Int, val gBit: Int, val bBit: Int, val expected: Int)
        val cases = listOf(
            Case(0, 0, 0, black), // parity 0
            Case(1, 0, 0, white), // parity 1
            Case(0, 1, 0, white),
            Case(0, 0, 1, white),
            Case(1, 1, 0, black), // parity 0
            Case(1, 0, 1, black),
            Case(0, 1, 1, black),
            Case(1, 1, 1, white), // parity 1
        )
        val pixels = IntArray(cases.size) { i ->
            val c = cases[i]
            argb(a = 0xFF, r = 0xF0 or c.rBit, g = 0x0E or c.gBit, b = 0x50 or c.bBit)
        }
        val expected = IntArray(cases.size) { i -> cases[i].expected }

        val actual = LsbBitPlane.compute(pixels, width = cases.size, height = 1)

        assertArrayEquals(expected, actual)
    }

    // ---------------------------------------------------------------------
    // The required guarantee: flipping bit 0 of exactly one channel always flips the output
    // pixel, no matter what the other two channels' LSBs are. This is the property that makes
    // XOR (not OR/AND) the right combination -- see LsbBitPlane's KDoc.
    // ---------------------------------------------------------------------

    @Test
    fun `flipping only bit 0 of one channel always flips the output pixel, regardless of the other two`() {
        for (rBit in 0..1) {
            for (gBit in 0..1) {
                for (bBit in 0..1) {
                    val base = argb(a = 0xFF, r = 0x80 or rBit, g = 0x42 or gBit, b = 0x10 or bBit)
                    val flippedR = base xor (1 shl 16) // toggle R's LSB only
                    val flippedG = base xor (1 shl 8) // toggle G's LSB only
                    val flippedB = base xor 1 // toggle B's LSB only

                    val outputs = LsbBitPlane.compute(
                        pixels = intArrayOf(base, flippedR, flippedG, flippedB),
                        width = 4,
                        height = 1,
                    )
                    val baseOutput = outputs[0]
                    val case = "rgb LSBs=$rBit$gBit$bBit"

                    assertNotEquals("flipping R's LSB alone must flip the output ($case)", baseOutput, outputs[1])
                    assertNotEquals("flipping G's LSB alone must flip the output ($case)", baseOutput, outputs[2])
                    assertNotEquals("flipping B's LSB alone must flip the output ($case)", baseOutput, outputs[3])
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Output is always fully opaque, independent of the input's own alpha -- alpha never
    // carries a payload bit (ImageStegoCarrier never touches it), so it must never affect this.
    // ---------------------------------------------------------------------

    @Test
    fun `output is always fully opaque regardless of input alpha`() {
        val transparentPixel = argb(a = 0x00, r = 0x81, g = 0x43, b = 0x10) // parity 0 -> black
        val opaquePixel = argb(a = 0xFF, r = 0x81, g = 0x43, b = 0x10) // same RGB, parity 0 -> black

        val actual = LsbBitPlane.compute(intArrayOf(transparentPixel, opaquePixel), width = 2, height = 1)

        assertArrayEquals(intArrayOf(black, black), actual)
    }

    // ---------------------------------------------------------------------
    // Guard rail: mismatched width*height against the actual pixel count is a caller bug, not
    // silently-tolerated bad input -- same "require, don't guess" discipline
    // ImageStegoCarrier.encode uses for an oversized payload.
    // ---------------------------------------------------------------------

    @Test
    fun `mismatched pixel count throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            LsbBitPlane.compute(IntArray(3), width = 2, height = 2)
        }
    }
}
