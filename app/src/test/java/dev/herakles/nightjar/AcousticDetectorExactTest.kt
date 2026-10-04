package dev.herakles.nightjar

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic frame-level checks of [AcousticDetector]'s decision rule: the >= 3 hot bins
 * per band edge conditions, per-band (not summed) counting, the 5-frame sustain rule, the
 * exact confidence fraction, the 0.2 flag threshold, chunk-boundary buffering and stationary
 * tone rejection. Ambient frames are seeded uniform noise (so floors are realistic and
 * quantization noise cannot light up the whole band the way it would against digital silence).
 */
class AcousticDetectorExactTest {

    private val n = NightjarAcoustics.FRAME_SAMPLES
    private val ambientAmp = 2000
    private val toneAmp = 20_000.0

    private fun ambient(rnd: Random) = ShortArray(n) { rnd.nextInt(-ambientAmp, ambientAmp + 1).toShort() }

    /** Bin-exact sinusoids at [bins] (same every frame: integer cycles per frame) + ambient noise. */
    private fun toneFrame(bins: List<Int>, rnd: Random, amp: Double = toneAmp): ShortArray {
        val a = ambient(rnd)
        return ShortArray(n) { i ->
            var s = 0.0
            for (b in bins) s += sin(2.0 * PI * b * i / n)
            (a[i] + amp * s / bins.size).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun feed(d: AcousticDetector, frames: List<ShortArray>): List<DetectionResult> =
        frames.map { d.analyze(it) }

    /** [warm] noise frames, then [burst] tone frames, returns the result after the last one. */
    private fun run(bins: List<Int>, warm: Int, burst: Int, seed: Int = 5, amp: Double = toneAmp): DetectionResult {
        val rnd = Random(seed)
        val d = AcousticDetector()
        feed(d, List(warm) { ambient(rnd) })
        var last = DetectionResult(0f, false)
        repeat(burst) { last = d.analyze(toneFrame(bins, rnd, amp)) }
        return last
    }

    @Test
    fun `descriptor and threshold are the documented values`() {
        val d = AcousticDetector()
        assertEquals(ModuleId.ACOUSTIC_DETECTOR, d.descriptor.id)
        assertEquals(CarrierDomain.AUDIO, d.descriptor.domain)
        assertEquals(ModuleRole.DETECTOR, d.descriptor.role)
        assertEquals(0.2f, d.flagThreshold, 0f)
        assertEquals("Acoustic Anomaly Detector", d.descriptor.displayName)
    }

    @Test
    fun `fresh detector fed pure ambient never flags and reports zero confidence`() {
        val rnd = Random(1)
        val d = AcousticDetector()
        for (r in feed(d, List(80) { ambient(rnd) })) {
            assertEquals(0f, r.confidence, 0f)
            assertFalse(r.flagged)
            assertNull(r.detail)
            assertNull(r.estimatedPayloadBytes)
        }
    }

    @Test
    fun `no frame boundary yet means the initial zero result`() {
        val d = AcousticDetector()
        val r = d.analyze(ShortArray(n - 1))
        assertEquals(0f, r.confidence, 0f)
        assertFalse(r.flagged)
    }

    @Test
    fun `sustain needs five hot frames - confidence is hits over window and flags at exactly 0_2`() {
        val bins = listOf(60, 70, 80)
        // warm = 10 ambient frames; k tone frames. History n = 10 + k, window = min(n, 20),
        // hits = max(0, k - 4).
        val expectConf = mapOf(
            1 to 0f, 4 to 0f, 5 to 1f / 15f, 6 to 2f / 16f, 7 to 3f / 17f, 8 to 4f / 18f, 9 to 5f / 19f,
        )
        for ((k, conf) in expectConf) {
            val r = run(bins, warm = 10, burst = k)
            assertEquals("k=$k", conf, r.confidence, 1e-6f)
            assertEquals("k=$k flagged", conf >= 0.2f, r.flagged)
            if (k >= 5) {
                assertNotNull("k=$k detail", r.detail)
                assertTrue(r.detail!!, r.detail!!.startsWith("sustained tone-grid energy:"))
                assertTrue(r.detail!!, r.detail!!.endsWith("dB over floor"))
                assertTrue(r.detail!!, r.detail!!.contains("15.0"))
            } else {
                assertNull("k=$k detail", r.detail)
            }
        }
    }

    @Test
    fun `detail reports the number of hot on-grid bins`() {
        val r = run(listOf(60, 70, 80), warm = 10, burst = 6)
        // Hann main lobe: each bin-exact tone lights its own bin and the two neighbours
        assertTrue(r.detail!!, r.detail!!.contains(" 9 on-grid bin(s)"))
    }

    @Test
    fun `four hot frames then a gap then four more never reach the sustain length`() {
        val rnd = Random(3)
        val d = AcousticDetector()
        feed(d, List(30) { ambient(rnd) })
        val bins = listOf(60, 70, 80)
        var last = DetectionResult(0f, false)
        repeat(3) {
            repeat(4) { last = d.analyze(toneFrame(bins, rnd)) }
            last = d.analyze(ambient(rnd))
        }
        assertEquals(0f, last.confidence, 0f)
        assertFalse(last.flagged)
    }

    @Test
    fun `confidence decays back to zero once the burst leaves the 20 frame window`() {
        val rnd = Random(4)
        val d = AcousticDetector()
        feed(d, List(30) { ambient(rnd) })
        repeat(8) { d.analyze(toneFrame(listOf(60, 70, 80), rnd)) }
        var r = DetectionResult(0f, false)
        // history (24) = 4 ambient + 8 tone + n quiet, window = last 20. The 4 qualifying frames
        // (tone frames 5..8) fall out of the window once 17 quiet frames have followed.
        repeat(16) { r = d.analyze(ambient(rnd)) }
        assertEquals(0.2f, r.confidence, 1e-6f)
        assertTrue(r.flagged)
        r = d.analyze(ambient(rnd))
        assertEquals(0.15f, r.confidence, 1e-6f)
        assertFalse(r.flagged)
        repeat(30) { r = d.analyze(ambient(rnd)) }
        assertEquals(0f, r.confidence, 0f)
    }

    @Test
    fun `a long burst saturates the window with the ramp-up frames excluded only at the very start`() {
        val rnd = Random(6)
        val d = AcousticDetector()
        feed(d, List(40) { ambient(rnd) })
        var r = DetectionResult(0f, false)
        repeat(22) { r = d.analyze(toneFrame(listOf(60, 70, 80), rnd)) }
        // history capacity 24 = 2 ambient + 22 hot; window = last 20 (idx 4..23);
        // hot run starts at idx 2, qualifies from idx 6 -> 18 hits.
        assertEquals(18f / 20f, r.confidence, 1e-6f)
        assertTrue(r.flagged)
    }

    @Test
    fun `AUDIBLE band edges - a tone on baseBin lights only two in-band bins, one bin up lights three`() {
        val lo = NightjarAcoustics.Protocol.AUDIBLE.baseBin
        val hi = NightjarAcoustics.Protocol.AUDIBLE.topBin
        assertEquals(0f, run(listOf(lo), 20, 8).confidence, 0f)      // bins lo, lo+1 -> 2 < 3
        assertTrue(run(listOf(lo + 1), 20, 8).flagged)               // lo..lo+2 -> 3
        assertEquals(0f, run(listOf(hi), 20, 8).confidence, 0f)      // hi-1, hi -> 2
        assertTrue(run(listOf(hi - 1), 20, 8).flagged)               // hi-2..hi -> 3
    }

    @Test
    fun `NEAR_ULTRASONIC band edges behave the same`() {
        val lo = NightjarAcoustics.Protocol.NEAR_ULTRASONIC.baseBin
        val hi = NightjarAcoustics.Protocol.NEAR_ULTRASONIC.topBin
        assertEquals(0f, run(listOf(lo), 20, 8).confidence, 0f)
        assertTrue(run(listOf(lo + 1), 20, 8).flagged)
        assertEquals(0f, run(listOf(hi), 20, 8).confidence, 0f)
        assertTrue(run(listOf(hi - 1), 20, 8).flagged)
    }

    @Test
    fun `hot bins are counted per band and not summed across the two bands`() {
        val aTop = NightjarAcoustics.Protocol.AUDIBLE.topBin
        val nLo = NightjarAcoustics.Protocol.NEAR_ULTRASONIC.baseBin
        // 2 hot bins in each band = 4 total, but never 3 in one band.
        val r = run(listOf(aTop, nLo), warm = 20, burst = 8)
        assertEquals(0f, r.confidence, 0f)
        assertFalse(r.flagged)
    }

    @Test
    fun `out-of-band tones are ignored entirely`() {
        for (bins in listOf(listOf(5, 10, 15), listOf(160, 170, 180), listOf(250, 280, 300), listOf(440, 450, 460))) {
            val bin = bins[0]
            val r = run(bins, warm = 20, burst = 8)
            assertEquals("bin=$bin", 0f, r.confidence, 0f)
        }
    }

    @Test
    fun `a stationary tone is absorbed into the noise floor and never flags`() {
        val rnd = Random(8)
        val d = AcousticDetector()
        val results = feed(d, List(80) { toneFrame(listOf(60, 70, 80), rnd) })
        assertEquals(0f, results.last().confidence, 0f)
        assertTrue(results.none { it.flagged })
    }

    @Test
    fun `a faint tone under the 15 dB margin is not hot but a strong one is`() {
        // amp 400 -> ~ +9 dB at the 6 dB-down neighbour bins vs ambient floor: below margin
        val weak = run(listOf(60, 70, 80), warm = 20, burst = 8, amp = 300.0)
        assertEquals(0f, weak.confidence, 0f)
        val strong = run(listOf(60, 70, 80), warm = 20, burst = 8, amp = 20_000.0)
        assertTrue(strong.flagged)
    }

    @Test
    fun `chunked input gives the same results as frame-aligned input`() {
        val rnd = Random(9)
        val frames = List(12) { ambient(rnd) } + List(10) { toneFrame(listOf(60, 70, 80), rnd) }
        val whole = AcousticDetector()
        var expected = DetectionResult(0f, false)
        for (f in frames) expected = whole.analyze(f)

        val flat = frames.flatMap { it.asList() }.toShortArray()
        val chunked = AcousticDetector()
        var actual = DetectionResult(0f, false)
        var pos = 0
        val sizes = intArrayOf(1, 100, 1023, 777, 4096, 3)
        var si = 0
        while (pos < flat.size) {
            val len = minOf(sizes[si++ % sizes.size], flat.size - pos)
            actual = chunked.analyze(flat.copyOfRange(pos, pos + len))
            pos += len
        }
        assertEquals(expected.confidence, actual.confidence, 1e-6f)
        assertEquals(expected.flagged, actual.flagged)
        assertEquals(expected.detail, actual.detail)
    }

    @Test
    fun `an analyze call that completes no frame returns the previous result unchanged`() {
        val rnd = Random(10)
        val d = AcousticDetector()
        feed(d, List(12) { ambient(rnd) })
        var last = DetectionResult(0f, false)
        repeat(8) { last = d.analyze(toneFrame(listOf(60, 70, 80), rnd)) }
        assertTrue(last.flagged)
        val again = d.analyze(ShortArray(10))
        assertEquals(last, again)
    }

    @Test
    fun `leftover samples are carried over - two half frames complete exactly one frame`() {
        val rnd = Random(12)
        val d = AcousticDetector()
        feed(d, List(12) { ambient(rnd) })
        // 8 tone frames delivered as 16 half-frames must equal 8 whole frames.
        val tone = List(8) { toneFrame(listOf(60, 70, 80), rnd) }
        var r = DetectionResult(0f, false)
        for (f in tone) {
            d.analyze(f.copyOfRange(0, n / 2))
            r = d.analyze(f.copyOfRange(n / 2, n))
        }
        assertEquals(4f / 20f, r.confidence, 1e-6f)
        assertTrue(r.flagged)
    }
}
