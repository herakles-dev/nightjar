package dev.herakles.nightjar

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 Cooley-Tukey FFT -- the same standard algorithm as
 * `AcousticDetector.fft`/`AcousticCarrier.fft`, but neither of those is reusable here: both
 * are declared inside a `private companion object` (`AcousticDetector.fft`) or as a private
 * member of one (`AcousticCarrier.fft`), so neither is visible outside its own file, and
 * de-duplicating them into a shared utility is explicitly out of scope for this task. This
 * is therefore a third, independent copy. `re.size` MUST be a power of two -- true for
 * [FRAME_SIZE].
 */
internal fun fft(re: DoubleArray, im: DoubleArray) {
    val n = re.size
    require(n and (n - 1) == 0) { "FFT size must be a power of two, was $n" }

    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j or bit
        if (i < j) {
            val tr = re[i]; re[i] = re[j]; re[j] = tr
            val ti = im[i]; im[i] = im[j]; im[j] = ti
        }
    }

    var len = 2
    while (len <= n) {
        val ang = -2.0 * PI / len
        val wRe = cos(ang)
        val wIm = sin(ang)
        var i = 0
        while (i < n) {
            var curRe = 1.0
            var curIm = 0.0
            val half = len / 2
            for (k in 0 until half) {
                val evenIdx = i + k
                val oddIdx = evenIdx + half
                val uRe = re[evenIdx]
                val uIm = im[evenIdx]
                val vRe = re[oddIdx] * curRe - im[oddIdx] * curIm
                val vIm = re[oddIdx] * curIm + im[oddIdx] * curRe
                re[evenIdx] = uRe + vRe
                im[evenIdx] = uIm + vIm
                re[oddIdx] = uRe - vRe
                im[oddIdx] = uIm - vIm
                val nextCurRe = curRe * wRe - curIm * wIm
                val nextCurIm = curRe * wIm + curIm * wRe
                curRe = nextCurRe
                curIm = nextCurIm
            }
            i += len
        }
        len = len shl 1
    }
}

/**
 * Inverse FFT via the standard conjugate trick: negate `im`, run the forward [fft], negate
 * the result's `im` again, then divide both `re` and `im` by `n`. There is no separate
 * inverse implementation to get wrong independently of [fft] -- and the `n` normalization
 * (not `sqrt(n)`, and applied to both `re` and `im`) is exactly what makes `ifft(fft(x))`
 * return `x` (up to floating-point rounding), which the class KDoc's "untouched frames
 * round-trip exactly" claim depends on.
 */
internal fun ifft(re: DoubleArray, im: DoubleArray) {
    val n = re.size
    for (i in im.indices) im[i] = -im[i]
    fft(re, im)
    for (i in re.indices) {
        re[i] = re[i] / n
        im[i] = -im[i] / n
    }
}
