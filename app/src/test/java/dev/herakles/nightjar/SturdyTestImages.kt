package dev.herakles.nightjar

import kotlin.random.Random

/**
 * Synthetic cover generators for [SturdyImageCarrier]'s unit tests -- gradients,
 * noise and simple textures at whatever size a test needs, with no bundled test-resource bytes.
 * Every generator produces a [SturdyImageCarrier.ArrayPixelSurface] with full RGB variation (not
 * pre-flattened to grayscale), matching what a real photo cover looks like before any luma-only
 * channel simulation is applied to a *stego* copy of it.
 */
object SturdyTestImages {

    private fun surface(width: Int, height: Int, argbAt: (x: Int, y: Int) -> Int): SturdyImageCarrier.ArrayPixelSurface {
        val out = SturdyImageCarrier.ArrayPixelSurface(width, height)
        for (y in 0 until height) for (x in 0 until width) out.setPixel(x, y, argbAt(x, y))
        return out
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Diagonal linear gradient across all three channels (smooth, low-frequency cover). */
    fun gradient(width: Int, height: Int): SturdyImageCarrier.ArrayPixelSurface =
        surface(width, height) { x, y ->
            val r = (255.0 * x / width).toInt()
            val g = (255.0 * y / height).toInt()
            val b = (255.0 * (x + y) / (width + height)).toInt()
            rgb(r, g, b)
        }

    /** Uniform random per-pixel noise, full color range (worst case for low-frequency QIM, and
     *  the classic false-catch stress case). */
    fun noise(width: Int, height: Int, seed: Int): SturdyImageCarrier.ArrayPixelSurface {
        val rnd = Random(seed)
        return surface(width, height) { _, _ -> rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }
    }

    /** Checkerboard-ish tiled texture with a repeating structure (stresses the chi-square
     *  false-catch case the way `ImageSteganalysis`'s bundled mosaic cover does). */
    fun texture(width: Int, height: Int, tile: Int = 9, seed: Int = 1): SturdyImageCarrier.ArrayPixelSurface {
        val rnd = Random(seed)
        val palette = IntArray(16) { rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }
        return surface(width, height) { x, y ->
            val idx = ((x / tile) + (y / tile)) % palette.size
            palette[idx]
        }
    }

    /** A smooth radial gradient (photo-like: a bright, low-contrast center falling off to the
     *  edges -- distinct low-frequency shape from [gradient]'s linear ramp). */
    fun radial(width: Int, height: Int): SturdyImageCarrier.ArrayPixelSurface {
        val cx = width / 2.0
        val cy = height / 2.0
        val maxD = Math.sqrt(cx * cx + cy * cy)
        return surface(width, height) { x, y ->
            val d = Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) / maxD
            val v = (255 * (1.0 - d)).toInt()
            rgb(v, (v * 0.9).toInt(), (v * 0.7).toInt())
        }
    }

    /** A mostly-dark cover (deep-shadow stress case -- the "deep-shadow mottling"
     *  known risk lives here). */
    fun darkFlat(width: Int, height: Int, base: Int = 10, seed: Int = 2): SturdyImageCarrier.ArrayPixelSurface {
        val rnd = Random(seed)
        return surface(width, height) { _, _ ->
            val jitter = rnd.nextInt(5)
            rgb(base + jitter, base + jitter, base + jitter)
        }
    }

    // --- Pixel-surface transforms used by the failure-envelope tests ---

    /** Crops [fraction] off each edge (e.g. 0.05 => a 10% total crop). */
    fun crop(src: SturdyImageCarrier.PixelSurface, fraction: Double): SturdyImageCarrier.ArrayPixelSurface {
        val dx = (src.width * fraction).toInt()
        val dy = (src.height * fraction).toInt()
        val w = src.width - 2 * dx
        val h = src.height - 2 * dy
        return surface(w, h) { x, y -> src.getPixel(x + dx, y + dy) }
    }

    /** Rotates 90 degrees clockwise. */
    fun rotate90(src: SturdyImageCarrier.PixelSurface): SturdyImageCarrier.ArrayPixelSurface =
        surface(src.height, src.width) { x, y -> src.getPixel(y, src.height - 1 - x) }

    /** Horizontal flip. */
    fun flipHorizontal(src: SturdyImageCarrier.PixelSurface): SturdyImageCarrier.ArrayPixelSurface =
        surface(src.width, src.height) { x, y -> src.getPixel(src.width - 1 - x, y) }

    /** A random sub-crop, [wFrac]/[hFrac] of the original width/height, at a random offset. */
    fun randomCrop(src: SturdyImageCarrier.PixelSurface, rnd: Random, wFrac: Double, hFrac: Double): SturdyImageCarrier.ArrayPixelSurface {
        val w = (src.width * wFrac).toInt().coerceIn(1, src.width)
        val h = (src.height * hFrac).toInt().coerceIn(1, src.height)
        val x0 = if (src.width > w) rnd.nextInt(src.width - w) else 0
        val y0 = if (src.height > h) rnd.nextInt(src.height - h) else 0
        return surface(w, h) { x, y -> src.getPixel(x0 + x, y0 + y) }
    }
}
