package dev.herakles.nightjar

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Android adapter for [SturdyImageCarrier] (task W1-1). The carrier itself is pure Kotlin (no
 * `android.*`, per architecture.md's porting note), so this small file supplies everything the
 * send UI needs to drive it against a real [Bitmap]: a [SturdyImageCarrier.PixelSurface] view
 * over a `Bitmap`, the reverse conversion, the send-side cover-prep step (downscale/refuse,
 * architecture.md's ~640px robustness floor), and the JPEG export step (the survival matrix is
 * measured against JPEG, 4:2:0).
 */

/**
 * [SturdyImageCarrier.PixelSurface] view directly over [bitmap] -- no copy. [SturdyImageCarrier
 * .encode] never mutates the surface passed to its constructor (it works on an internal
 * [SturdyImageCarrier.ArrayPixelSurface] copy instead), so wrapping an immutable decoded [Bitmap]
 * this way is always safe for that direction; [setPixel] is only ever exercised by callers that
 * hand this wrapper a mutable `Bitmap` (e.g. one built by [SturdyImageCarrier.PixelSurface
 * .toBitmap] below).
 */
class BitmapPixelSurface(private val bitmap: Bitmap) : SturdyImageCarrier.PixelSurface {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    /**
     * Caches the most recently read row so [SturdyImageCarrier.cellMeans]'s row-major full-image
     * scan -- its access pattern for every call site, encode and decode alike -- costs one native
     * [Bitmap.getPixels] call per ROW instead of one [Bitmap.getPixel] JNI call per PIXEL (gate-41
     * safety re-audit, finding F-5): up to [Bitmap.width] x [Bitmap.height] calls otherwise, tens
     * of millions at this app's own 24 MP incoming-file cap, for a bitmap [SturdyImageFireflyDecoder]
     * hands this class straight from unauthenticated external input on the receive path.
     *
     * Correct under any access order, not just row-major: a cache miss just re-fetches the right
     * row before answering, it never returns stale or wrong data -- this only changes how many
     * native calls a row-major caller costs, never what any caller reads.
     */
    private var cachedRow = -1
    private val rowBuffer = IntArray(bitmap.width)

    override fun getPixel(x: Int, y: Int): Int {
        if (y != cachedRow) {
            bitmap.getPixels(rowBuffer, 0, width, 0, y, width, 1)
            cachedRow = y
        }
        return rowBuffer[x]
    }

    override fun setPixel(x: Int, y: Int, argb: Int) {
        bitmap.setPixel(x, y, argb)
        if (y == cachedRow) rowBuffer[x] = argb
    }
}

/**
 * Materializes any [SturdyImageCarrier.PixelSurface] (typically [SturdyImageCarrier.encode]'s
 * in-memory [SturdyImageCarrier.ArrayPixelSurface] result) as a fresh mutable [Bitmap], so its
 * bytes can be JPEG-encoded via [encodeSturdyJpeg].
 */
fun SturdyImageCarrier.PixelSurface.toBitmap(): Bitmap {
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    for (y in 0 until height) {
        for (x in 0 until width) {
            out.setPixel(x, y, getPixel(x, y))
        }
    }
    return out
}

/** Why [prepareSturdyCover] refused a cover. */
enum class SturdyCoverRejection {
    /** Long side under [STURDY_MIN_COVER_LONG_SIDE_PX] -- architecture.md's known risk: "Small
     *  images: survival falls below ~512-640 px long side." */
    TOO_SMALL,
}

/** Outcome of [prepareSturdyCover]. */
sealed interface SturdyCoverPrep {
    /** [bitmap] is safe to embed into -- either the original or a downscaled copy. */
    data class Ready(val bitmap: Bitmap) : SturdyCoverPrep

    /** [detail] is user-facing: why this cover was refused. */
    data class Rejected(val reason: SturdyCoverRejection, val detail: String) : SturdyCoverPrep
}

/**
 * Send-side cover prep (task W1-1): downscales [bitmap] so its long side is at most
 * [STURDY_MAX_COVER_LONG_SIDE_PX] px (bilinear filtering -- the offline harness measured
 * bilinear and bicubic resamplers surviving identically across the round-2 matrix, so there is no
 * fidelity reason to prefer the pricier bicubic path here), and refuses covers whose *original*
 * long side starts under [STURDY_MIN_COVER_LONG_SIDE_PX] rather than silently embedding into (and
 * likely failing to survive on) a cover the design was never measured against. A typical phone
 * photo is far above both bounds.
 */
fun prepareSturdyCover(bitmap: Bitmap): SturdyCoverPrep {
    val longSide = maxOf(bitmap.width, bitmap.height)
    if (longSide < STURDY_MIN_COVER_LONG_SIDE_PX) {
        return SturdyCoverPrep.Rejected(
            SturdyCoverRejection.TOO_SMALL,
            "this photo is ${bitmap.width}x${bitmap.height} -- sturdy needs at least " +
                "${STURDY_MIN_COVER_LONG_SIDE_PX}px on its long side to survive recompression " +
                "(architecture.md 'Sturdy image technique (v6)')",
        )
    }
    if (longSide <= STURDY_MAX_COVER_LONG_SIDE_PX) return SturdyCoverPrep.Ready(bitmap)
    val scale = STURDY_MAX_COVER_LONG_SIDE_PX.toDouble() / longSide
    val w = maxOf(1, Math.round(bitmap.width * scale).toInt())
    val h = maxOf(1, Math.round(bitmap.height * scale).toInt())
    return SturdyCoverPrep.Ready(Bitmap.createScaledBitmap(bitmap, w, h, /* filter = */ true))
}

/**
 * JPEG-encodes [bitmap] (typically the already-embedded stego bitmap from
 * [SturdyImageCarrier.encode]`.toBitmap()`) at [quality] -- default [STURDY_JPEG_QUALITY].
 */
fun encodeSturdyJpeg(bitmap: Bitmap, quality: Int = STURDY_JPEG_QUALITY): ByteArray {
    val out = ByteArrayOutputStream()
    check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "sturdy JPEG compress failed" }
    return out.toByteArray()
}

/** architecture.md's known risk: survival falls below ~512-640 px long side. */
const val STURDY_MIN_COVER_LONG_SIDE_PX = 640

/** Send-side cap. The offline harness measured survival at long sides 1600 (orig-scale JPEG
 *  passes were run un-downscaled up to this app's own resize step), 1080 and 640; sending above
 *  1600 only grows upload size and every messaging app's own re-encode pass without adding
 *  measured margin. */
const val STURDY_MAX_COVER_LONG_SIDE_PX = 1600

/**
 * JPEG quality for the sturdy send path. The survival matrix's q50-95 range (architecture.md) is
 * what a *receiving* service's own re-compression does to whatever we upload -- not a target for
 * us to send at, since sending already-lossy imagery only stacks our own generation loss on top
 * of that unavoidable step. 90 keeps the outgoing cover close to source fidelity (so the
 * receiving side's own recompression starts from a good copy) while still cutting real bytes
 * versus a near-lossless PNG/q95+ export against MMS's tight attachment ceilings -- the offline
 * harness's file-size pass (gate-28 re-run, 24 real Kodak photos at their native <=768px long
 * side, so a genuine 1600px-long-side photo would weigh more than these absolute numbers) measured
 * mean JPEG bytes of 91KB/103KB/**114KB**/126KB/165KB at q85/88/90/92/95 -- q92 costs ~11% more
 * than q90 for no additional measured survival or visibility gain, so 90 is the better trade
 * inside the task's suggested 90-92 range.
 */
const val STURDY_JPEG_QUALITY = 90
