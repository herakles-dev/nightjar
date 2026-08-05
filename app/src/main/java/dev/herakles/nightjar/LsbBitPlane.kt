package dev.herakles.nightjar

import android.graphics.Bitmap

/**
 * Stage D/2 (gate-19). The IMAGE carrier's "where it hid" visualization: renders the
 * least-significant-bit plane of a stego image so embedded regions read as structure and
 * untouched regions read as noise — the eyeball test `covert-data/library/02_image_
 * steganography.md`'s LSB section describes, and the honest counterpart to what the AUDIO
 * carrier's spectrogram (Stage D/3) can show for its own techniques.
 *
 * [ImageStegoCarrier] embeds one payload bit into each of R, G, B's own LSB in turn, cycling
 * channel-by-channel across the image, and never touches alpha (see that class's KDoc,
 * `BITS_PER_PIXEL = 3`). A visualization that only read one channel's bit would miss two-thirds
 * of what's actually carrying data, and picking just one of the three would be arbitrary. This
 * combines all three channels' LSBs with XOR rather than OR/AND for one concrete, tested reason:
 * XOR is the only one of the three where flipping *any single* channel's LSB is guaranteed to
 * flip the output pixel, regardless of what the other two channels' LSBs happen to be. OR can
 * already be pinned at 1 by another channel; AND can already be pinned at 0. That guarantee is
 * what makes the output a faithful "did a bit move here" map, no matter which of the three
 * channels the embedder actually touched for a given payload bit.
 *
 * Pure `IntArray`-in/`IntArray`-out so the transform is verifiable in a plain JVM test
 * ([LsbBitPlaneTest], no `Bitmap`/Robolectric needed) — the same "keep the math separate from
 * any Android surface" split [WavFile] already uses in this package. [ofBitmap] below is the
 * thin adapter `JarDetailScreen.kt`'s `FireflyCarrierBlock` actually calls.
 */
object LsbBitPlane {

    /** Fully opaque black — ARGB_8888, alpha 0xFF. */
    private const val BLACK: Int = 0xFF000000.toInt()

    /** Fully opaque white — ARGB_8888, alpha 0xFF. */
    private const val WHITE: Int = 0xFFFFFFFF.toInt()

    /**
     * Transforms [pixels] — `width * height` ARGB_8888 ints in row-major order, the exact layout
     * [Bitmap.getPixels]/[Bitmap.createBitmap] use — into the LSB bit-plane: [WHITE] where the
     * R/G/B channels' bit-0 values XOR to 1, [BLACK] where they XOR to 0. Output is always fully
     * opaque; the input's own alpha is never read, matching [ImageStegoCarrier]'s "alpha is never
     * touched" embedding rule — alpha never carries a payload bit, so it has nothing to show
     * here. Position ([width]/[height]) plays no role beyond the size check: each output pixel
     * depends only on its own input pixel, so this is naturally a single linear pass, no
     * neighbor/row awareness needed.
     */
    fun compute(pixels: IntArray, width: Int, height: Int): IntArray {
        require(pixels.size == width * height) {
            "pixels.size (${pixels.size}) does not match width*height ($width*$height)"
        }
        return IntArray(pixels.size) { i ->
            val pixel = pixels[i]
            val rBit = (pixel ushr 16) and 1
            val gBit = (pixel ushr 8) and 1
            val bBit = pixel and 1
            if ((rBit xor gBit xor bBit) == 1) WHITE else BLACK
        }
    }

    /**
     * Thin [Bitmap] adapter around [compute] for the UI: reads [source]'s pixels via
     * `getPixels`, runs the pure transform, and rebuilds a same-size `ARGB_8888` bitmap via
     * `createBitmap`. [source] itself is never mutated or recycled.
     */
    fun ofBitmap(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val bitPlane = compute(pixels, width, height)
        return Bitmap.createBitmap(bitPlane, width, height, Bitmap.Config.ARGB_8888)
    }
}
