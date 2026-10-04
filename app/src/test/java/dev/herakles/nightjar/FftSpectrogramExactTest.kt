package dev.herakles.nightjar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Hand-computed / independent-reference checks for [fft], [ifft] and [spectrogram]. The
 * reference here is a naive O(n^2) DFT, so a wrong butterfly, twiddle, bit-reversal or window
 * is caught by exact numbers rather than by a "peaks at the right bin" smoke check.
 */
class FftSpectrogramExactTest {

    private val eps = 1e-9

    private fun naiveDft(x: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = x.size
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (k in 0 until n) {
            for (t in 0 until n) {
                val a = -2.0 * PI * k * t / n
                re[k] += x[t] * cos(a)
                im[k] += x[t] * sin(a)
            }
        }
        return re to im
    }

    // ---- fft ---------------------------------------------------------------------------

    @Test
    fun `fft of 1 2 3 4 is 10, -2+2i, -2, -2-2i`() {
        val re = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        val im = DoubleArray(4)
        fft(re, im)
        assertArrayEquals(doubleArrayOf(10.0, -2.0, -2.0, -2.0), re, eps)
        assertArrayEquals(doubleArrayOf(0.0, 2.0, 0.0, -2.0), im, eps)
    }

    @Test
    fun `fft of size 1 and size 2 are trivial`() {
        val re1 = doubleArrayOf(5.0); val im1 = doubleArrayOf(0.0)
        fft(re1, im1)
        assertEquals(5.0, re1[0], eps)
        val re = doubleArrayOf(3.0, 1.0); val im = DoubleArray(2)
        fft(re, im)
        assertArrayEquals(doubleArrayOf(4.0, 2.0), re, eps)
        assertArrayEquals(doubleArrayOf(0.0, 0.0), im, eps)
    }

    @Test
    fun `fft matches a naive DFT for sizes 8 and 64 with real input`() {
        val rnd = Random(7)
        for (n in intArrayOf(8, 16, 64)) {
            val x = DoubleArray(n) { rnd.nextDouble(-1.0, 1.0) }
            val (er, ei) = naiveDft(x)
            val re = x.copyOf(); val im = DoubleArray(n)
            fft(re, im)
            assertArrayEquals("re n=$n", er, re, 1e-9)
            assertArrayEquals("im n=$n", ei, im, 1e-9)
        }
    }

    @Test
    fun `fft handles complex input - an impulse at index 1 gives a complex exponential`() {
        val n = 8
        val re = DoubleArray(n); val im = DoubleArray(n)
        re[1] = 1.0
        fft(re, im)
        for (k in 0 until n) {
            assertEquals("re k=$k", cos(-2.0 * PI * k / n), re[k], eps)
            assertEquals("im k=$k", sin(-2.0 * PI * k / n), im[k], eps)
        }
        // purely imaginary input i*1 at index 0 -> every bin is i
        val re2 = DoubleArray(4); val im2 = DoubleArray(4); im2[0] = 1.0
        fft(re2, im2)
        assertArrayEquals(DoubleArray(4), re2, eps)
        assertArrayEquals(doubleArrayOf(1.0, 1.0, 1.0, 1.0), im2, eps)
    }

