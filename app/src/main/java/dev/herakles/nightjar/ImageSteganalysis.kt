package dev.herakles.nightjar

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Module 1 — image chi-square steganalysis.
 *
 * Implements [CovertDetector] over `android.graphics.Bitmap` per library
 * `06_detection_and_countermeasures.md`'s "Raw-pixel LSB images" row: the **chi-square attack**
 * (Westfeld, "Attacks on Steganographic Systems", 1999/2000), the same statistic StegExpose
 * (`b3dk7/StegExpose`) fuses into its own detector. This is a passive, one-way analyzer — it
 * never recovers a payload, only scores how consistent a bitmap's pixel-value statistics are
 * with raw-pixel LSB embedding, matching the defensive posture already established
 * for [AcousticDetector]. It knows nothing about [ImageStegoCarrier]'s frame format (magic byte,
 * CRC, etc.) — the statistical test looks purely at the color-channel byte distribution, so it
 * scores *any* raw-pixel LSB tool's output, not just this app's own encoder.
 *
 * ## The chi-square "Pairs of Values" (PoV) attack
 *
 * LSB replacement (the technique [ImageStegoCarrier] uses: `withLsb` unconditionally sets a
 * channel's LSB to the payload bit) has a side effect independent of *what* is embedded:
 * embedding a bit into a channel value `v` can only move it to `v` or `v xor 1` — i.e. within its
 * "pair of values" {2i, 2i+1}. If a message occupies most/all of a region, LSBs there become
 * effectively coin-flips, which pulls each pair's two counts toward their shared average:
 * `h[2i] ≈ h[2i+1] ≈ (h[2i] + h[2i+1]) / 2`. Natural, un-embedded image data has no reason to
 * produce that equalization — adjacent byte values are usually *not* equally likely.
 *
 * The chi-square goodness-of-fit statistic measures how well an observed histogram matches that
 * "equalized pairs" pattern:
 *
 * ```
 * chi2 = sum over pairs i of (h[2i] - expected_i)^2 / expected_i,   expected_i = (h[2i]+h[2i+1])/2
 * ```
 *
 * A **small** chi2 means the histogram already looks equalized (consistent with embedding); a
 * **large** chi2 means it does not (consistent with an un-embedded carrier). Converting chi2 to
 * the chi-square distribution's upper-tail probability `p = P(X >= chi2 | dof)` turns this into
 * a [0,1] "probability of embedding" score: **p close to 1.0 is the LSB-embedding signal**, p
 * close to 0.0 is not — the inverse of a typical significance test, because here the null
 * hypothesis being tested for a *good* fit **is** "this pair looks embedded." (Verified against
 * the empirical separation observed on this app's two bundled sample images — see
 * `ImageSteganalysisTest`.)
 *
 * ## Windowed scan, not whole-image
 *
 * [ImageStegoCarrier] (and most simple raw-pixel LSB tools) embeds sequentially from the first
 * pixel, so a payload that doesn't fill the whole carrier only touches a *prefix* of the scan.
 * A single whole-image chi-square test dilutes that signal against the untouched remainder. This
 * detector instead partitions the scan into fixed-size windows (in the same row-major, R/G/B
 * per-pixel scan order [ImageStegoCarrier] embeds in) and looks for a **sustained run** of
 * consecutive windows that each individually look equalized — mirroring [AcousticDetector]'s
 * "sustained hot frames" pattern for the same reason: a single window can look equalized by pure
 * sampling noise, but a long run of consecutive windows is very unlikely to.
 *
 * **Known limitation (worth stating explicitly, per this project's Track-3 framing):** this run
 * search only looks for a *contiguous* equalized region in scan order. A tool that scatters its
 * embedding non-sequentially (e.g. pseudo-random pixel order keyed by a password) would evade
 * both the sustained-run detection and the [DetectionResult.estimatedPayloadBytes] length
 * estimate here, though an isolated equalized window could still surface as a weak (damped)
 * signal. Randomized-order embedding as the next-generation evasion for *this* detector is the
 * direct Gen-2 analog library §06's "Gen 1 defense -> Gen 2 attack" table already calls out for
 * DCT embedding.
 *
 * Unlike [AcousticDetector], this class holds no per-instance state across calls: each [Bitmap]
 * handed to [analyze] is already a complete image, not one chunk of a longer stream.
 */
class ImageSteganalysis : CovertDetector<Bitmap> {

    override val descriptor = ModuleDescriptor(
        id = ModuleId.IMAGE_STEGANALYSIS,
        displayName = "Image LSB Steganalysis (chi-square)",
        domain = CarrierDomain.IMAGE,
        role = ModuleRole.DETECTOR,
    )

    /**
     * Explicit, tunable false-positive/false-negative knob ([CovertDetector] contract; StegExpose's
     * own tunable detection threshold, library §06, default 0.2 there — "raise it to suppress false
     * positives... lower it to catch more true positives at the cost of more false alarms").
     *
     * Set to 0.85, not 0.5, based on empirical separation observed against this app's two bundled
     * sample cover images (`ImageSteganalysisTest`): the bundled **mosaic** cover — a synthetic
     * tiled/repeating-structure test pattern, not a natural photo — produces a genuine false
     * positive at the naive midpoint (a coincidental 5-window sustained run, mean p=0.70) purely
     * from its own repetitive structure, not from any embedding. Every *real* embedded payload
     * tested (from a modest ~15% capacity embed up to ~90% capacity, on both bundled covers)
     * scored mean p >= 0.98 — comfortably clear of that 0.70 noise ceiling. 0.85 sits with wide
     * margin on both sides of the observed gap and is a direct instance of this project's own
     * open question (library §06: "the actual false-positive rate of chi-square/RS analysis
     * against ordinary... images... isn't just theoretically sound" until measured) — measured
     * here, on a tiny two-image sample, not a claim that generalizes to a large corpus.
     */
    override val flagThreshold: Float = FLAG_THRESHOLD

    override fun analyze(sample: Bitmap): DetectionResult {
        val width = sample.width
        val height = sample.height
        if (width <= 0 || height <= 0) {
            return DetectionResult(confidence = 0f, flagged = false, detail = "empty bitmap, nothing to analyze")
        }

        val samples = channelSamplesInScanOrder(sample, width, height)
        val windows = partitionIntoWindows(samples.size)
        if (windows.isEmpty()) {
            return DetectionResult(
                confidence = 0f,
                flagged = false,
                detail = "carrier too small for a steganalysis window",
            )
        }

        val windowPValues = windows.map { window -> chiSquarePValueForWindow(samples, window.start, window.endExclusive) }
        val bestRun = longestSustainedRun(windowPValues)
        val maxWindowP = windowPValues.max()

        return if (bestRun != null && bestRun.lengthWindows >= MIN_SUSTAIN_WINDOWS) {
            val runWindows = windows.subList(bestRun.startIndex, bestRun.startIndex + bestRun.lengthWindows)
            val runPValues = windowPValues.subList(bestRun.startIndex, bestRun.startIndex + bestRun.lengthWindows)
            val confidence = runPValues.average().toFloat()
            val runSampleCount = runWindows.sumOf { it.endExclusive - it.start }
            DetectionResult(
                confidence = confidence,
                flagged = confidence >= flagThreshold,
                estimatedPayloadBytes = runSampleCount / BITS_PER_BYTE,
                detail = "sustained PoV-equalization run: ${bestRun.lengthWindows}/${windows.size} windows " +
                    "(~$runSampleCount of ${samples.size} channel samples), mean p=${"%.3f".format(confidence)}",
            )
        } else {
            // No run met the sustain bar: damp the strongest single window so isolated sampling
            // noise in one window can never, by itself, cross flagThreshold.
            val confidence = (maxWindowP * UNSUSTAINED_DAMPING).toFloat()
            DetectionResult(
                confidence = confidence,
                flagged = confidence >= flagThreshold,
                estimatedPayloadBytes = null,
                detail = "no sustained PoV-equalization run found; strongest single window p=${"%.3f".format(maxWindowP)}",
            )
        }
    }

    // --- Windowing over the scan-order channel-sample sequence ---

    /** Half-open sample-index range `[start, endExclusive)`. */
    private data class Window(val start: Int, val endExclusive: Int)

    private data class Run(val startIndex: Int, val lengthWindows: Int)

    /** Windows, in scan order, matching [ImageStegoCarrier]'s row-major R/G/B embedding order. */
    private fun partitionIntoWindows(totalSamples: Int): List<Window> {
        if (totalSamples <= 0) return emptyList()
        if (totalSamples < MIN_WINDOW_SAMPLES) return listOf(Window(0, totalSamples))

        val windowSize = maxOf(MIN_WINDOW_SAMPLES, totalSamples / TARGET_WINDOW_COUNT)
        val windows = mutableListOf<Window>()
        var start = 0
        while (start < totalSamples) {
            var end = min(start + windowSize, totalSamples)
            val remainder = totalSamples - end
            // Absorb a too-small trailing remainder into this window rather than running an
            // underpowered chi-square test on a handful of leftover samples.
            if (remainder in 1 until (MIN_WINDOW_SAMPLES / 2)) {
                end = totalSamples
            }
            windows.add(Window(start, end))
            start = end
        }
        return windows
    }

    /** Longest run of consecutive `p >= WINDOW_POSITIVE_P` windows anywhere in scan order. */
    private fun longestSustainedRun(windowPValues: List<Double>): Run? {
        var bestStart = -1
        var bestLen = 0
        var curStart = -1
        var curLen = 0
        for (i in windowPValues.indices) {
            if (windowPValues[i] >= WINDOW_POSITIVE_P) {
                if (curLen == 0) curStart = i
                curLen++
                if (curLen > bestLen) {
                    bestLen = curLen
                    bestStart = curStart
                }
            } else {
                curLen = 0
            }
        }
        return if (bestLen > 0) Run(bestStart, bestLen) else null
    }

    // --- Chi-square Pairs-of-Values (PoV) test (Westfeld 2000) ---

    /**
     * Upper-tail p-value of the chi-square PoV statistic for the window `[start, endExclusive)`
     * of [samples]. See the class KDoc for why p close to 1.0, not 0.0, is the embedding signal.
     */
    internal fun chiSquarePValueForWindow(samples: IntArray, start: Int, endExclusive: Int): Double {
        val hist = IntArray(POSSIBLE_CHANNEL_VALUES)
        for (i in start until endExclusive) hist[samples[i]]++

        var chi2 = 0.0
        var nonEmptyPairs = 0
        for (i in 0 until POSSIBLE_CHANNEL_VALUES / 2) {
            val a = hist[2 * i].toDouble()
            val b = hist[2 * i + 1].toDouble()
            val expected = (a + b) / 2.0
            if (expected > 0.0) {
                chi2 += (a - expected).pow(2) / expected
                nonEmptyPairs++
            }
        }
        val dof = (nonEmptyPairs - 1).coerceAtLeast(1)
        return chiSquareUpperTailP(chi2, dof)
    }

    /** `P(X >= chi2Stat)` for `X ~ chi-square(dof)` — the classical Westfeld attack's p-value. */
    internal fun chiSquareUpperTailP(chi2Stat: Double, dof: Int): Double {
        if (chi2Stat <= 0.0) return 1.0
        return regularizedGammaQ(dof / 2.0, chi2Stat / 2.0).coerceIn(0.0, 1.0)
    }

    // --- Regularized upper incomplete gamma function Q(a,x), for the chi-square CDF ---
    // Standard series (x < a+1) / continued-fraction (x >= a+1) split; see e.g. Numerical
    // Recipes' gser/gcf. Used only to convert a chi-square statistic to a p-value above.

    private fun regularizedGammaQ(a: Double, x: Double): Double {
        if (x < 0.0) return 1.0
        return if (x < a + 1.0) 1.0 - lowerIncompleteGammaSeries(a, x) else upperIncompleteGammaContinuedFraction(a, x)
    }

    private fun lowerIncompleteGammaSeries(a: Double, x: Double): Double {
        if (x <= 0.0) return 0.0
        val gln = logGamma(a)
        var ap = a
        var sum = 1.0 / a
        var del = sum
        for (n in 1..MAX_ITERATIONS) {
            ap += 1.0
            del *= x / ap
            sum += del
            if (abs(del) < abs(sum) * EPSILON) break
        }
        return sum * exp(-x + a * ln(x) - gln)
    }

    private fun upperIncompleteGammaContinuedFraction(a: Double, x: Double): Double {
        val gln = logGamma(a)
        var b = x + 1.0 - a
        var c = 1.0 / FP_MIN
        var d = 1.0 / b
        var h = d
        for (i in 1..MAX_ITERATIONS) {
            val an = -i * (i - a)
            b += 2.0
            d = an * d + b
            if (abs(d) < FP_MIN) d = FP_MIN
            c = b + an / c
            if (abs(c) < FP_MIN) c = FP_MIN
            d = 1.0 / d
            val del = d * c
            h *= del
            if (abs(del - 1.0) < EPSILON) break
        }
        return exp(-x + a * ln(x) - gln) * h
    }

    /** Lanczos approximation (g=7, n=9) log-gamma, standard double precision coefficients. */
    private fun logGamma(xIn: Double): Double {
        if (xIn < 0.5) {
            return ln(PI / sin(PI * xIn)) - logGamma(1.0 - xIn)
        }
        val x = xIn - 1.0
        var a = LANCZOS_COEFFICIENTS[0]
        val t = x + 7.5
        for (i in 1..8) a += LANCZOS_COEFFICIENTS[i] / (x + i)
        return 0.5 * ln(2.0 * PI) + (x + 0.5) * ln(t) - t + ln(a)
    }

    // --- Scan-order sample extraction (mirrors ImageStegoCarrier's bit-index math exactly) ---

    /**
     * Every R, G, B channel value (alpha excluded), in the exact order [ImageStegoCarrier] embeds
     * bits into: row-major pixels, R then G then B per pixel. `samples[bitIndex]` is the channel
     * value whose LSB [ImageStegoCarrier] would set to `bitAt(frame, bitIndex)`.
     */
    private fun channelSamplesInScanOrder(bitmap: Bitmap, width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val samples = IntArray(pixels.size * 3)
        var idx = 0
        for (pixel in pixels) {
            samples[idx++] = (pixel shr 16) and 0xFF
            samples[idx++] = (pixel shr 8) and 0xFF
            samples[idx++] = pixel and 0xFF
        }
        return samples
    }

    private companion object {
        /** Bits per byte, used to convert a run's sample count into an estimated payload size. */
        const val BITS_PER_BYTE = 8

        /** Channel values are single bytes: 0..255. */
        const val POSSIBLE_CHANNEL_VALUES = 256

        /** Target number of windows to slice a large carrier's scan into. */
        const val TARGET_WINDOW_COUNT = 32

        /** Floor on window size so each window's PoV histogram has enough samples to be meaningful. */
        const val MIN_WINDOW_SAMPLES = 1024

        /** Per-window p-value at/above which a window counts as "positive" (embedding-consistent). */
        const val WINDOW_POSITIVE_P = 0.5

        /** Minimum consecutive positive windows before we trust the run over single-window noise. */
        const val MIN_SUSTAIN_WINDOWS = 3

        /** Multiplier applied to an unsustained single-window signal so it can't alone cross the flag. */
        const val UNSUSTAINED_DAMPING = 0.5

        /** confidence >= this => flagged. See [ImageSteganalysis.flagThreshold] KDoc for rationale. */
        const val FLAG_THRESHOLD = 0.85f

        /** Numerical Recipes gser/gcf convergence knobs for the regularized incomplete gamma function. */
        const val MAX_ITERATIONS = 200
        const val EPSILON = 3.0e-12
        const val FP_MIN = 1.0e-300

        /** Lanczos approximation coefficients, g=7, n=9 (standard published double-precision set). */
        val LANCZOS_COEFFICIENTS = doubleArrayOf(
            0.99999999999980993,
            676.5203681218851,
            -1259.1392167224028,
            771.32342877765313,
            -176.61502916214059,
            12.507343278686905,
            -0.13857109526572012,
            9.9843695780195716e-6,
            1.5056327351493116e-7,
        )
    }
}
