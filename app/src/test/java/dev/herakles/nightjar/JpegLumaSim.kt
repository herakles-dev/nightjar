package dev.herakles.nightjar

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure-Kotlin JPEG *luma*-loss simulator for [SturdyImageCarrier]'s unit tests.
 *
 * `javax.imageio`/`java.awt` are not on this Android Gradle module's Kotlin unit-test compile
 * classpath (AGP restricts it to Android-compatible JDK APIs even though `testDebugUnitTest` runs
 * on a plain desktop JVM -- confirmed by `Unresolved reference` at compile time, same finding
 * `IncomingRouterImageTest.jpegBytes`'s KDoc already documents for this codebase). This
 * reimplements just the two loss mechanisms [SturdyImageCarrier] actually depends on surviving:
 *
 *  1. **8x8-block DCT -> quantize -> dequantize -> IDCT -> clamp**, using the standard IJG
 *     baseline luminance quantization table scaled by `quality` per the classic libjpeg formula
 *     (the same one every mainstream JPEG encoder -- including Android's and Facebook/Messenger's
 *     -- derives its own scaled tables from).
 *  2. **Area-average and bilinear downscale**, matching a resizing step independent of JPEG
 *     compression (the harness's `resizeLong` equivalent).
 *
 * Chroma (Cb/Cr) is never modeled: [SturdyImageCarrier] only ever reads luminance
 * (`Y = 0.299R + 0.587G + 0.114B`), so a luma-only simulator exercises the actual failure mode the
 * codec depends on surviving. A simulated image comes back as a de-facto grayscale rendering
 * (`R=G=B=round(Y)`) via [LumaSurface], not a color-accurate JPEG re-encode -- this utility is a
 * fidelity-of-*luma-degradation* tool for these tests, not a general-purpose JPEG codec.
 */
object JpegLumaSim {

    /** IJG baseline luminance quantization table at quality 50, row-major (natural, not zig-zag). */
    private val LUMA_Q50 = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61,
        12, 12, 14, 19, 26, 58, 60, 55,
        14, 13, 16, 24, 40, 57, 69, 56,
        14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77,
        24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101,
        72, 92, 95, 98, 112, 100, 103, 99,
    )

    /** `cos[(2x+1)*u*PI/16]` basis table, `x,u` in `0..7`. */
    private val BASIS = Array(8) { x -> DoubleArray(8) { u -> cos((2 * x + 1) * u * PI / 16.0) } }

    /** JPEG DCT-II normalization constants: `C(0) = 1/sqrt(2)`, `C(k) = 1` for `k > 0`. */
    private val C = doubleArrayOf(1.0 / sqrt(2.0), 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)

    /** Scales [LUMA_Q50] to `quality` (1..100) per the classic IJG formula, clamped to `[1,255]`. */
    fun scaledLumaTable(quality: Int): IntArray {
        val q = quality.coerceIn(1, 100)
        val scale = if (q < 50) 5000 / q else 200 - q * 2
        return IntArray(64) { i -> ((LUMA_Q50[i] * scale + 50) / 100).coerceIn(1, 255) }
    }

    /** Forward 8x8 DCT-II (JPEG normalization), separable (row pass then column pass). [block] is
     *  row-major, 64 samples, already level-shifted by the caller (JPEG subtracts 128). */
    private fun fdct8x8(block: DoubleArray): DoubleArray {
        val tmp = DoubleArray(64)
        for (u in 0 until 8) {
            for (y in 0 until 8) {
                var sum = 0.0
                for (x in 0 until 8) sum += block[y * 8 + x] * BASIS[x][u]
                tmp[y * 8 + u] = sum * (C[u] / 2.0)
            }
        }
        val out = DoubleArray(64)
        for (u in 0 until 8) {
            for (v in 0 until 8) {
                var sum = 0.0
                for (y in 0 until 8) sum += tmp[y * 8 + u] * BASIS[y][v]
                out[v * 8 + u] = sum * (C[v] / 2.0)
            }
        }
        return out
    }

    /** Inverse of [fdct8x8]. [coef] is row-major `F(v*8+u)`; output is level-shifted back by the
     *  caller. */
    private fun idct8x8(coef: DoubleArray): DoubleArray {
        val tmp = DoubleArray(64)
        for (x in 0 until 8) {
            for (v in 0 until 8) {
                var sum = 0.0
                for (u in 0 until 8) sum += (C[u] / 2.0) * coef[v * 8 + u] * BASIS[x][u]
                tmp[v * 8 + x] = sum
            }
        }
        val out = DoubleArray(64)
        for (x in 0 until 8) {
            for (y in 0 until 8) {
                var sum = 0.0
                for (v in 0 until 8) sum += (C[v] / 2.0) * tmp[v * 8 + x] * BASIS[y][v]
                out[y * 8 + x] = sum
            }
        }
        return out
    }

    /**
     * Runs a `width`x`height` luma plane through 8x8 DCT -> quantize(`quality`) -> dequantize ->
     * IDCT -> clamp, block by block. Pads to a multiple of 8 by edge-replication (matches real
     * JPEG's own block padding at image edges) and crops back to the original size afterward.
     */
    fun degrade(luma: Array<DoubleArray>, width: Int, height: Int, quality: Int): Array<DoubleArray> {
        if (width <= 0 || height <= 0) return luma
        val qtab = scaledLumaTable(quality)
        val pw = ((width + 7) / 8) * 8
        val ph = ((height + 7) / 8) * 8
        val padded = Array(ph) { y -> DoubleArray(pw) { x -> luma[y.coerceAtMost(height - 1)][x.coerceAtMost(width - 1)] } }
        val out = Array(ph) { DoubleArray(pw) }
        val block = DoubleArray(64)
        var by = 0
        while (by < ph) {
            var bx = 0
            while (bx < pw) {
                for (y in 0 until 8) for (x in 0 until 8) block[y * 8 + x] = padded[by + y][bx + x] - 128.0
                val coef = fdct8x8(block)
                for (i in 0 until 64) coef[i] = (coef[i] / qtab[i]).roundToInt() * qtab[i].toDouble()
                val rec = idct8x8(coef)
                for (y in 0 until 8) for (x in 0 until 8) out[by + y][bx + x] = (rec[y * 8 + x] + 128.0).coerceIn(0.0, 255.0)
                bx += 8
            }
            by += 8
        }
        return Array(height) { y -> DoubleArray(width) { x -> out[y][x] } }
    }

    /** Area-average downscale to `newW`x`newH` (matches a resizer's "area"/"box" filter). */
    fun downscaleArea(luma: Array<DoubleArray>, width: Int, height: Int, newW: Int, newH: Int): Array<DoubleArray> {
        if (newW >= width && newH >= height) return luma
        val out = Array(newH) { DoubleArray(newW) }
        for (ny in 0 until newH) {
            val y0 = ny * height / newH
            val y1 = maxOf(y0 + 1, (ny + 1) * height / newH).coerceAtMost(height)
            for (nx in 0 until newW) {
                val x0 = nx * width / newW
                val x1 = maxOf(x0 + 1, (nx + 1) * width / newW).coerceAtMost(width)
                var sum = 0.0
                var n = 0
                for (y in y0 until y1) for (x in x0 until x1) { sum += luma[y][x]; n++ }
                out[ny][nx] = if (n > 0) sum / n else 0.0
            }
        }
        return out
    }

    /** Bilinear downscale (alternate resampler -- matches harness.kt's bilinear-vs-bicubic split;
     *  bilinear stands in for both, per that harness's finding they survive identically). */
    fun downscaleBilinear(luma: Array<DoubleArray>, width: Int, height: Int, newW: Int, newH: Int): Array<DoubleArray> {
        if (newW >= width && newH >= height) return luma
        val out = Array(newH) { DoubleArray(newW) }
        val sx = width.toDouble() / newW
        val sy = height.toDouble() / newH
        for (ny in 0 until newH) {
            val fy = ((ny + 0.5) * sy - 0.5).coerceIn(0.0, (height - 1).toDouble())
            val y0 = fy.toInt().coerceIn(0, height - 1)
            val y1 = (y0 + 1).coerceAtMost(height - 1)
            val wy = fy - y0
            for (nx in 0 until newW) {
                val fx = ((nx + 0.5) * sx - 0.5).coerceIn(0.0, (width - 1).toDouble())
                val x0 = fx.toInt().coerceIn(0, width - 1)
                val x1 = (x0 + 1).coerceAtMost(width - 1)
                val wx = fx - x0
                val top = luma[y0][x0] * (1 - wx) + luma[y0][x1] * wx
                val bot = luma[y1][x0] * (1 - wx) + luma[y1][x1] * wx
                out[ny][nx] = top * (1 - wy) + bot * wy
            }
        }
        return out
    }

    /** New (w,h) so the long side becomes [longSide] (no-op if already <= it). */
    fun longSideTarget(width: Int, height: Int, longSide: Int): Pair<Int, Int> {
        val long = maxOf(width, height)
        if (longSide <= 0 || long <= longSide) return width to height
        val scale = longSide.toDouble() / long
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }
}

/** Per-pixel luminance of [surface], matching [SturdyImageCarrier]'s own `Y` formula exactly. */
fun lumaPlaneOf(surface: SturdyImageCarrier.PixelSurface): Array<DoubleArray> =
    Array(surface.height) { y ->
        DoubleArray(surface.width) { x ->
            val p = surface.getPixel(x, y)
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            0.299 * r + 0.587 * g + 0.114 * b
        }
    }

/** Read-only [SturdyImageCarrier.PixelSurface] wrapping a luma plane as a grayscale image
 *  (`R=G=B=round(Y)`) -- the output type every [JpegLumaSim] pipeline in these tests produces. */
class LumaSurface(private val luma: Array<DoubleArray>) : SturdyImageCarrier.PixelSurface {
    override val height: Int = luma.size
    override val width: Int = if (luma.isNotEmpty()) luma[0].size else 0

    override fun getPixel(x: Int, y: Int): Int {
        val v = luma[y][x].roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    override fun setPixel(x: Int, y: Int, argb: Int) {
        throw UnsupportedOperationException("LumaSurface is a read-only channel-simulation output")
    }
}

/**
 * One simulated messaging-channel pass over [surface]: JPEG-degrades luma at [quality], then
 * (optionally) downscales so the long side becomes [longSide] -- order matches which the caller
 * wants (JPEG-then-resize or resize-then-JPEG), selected via [resizeFirst].
 */
fun simulateJpegChannel(
    surface: SturdyImageCarrier.PixelSurface,
    quality: Int,
    longSide: Int = 0,
    bilinear: Boolean = true,
    resizeFirst: Boolean = false,
): SturdyImageCarrier.PixelSurface {
    var luma = lumaPlaneOf(surface)
    var w = surface.width
    var h = surface.height

    fun resize() {
        val (nw, nh) = JpegLumaSim.longSideTarget(w, h, longSide)
        if (nw != w || nh != h) {
            luma = if (bilinear) JpegLumaSim.downscaleBilinear(luma, w, h, nw, nh) else JpegLumaSim.downscaleArea(luma, w, h, nw, nh)
            w = nw
            h = nh
        }
    }

    if (resizeFirst && longSide > 0) resize()
    luma = JpegLumaSim.degrade(luma, w, h, quality)
    if (!resizeFirst && longSide > 0) resize()

    return LumaSurface(luma)
}

/** Two JPEG passes chained -- for FB-like (2048->q85->1080->q75). */
fun simulateFacebookLikeChannel(surface: SturdyImageCarrier.PixelSurface): SturdyImageCarrier.PixelSurface {
    val first = simulateJpegChannel(surface, quality = 85, longSide = 2048, resizeFirst = true)
    return simulateJpegChannel(first, quality = 75, longSide = 1080, resizeFirst = true)
}

/** MMS-like (640px, q50) -- a single downscale-then-recompress pass. */
fun simulateMmsLikeChannel(surface: SturdyImageCarrier.PixelSurface): SturdyImageCarrier.PixelSurface =
    simulateJpegChannel(surface, quality = 50, longSide = 640, resizeFirst = true)