    @Test
    fun `fft rejects non power of two sizes`() {
        for (n in intArrayOf(3, 6, 12, 1000)) {
            try {
                fft(DoubleArray(n), DoubleArray(n))
                fail("n=$n should throw")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("$n"))
            }
        }
    }

    // ---- ifft --------------------------------------------------------------------------

    @Test
    fun `ifft inverts fft and divides by n`() {
        val rnd = Random(3)
        val n = 32
        val xr = DoubleArray(n) { rnd.nextDouble(-1.0, 1.0) }
        val xi = DoubleArray(n) { rnd.nextDouble(-1.0, 1.0) }
        val re = xr.copyOf(); val im = xi.copyOf()
        fft(re, im)
        ifft(re, im)
        assertArrayEquals(xr, re, 1e-12)
        assertArrayEquals(xi, im, 1e-12)
    }

    @Test
    fun `ifft of a constant spectrum is an impulse of height 1 and conjugates imaginary parts correctly`() {
        val re = doubleArrayOf(4.0, 4.0, 4.0, 4.0); val im = DoubleArray(4)
        ifft(re, im)
        assertArrayEquals(doubleArrayOf(4.0, 0.0, 0.0, 0.0), re, eps)
        assertArrayEquals(DoubleArray(4), im, eps)
        // spectrum [0, i, 0, 0] -> x[t] = (i/4) * e^{+2 pi i t/4}: x = i/4 * [1, i, -1, -i]
        val r2 = DoubleArray(4); val i2 = doubleArrayOf(0.0, 1.0, 0.0, 0.0)
        ifft(r2, i2)
        assertArrayEquals(doubleArrayOf(0.0, -0.25, 0.0, 0.25), r2, eps)
        assertArrayEquals(doubleArrayOf(0.25, 0.0, -0.25, 0.0), i2, eps)
    }

    // ---- spectrogram: structure --------------------------------------------------------

    @Test
    fun `column count follows ceil(frames over hop) and metadata is carried through`() {
        val d = spectrogram(ShortArray(2500), frameSize = 1024, hop = 512, sampleRateHz = 44_100)
        assertEquals(5, d.columns.size) // starts 0,512,1024,1536,2048
        assertEquals(1024, d.frameSize)
        assertEquals(512, d.hop)
        assertEquals(44_100, d.sampleRateHz)
        assertEquals(513, d.binCount)
        assertTrue(d.columns.all { it.size == 513 })

        assertEquals(2, spectrogram(ShortArray(1024), frameSize = 1024).columns.size) // starts 0,512
        assertEquals(3, spectrogram(ShortArray(1025), frameSize = 1024).columns.size) // 0,512,1024
        assertEquals(1, spectrogram(ShortArray(1024), frameSize = 1024, hop = 1024).columns.size)
        assertEquals(2, spectrogram(ShortArray(1025), frameSize = 1024, hop = 1024).columns.size)
        assertEquals(3, spectrogram(ShortArray(1024), frameSize = 1024, hop = 400).columns.size) // 0,400,800
        assertEquals(1, spectrogram(ShortArray(1), frameSize = 8).columns.size)
    }

    @Test
    fun `defaults - hop is half the frame and rate is the nightjar rate`() {
        val d = spectrogram(ShortArray(100))
        assertEquals(1024, d.frameSize)
        assertEquals(512, d.hop)
        assertEquals(NightjarAcoustics.SAMPLE_RATE_HZ, d.sampleRateHz)
        val d2 = spectrogram(ShortArray(100), frameSize = 256)
        assertEquals(128, d2.hop)
        assertEquals(129, d2.binCount)
    }

    @Test
    fun `argument validation - non power of two frame, zero frame, nonpositive hop`() {
        fun expectIae(block: () -> Unit, needle: String) {
            try { block(); fail("expected IllegalArgumentException: $needle") }
            catch (e: IllegalArgumentException) { assertTrue(e.message, e.message!!.contains(needle)) }
        }
        expectIae({ spectrogram(ShortArray(8), frameSize = 100) }, "100")
        expectIae({ spectrogram(ShortArray(8), frameSize = 0) }, "0")
        expectIae({ spectrogram(ShortArray(8), frameSize = -8) }, "-8")
        expectIae({ spectrogram(ShortArray(8), frameSize = 8, hop = 0) }, "hop")
        expectIae({ spectrogram(ShortArray(8), frameSize = 8, hop = -1) }, "-1")
        // frameSize 1 is a power of two and is accepted (binCount = 1 / 2 + 1 = 1)
        assertEquals(1, spectrogram(ShortArray(2), frameSize = 1, hop = 1).binCount)
    }

    // ---- spectrogram: exact values -----------------------------------------------------

    private fun refColumns(mono: DoubleArray, frameSize: Int, hop: Int): List<DoubleArray> {
        val out = mutableListOf<DoubleArray>()
        var start = 0
        while (start < mono.size) {
            val x = DoubleArray(frameSize) { n ->
                val v = if (start + n < mono.size) mono[start + n] else 0.0
                v * (0.5 - 0.5 * cos(2.0 * PI * n / (frameSize - 1)))
            }
            val (r, i) = naiveDft(x)
            out.add(DoubleArray(frameSize / 2 + 1) { b -> 20.0 * log10(max(sqrt(r[b] * r[b] + i[b] * i[b]), 1e-9)) })
            start += hop
        }
        return out
    }

    private fun assertMatchesReference(pcmMono: ShortArray, frameSize: Int, hop: Int) {
        val d = spectrogram(pcmMono, 1, frameSize, hop)
        val ref = refColumns(DoubleArray(pcmMono.size) { pcmMono[it].toDouble() / Short.MAX_VALUE }, frameSize, hop)
        assertEquals(ref.size, d.columns.size)
        for (c in ref.indices) for (b in ref[c].indices) {
            assertEquals("col=$c bin=$b", ref[c][b], d.columns[c][b], 1e-6)
        }
    }

    @Test
    fun `mono output equals the naive Hann-windowed DFT in dB, including overlap and zero padded tail`() {
        val rnd = Random(11)
        val pcm = ShortArray(29) { rnd.nextInt(-20000, 20000).toShort() }
        assertMatchesReference(pcm, frameSize = 8, hop = 4)  // overlap + partial tail
        assertMatchesReference(pcm, frameSize = 16, hop = 16) // no overlap, tail padded
        assertMatchesReference(pcm, frameSize = 8, hop = 11)  // hop larger than the frame skips samples
    }

    @Test
    fun `constant full-scale signal has DC magnitude equal to the Hann sum (N-1)_2`() {
        val n = 8
        val d = spectrogram(ShortArray(8) { Short.MAX_VALUE }, frameSize = n, hop = n)
        assertEquals(1, d.columns.size)
        assertEquals(20.0 * log10(3.5), d.columns[0][0], 1e-9) // sum of Hann(8) = 3.5
    }

    @Test
    fun `silence floors at minus 180 dB rather than minus infinity`() {
        val d = spectrogram(ShortArray(16), frameSize = 8, hop = 8)
        for (col in d.columns) for (v in col) assertEquals(-180.0, v, 1e-9)
    }

    @Test
    fun `stereo is a plain per-frame average - opposite channels cancel, equal channels match mono`() {
        val n = 8
        val opp = ShortArray(16) { if (it % 2 == 0) 20000 else -20000 }
        val d = spectrogram(opp, channels = 2, frameSize = n, hop = n)
        assertEquals(1, d.columns.size)
        for (v in d.columns[0]) assertEquals(-180.0, v, 1e-9)

        // L = 30000, R = 10000 -> mean 20000 per frame
        val lr = ShortArray(16) { if (it % 2 == 0) 30000 else 10000 }
        val mono = ShortArray(8) { 20000 }
        val a = spectrogram(lr, channels = 2, frameSize = n, hop = n)
        val b = spectrogram(mono, channels = 1, frameSize = n, hop = n)
        for (i in a.columns[0].indices) assertEquals(b.columns[0][i], a.columns[0][i], 1e-9)

        // three channels: mean of (3000, 6000, 9000) = 6000
        val tri = ShortArray(24) { intArrayOf(3000, 6000, 9000)[it % 3].toShort() }
        val c = spectrogram(tri, channels = 3, frameSize = n, hop = n)
        val m6 = spectrogram(ShortArray(8) { 6000 }, 1, n, n)
        for (i in c.columns[0].indices) assertEquals(m6.columns[0][i], c.columns[0][i], 1e-9)
        assertEquals(1, c.columns.size)
    }

    @Test
    fun `channels below one are coerced to mono`() {
        val pcm = ShortArray(8) { (it * 1000).toShort() }
        val a = spectrogram(pcm, channels = 0, frameSize = 8, hop = 8)
        val b = spectrogram(pcm, channels = 1, frameSize = 8, hop = 8)
        for (i in a.columns[0].indices) assertEquals(b.columns[0][i], a.columns[0][i], 1e-12)
        val c = spectrogram(pcm, channels = -3, frameSize = 8, hop = 8)
        for (i in c.columns[0].indices) assertEquals(b.columns[0][i], c.columns[0][i], 1e-12)
    }

    @Test
    fun `a trailing partial interleaved stereo frame is dropped from the frame count`() {
        // 17 samples / 2 channels = 8 whole stereo frames -> a single 8-sample column.
        val d = spectrogram(ShortArray(17) { 1000 }, channels = 2, frameSize = 8, hop = 8)
        assertEquals(1, d.columns.size)
        // 9 whole frames -> a second (zero-padded) column starting at frame 8
        assertEquals(2, spectrogram(ShortArray(18), channels = 2, frameSize = 8, hop = 8).columns.size)
    }

    @Test
    fun `dB uses 20 log10 of linear magnitude - doubling amplitude adds 6_02 dB`() {
        val a = spectrogram(ShortArray(8) { 5000 }, frameSize = 8, hop = 8).columns[0][0]
        val b = spectrogram(ShortArray(8) { 10000 }, frameSize = 8, hop = 8).columns[0][0]
        assertEquals(20.0 * log10(2.0), b - a, 1e-9)
        assertTrue(abs(a - 20.0 * log10(3.5 * 5000.0 / Short.MAX_VALUE)) < 1e-9)
    }
}
