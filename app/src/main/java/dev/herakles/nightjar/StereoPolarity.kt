package dev.herakles.nightjar

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * v5 addition (design-v5.md §4). Pure time-domain L/R polarity statistics for a
 * [AudioStegoTechnique.PHASE_INVERSION] carrier — the honest counterpart to what [spectrogram]
 * gives spectrogram-LSB, computed directly on the two channels rather than by mono-mixing them
 * away. Mono-mixing IS phase-inversion's own decode step (`AudioStegoCarrier`'s class KDoc:
 * "summing the two channels ... the original program content cancels out"), so a spectrogram of
 * the mono-summed clip already shows the payload as low-frequency brightness (`SpectrogramTest`'s
 * "brightest thing in its own spectrogram" case). This file instead makes the *cancellation
 * itself* visible, one channel against the other, which a mono-mixed view structurally cannot do.
 *
 * Every number here is computed from [interleaved] alone — no knowledge of [AudioStegoCarrier]'s
 * private constants (`SEGMENT_SAMPLES`, `MIX_AMPLITUDE`) leaks into this file, and it never
 * constructs an [AudioStegoCarrier] or calls `decode()`. `AudioStegDetector` (design-v5.md §2)
 * reuses [StereoPolarity.residualWindowRms] as one of its own PHASE_INVERSION inputs specifically
 * so the anti-correlation number the detector scores and the number this view renders can never
 * disagree (design-v5.md §2.6) — computing the same statistic twice, once loosely for a
 * detector's threshold and once precisely for a picture, is exactly the kind of drift
 * architecture.md §5 calls "the #1 integration hazard."
 *
 * @property correlation Pearson r(L, R) over the whole clip. A real phase-inversion carrier
 *   measures in the roughly −0.999..−1.000 range (dual-mono base, one channel saturating-negated
 *   — design-v5.md §2.3 measured −0.99936 to −0.99998 on real stego output); ordinary stereo
 *   content sits around +0.3..+1. Exactly `0.0` — never `NaN` — when either channel is constant
 *   (a constant signal has no linear direction to correlate against), most notably digital
 *   silence, where both channels are constant zero.
 * @property sumToDifferenceDb `10*log10(E[(L+R)^2] / E[(L-R)^2])`, clamped to
 *   [-120.0, 120.0]. For phase-inversion output the sum is small (only the mixed-in payload
 *   offset survives the cancellation) and the difference is large (twice the cover), so this
 *   reads strongly negative — the numeric form of "the cover cancels out of the sum." A
 *   perfectly silent clip, or a perfectly dual-mono one (`L == R` everywhere, so the difference
 *   energy alone is exactly `0.0`), would otherwise make this `0/0` or `x/0`; both ends are
 *   clamped to a finite value rather than left to produce `NaN`/`Infinity`.
 * @property windowFrames Samples per residual analysis window, echoed back from the call
 *   (default 480 = 10 ms at [NightjarAcoustics.SAMPLE_RATE_HZ] — deliberately the same size as
 *   [AudioStegoCarrier]'s own phase-inversion segment, so that when this *is* real stego output,
 *   each window lines up with exactly one embedded bit, though this file has no idea that's why).
 * @property residualWindowRms `rms(L+R)` per [windowFrames]-sample window,
 *   `floor(numFrames / windowFrames)` of them — a short trailing remainder shorter than one
 *   window is dropped rather than padded. This is what `AudioStegDetector` reads as its own
 *   "surviving residual" signal.
 * @property residualWindowMean *Signed* `mean(L+R)` per window — unlike [residualWindowRms],
 *   this keeps the sign, which is what actually carries phase-inversion's payload bit
 *   (`AudioStegoCarrier.readBit`: segment sum >= 0 -> bit 0, sum < 0 -> bit 1). This is the
 *   residual strip design-v5.md §4.2 draws.
 * @property zoomStartFrame Start sample index of the loudest [zoomFrames]-wide window of the
 *   left channel found by [stereoPolarity] (searched at [windowFrames]-sample hops, by summed L
 *   energy) — individual polarity cycles are only visible at this zoomed-in scale (design-v5.md
 *   §4.2: "at whole-clip scale, a waveform envelope is symmetric, so polarity is invisible").
 * @property zoomLeft [zoomFrames] samples of the left channel starting at [zoomStartFrame],
 *   normalized so the loudest sample of *either* channel in that window hits ±1.0. Fewer than
 *   [zoomFrames] samples (down to an empty array) when the whole clip is shorter than one zoom
 *   window; never longer, and never thrown on.
 * @property zoomRight The same window's right channel, normalized by the SAME peak as
 *   [zoomLeft] — not its own independent peak — so the two curves' relative amplitude, and
 *   therefore how close they come to mirroring each other, survives normalization intact.
 */
class StereoPolarity(
    val correlation: Double,
    val sumToDifferenceDb: Double,
    val windowFrames: Int,
    val residualWindowRms: FloatArray,
    val residualWindowMean: FloatArray,
    val zoomStartFrame: Int,
    val zoomLeft: FloatArray,
    val zoomRight: FloatArray,
)

/**
 * Computes [StereoPolarity] for [interleaved] L/R PCM16 audio (`interleaved[2*i]` = left,
 * `interleaved[2*i+1]` = right — the same convention [AudioStegoCarrier.encode] produces for
 * [AudioStegoTechnique.PHASE_INVERSION], and the layout [WavFile.decodePcm16] returns for any
 * stereo WAV via its `ParsedWav.samples`). A trailing unpaired sample (odd [interleaved] length)
 * is silently dropped rather than thrown on — this is a display/analysis function that may see
 * whatever stereo bytes a device WAV import or a backup-restore hands it (design-v5.md §8's
 * tampered-file threat), not a strict codec decoder that gets to assume a well-formed carrier.
 *
 * [windowFrames] and [zoomFrames] both default to values expressed in samples, not milliseconds,
 * to keep this function free of any embedded assumption about [NightjarAcoustics.SAMPLE_RATE_HZ]
 * — every clip this app produces happens to be 48 kHz, at which 480/960 samples read as 10 ms/20
 * ms, but nothing here requires that.
 */
fun stereoPolarity(
    interleaved: ShortArray,
    windowFrames: Int = 480,
    zoomFrames: Int = 960,
): StereoPolarity {
    require(windowFrames > 0) { "windowFrames must be positive, was $windowFrames" }
    require(zoomFrames > 0) { "zoomFrames must be positive, was $zoomFrames" }

    val numFrames = interleaved.size / 2 // odd trailing sample dropped, never thrown on
    val left = DoubleArray(numFrames) { interleaved[2 * it].toDouble() }
    val right = DoubleArray(numFrames) { interleaved[2 * it + 1].toDouble() }

    val numWindows = numFrames / windowFrames
    val residualWindowRms = FloatArray(numWindows)
    val residualWindowMean = FloatArray(numWindows)
    for (w in 0 until numWindows) {
        val start = w * windowFrames
        var sum = 0.0
        var sumSquares = 0.0
        for (i in start until start + windowFrames) {
            val residual = left[i] + right[i]
            sum += residual
            sumSquares += residual * residual
        }
        residualWindowMean[w] = (sum / windowFrames).toFloat()
        residualWindowRms[w] = sqrt(sumSquares / windowFrames).toFloat()
    }

    val (zoomStartFrame, zoomLength) = loudestZoomWindow(left, numFrames, windowFrames, zoomFrames)
    val zoomLeft = FloatArray(zoomLength)
    val zoomRight = FloatArray(zoomLength)
    if (zoomLength > 0) {
        var peak = 0.0
        for (i in 0 until zoomLength) {
            val idx = zoomStartFrame + i
            peak = max(peak, max(abs(left[idx]), abs(right[idx])))
        }
        // peak == 0.0 means the loudest window this search could find is itself silent, i.e. the
        // WHOLE clip is silent -- leave both arrays zero-filled rather than dividing by zero.
        if (peak > 0.0) {
            for (i in 0 until zoomLength) {
                val idx = zoomStartFrame + i
                zoomLeft[i] = (left[idx] / peak).toFloat()
                zoomRight[i] = (right[idx] / peak).toFloat()
            }
        }
    }

    return StereoPolarity(
        correlation = pearsonCorrelation(left, right),
        sumToDifferenceDb = sumToDifferenceDb(left, right),
        windowFrames = windowFrames,
        residualWindowRms = residualWindowRms,
        residualWindowMean = residualWindowMean,
        zoomStartFrame = zoomStartFrame,
        zoomLeft = zoomLeft,
        zoomRight = zoomRight,
    )
}

/**
 * Pearson r(L, R). Returns exactly `0.0` — never `NaN` — when either channel has zero variance
 * (a constant channel, e.g. digital silence, has no linear direction to correlate against;
 * `0.0` reads as "uncorrelated," a more honest default than the `0/0` a naive division would
 * produce). Clamped to `[-1.0, 1.0]` to absorb floating-point overshoot past the mathematically
 * exact bound (e.g. a measured `1.0000000000000002` for two bit-identical channels).
 */
private fun pearsonCorrelation(left: DoubleArray, right: DoubleArray): Double {
    val n = left.size
    if (n == 0) return 0.0

    var meanL = 0.0
    var meanR = 0.0
    for (i in 0 until n) {
        meanL += left[i]
        meanR += right[i]
    }
    meanL /= n
    meanR /= n

    var covariance = 0.0
    var varianceL = 0.0
    var varianceR = 0.0
    for (i in 0 until n) {
        val dl = left[i] - meanL
        val dr = right[i] - meanR
        covariance += dl * dr
        varianceL += dl * dl
        varianceR += dr * dr
    }
    if (varianceL == 0.0 || varianceR == 0.0) return 0.0
    return (covariance / sqrt(varianceL * varianceR)).coerceIn(-1.0, 1.0)
}

/**
 * `10*log10(E[(L+R)^2] / E[(L-R)^2])`, clamped to [SUM_TO_DIFFERENCE_FLOOR_DB]..
 * [-SUM_TO_DIFFERENCE_FLOOR_DB]. [ENERGY_EPSILON] is added to both the numerator and the
 * denominator energies before dividing — not to change any real-audio result (it sits about 18
 * orders of magnitude below a single PCM16 sample's own squared value) but specifically so
 * digital silence (`meanSumEnergy == meanDifferenceEnergy == 0.0` exactly) resolves to a defined
 * `0.0 dB` instead of `0.0 / 0.0 == NaN`. A dual-mono clip (`L == R` everywhere, so the
 * difference energy alone is exactly `0.0`) is the other edge this epsilon exists for: without
 * it, a genuinely non-silent sum over a zero difference is `x / 0 == Infinity`, and
 * `log10(Infinity)` propagates straight through to `Infinity` — not `NaN`, but exactly as
 * unusable to a caller formatting a fixed-decimal readout, so this function's contract is
 * "always finite," not merely "never NaN."
 */
private fun sumToDifferenceDb(left: DoubleArray, right: DoubleArray): Double {
    val n = left.size
    if (n == 0) return SUM_TO_DIFFERENCE_FLOOR_DB

    var sumEnergy = 0.0
    var differenceEnergy = 0.0
    for (i in 0 until n) {
        val s = left[i] + right[i]
        val d = left[i] - right[i]
        sumEnergy += s * s
        differenceEnergy += d * d
    }
    val ratio = (sumEnergy / n + ENERGY_EPSILON) / (differenceEnergy / n + ENERGY_EPSILON)
    return (10.0 * log10(ratio)).coerceIn(SUM_TO_DIFFERENCE_FLOOR_DB, -SUM_TO_DIFFERENCE_FLOOR_DB)
}

/**
 * Finds the loudest [zoomFrames]-wide window of [left] by summed energy, at [windowFrames]-sample
 * hops (design-v5.md §4.2: "the loudest 20 ms window (by L energy, 10 ms hop)") — never
 * sample-by-sample, since a polarity view exists to show individual cycles clearly, not to hunt
 * for the single loudest possible sample-aligned window. Returns `(0, numFrames)` when the whole
 * clip is shorter than [zoomFrames] — a "very short clip" still gets whatever it has to show,
 * rather than an empty view or an array-index exception.
 */
private fun loudestZoomWindow(
    left: DoubleArray,
    numFrames: Int,
    windowFrames: Int,
    zoomFrames: Int,
): Pair<Int, Int> {
    if (numFrames <= zoomFrames) return 0 to numFrames

    var bestStart = 0
    var bestEnergy = -1.0
    var start = 0
    while (start + zoomFrames <= numFrames) {
        var energy = 0.0
        for (i in start until start + zoomFrames) {
            energy += left[i] * left[i]
        }
        if (energy > bestEnergy) {
            bestEnergy = energy
            bestStart = start
        }
        start += windowFrames
    }
    return bestStart to zoomFrames
}

/** Clamp bound for [sumToDifferenceDb] in both directions — see that function's KDoc. */
private const val SUM_TO_DIFFERENCE_FLOOR_DB = -120.0

/** See [sumToDifferenceDb]'s KDoc for why this exists and why its exact magnitude is safe. */
private const val ENERGY_EPSILON = 1e-9
