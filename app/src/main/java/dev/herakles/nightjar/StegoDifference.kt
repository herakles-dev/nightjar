package dev.herakles.nightjar

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * v5 addition. Cover-vs-stego difference computation for
 * [dev.herakles.nightjar.AudioStegoTechnique.SPECTROGRAM_LSB] fireflies — the "where it hid"
 * counterpart to [LsbBitPlane] (IMAGE carrier) and [spectrogram] (AUDIO carrier generally), scoped
 * to the one technique where a bit-for-bit cover comparison is both meaningful (QIM nudges are
 * exact on the codec's own grid, unlike phase-inversion's continuous residual) and, per
 * [matchCover] below, actually obtainable without persisting anything.
 *
 * Nightjar never stores the cover a firefly was caught from — only the stego clip.
 * Both of this app's bundled covers ([dev.herakles.nightjar.modules.audiostego
 * .AudioSampleCover]) are pure, deterministic functions of nothing (a fixed seed / closed-form
 * sines), so [matchCover] re-synthesizes each candidate on demand and verifies the fit against the
 * stego clip itself, rather than trusting a stored id. [dev.herakles.nightjar.AudioStegoTechnique
 * .SPECTROGRAM_LSB] only ever touches a bounded, small fraction of frames within `[0,
 * lastChangedFrame]` and leaves everything else -- including, since the v6 near-silent-frame-skip
 * fix, any genuinely-silent frame *within* that range too -- bit-exact, so a correctly-matched
 * cover's residual energy sits four-plus orders of magnitude below a wrong
 * cover's (measured: right cover 6.5e-5 / 1.8e-7, wrong cover 1.2 / 5.98 —
 * `scratchpad/proto/run1.txt`), which is what makes a single fixed threshold ([matchCover]'s
 * [MATCH_MAX_RESIDUAL_RATIO]) separate the two cases cleanly without per-clip tuning.
 *
 * This file only ever draws conclusions it can verify from the data
 * in hand. [matchCover] returns `null` — never a best-effort guess — the moment more than zero or
 * more than one candidate plausibly fits, and [stegoDifference] never approximates a cell's
 * classification; every [DiffCell] a caller sees was computed from a real FFT of real [PcmAudio],
 * not inferred.
 */

/** Threshold [matchCover] accepts a candidate cover at: normalized residual energy
 *  `E[(stego-cover)^2] / E[cover^2]` below `1e-2` (-20 dB). Chosen with four-plus orders of
 *  margin on both sides of the measured right/wrong-cover scores above — see this file's class
 *  KDoc and the rejected-alternatives table for why re-derivation (not a stored
 *  cover id) is what needs this margin: a same-seed [kotlin.random.Random] sequence and
 *  `Math.sin` are only promised to reproduce exactly within one Kotlin/JVM build, so a small
 *  amount of real jitter has to survive matching (±1 LSB on 1% of samples is still comfortably
 *  under this bound) while a genuinely different algorithm output (a re-seeded generator, i.e.
 *  simulated drift) must not. */
private const val MATCH_MAX_RESIDUAL_RATIO = 1e-2

/**
 * A cover [dev.herakles.nightjar.modules.audiostego.AudioSampleCover] that re-derives to within
 * [MATCH_MAX_RESIDUAL_RATIO] of the stego clip [matchCover] was asked to explain, tagged with its
 * display [label] and the measured [residualRatio] (kept mainly for tests/debugging — the UI only
 * needs [cover] and [label]).
 */
data class CoverMatch(val label: String, val cover: PcmAudio, val residualRatio: Double)

/**
 * Finds the unique bundled cover [stegoMono] was embedded from, or `null` if none fits — this
 * never guesses. [candidates] is a `(label, supplier)` list so callers (`JarDetailScreen`'s
 * call site: `AudioSampleCover.entries.map { it.label to { synthesizeSampleCover(it) } }`) don't
 * pay to synthesize every ~480 KB bundled cover up front; each supplier only runs when this
 * function actually reaches it, and scanning stops as soon as a second candidate also matches
 * (uniqueness is already lost at that point, so there is nothing left to learn from the rest of
 * the list).
 *
 * A candidate whose synthesized length doesn't match [stegoMono]'s is a **non-match, never a
 * throw** — [dev.herakles.nightjar.modules.audiostego.AudioSampleCover] is a closed, fixed-length
 * enum today, but a length mismatch is exactly the kind of "this candidate obviously isn't it"
 * signal [matchCover] should treat the same way as a bad residual ratio, not a crash.
 *
 * The match statistic — `E[(stego-cover)^2] / E[cover^2]` against [MATCH_MAX_RESIDUAL_RATIO] — is
 * valid here specifically because [dev.herakles.nightjar.AudioStegoTechnique.SPECTROGRAM_LSB]
 * only ever nudges a small, bounded fraction of frames and leaves everything else bit-exact;
 * it is **not** generally valid for every technique this app has (MFSK's additive tones
 * outweigh the cover: measured 4.19 for the right cover, 2.05 for the wrong one — same section),
 * which is exactly why callers gate this to `technique == "SPECTROGRAM_LSB"` rather than this
 * function trying to detect technique on its own.
 */
fun matchCover(stegoMono: PcmAudio, candidates: List<Pair<String, () -> PcmAudio>>): CoverMatch? {
    var found: CoverMatch? = null
    var matchCount = 0
    for ((label, synthesize) in candidates) {
        val candidate = synthesize()
        if (candidate.size != stegoMono.size) continue // size mismatch: a non-match, never a throw

        var residualEnergy = 0.0
        var coverEnergy = 0.0
        for (i in candidate.indices) {
            val diff = (stegoMono[i] - candidate[i]).toDouble()
            residualEnergy += diff * diff
            val c = candidate[i].toDouble()
            coverEnergy += c * c
        }
        // A literally silent candidate (coverEnergy == 0) can only "match" a literally silent
        // stego clip -- there is no meaningful ratio to divide by otherwise. Real bundled covers
        // are never silent, but this keeps the statistic well-defined rather than NaN.
        val ratio = when {
            coverEnergy > 0.0 -> residualEnergy / coverEnergy
            residualEnergy == 0.0 -> 0.0
            else -> Double.POSITIVE_INFINITY
        }

        if (ratio < MATCH_MAX_RESIDUAL_RATIO) {
            matchCount++
            if (matchCount > 1) return null // uniqueness already lost; nothing left to learn
            found = CoverMatch(label, candidate, ratio)
        }
    }
    return if (matchCount == 1) found else null
}

/**
 * The three ways a single (frame, bin) cell of [StegoDifferenceMap.kinds] can differ between a
 * cover and its spectrogram-LSB stego (measured against real
 * [dev.herakles.nightjar.AudioStegoCarrier] output, `scratchpad/proto/run3.txt`'s "difference map
 * classes" section):
 *  - [NUDGED]: the cover already had real energy here (`|X| >= 1000`,
 *    [dev.herakles.nightjar.AudioStegoCarrier]'s own `LOG_MAGNITUDE_FLOOR`) and QIM shifted its
 *    log-magnitude to the nearest bucket matching the embedded bit — a bounded, signed nudge.
 *  - [CREATED]: the cover had (near-)nothing here (`|X| < 1000`) for QIM to nudge, so
 *    `embedBitInBin`'s own floor forced the bin's magnitude up from near-zero to the lattice
 *    itself — new, not shifted, spectral content. Silent
 *    stretches of a cover (SPOKEN_WORD's between-syllable gaps, SOFT_SYNTH's fade-in) get audibly
 *    faint, but visually real, broadband texture where the embedded band would otherwise recover
 *    the original silence -- the v4 "a spectrogram can't show it" caption was false on exactly
 *    this case.
 *  - [UNCHANGED]: everything else, including every cell of a frame the codec never touched at
 *    all — [stegoDifference] skips the FFT for those frames entirely rather than computing (and
 *    discarding) a zero delta for them.
 */
enum class DiffCell { UNCHANGED, NUDGED, CREATED }

/**
 * Result of [stegoDifference]: a compact, "codec's own grid" map of exactly what
 * [dev.herakles.nightjar.AudioStegoTechnique.SPECTROGRAM_LSB] changed between [PcmAudio] [cover]
 * and its stego. [deltaNats]/[kinds] are indexed `[frame - firstChangedFrame][bin]` — only the
 * span of frames that actually changed is stored, not every frame of the clip — since a 5 s clip
 * has 234 [frameSize]-sample frames total but a typical embed touches a handful of them at the
 * front (worked examples: 6 to 106 frames depending on payload size).
 *
 * @property frameSize the codec's own [dev.herakles.nightjar.AudioStegoCarrier] frame size (1024
 *   samples) — [stegoDifference] always uses this, never a caller-supplied value, because the
 *   whole point of this map is to show exactly the grid the codec itself computed on.
 * @property totalFrames `cover.size / frameSize` — the clip's full frame count (unlike the
 *   [deltaNats]/[kinds] row count, which only spans the changed frames).
 * @property firstChangedFrame index of the first frame with `max|stego-cover| > 1 LSB`, or `-1`
 *   when [cover] and [stego] are identical (no row data exists in that case).
 * @property lastChangedFrame index of the last changed frame, or `-1` alongside
 *   [firstChangedFrame].
 * @property binCount how many bins from bin 0 were analyzed (default 128 = 0-6 kHz at 48 kHz/1024,
 *   this map's own display range).
 * @property deltaNats `ln(|stego bin magnitude|) - ln(|cover bin magnitude|)`, only meaningful
 *   (and only ever nonzero) for [NUDGED] cells — [CREATED] cells have no well-defined ratio
 *   (the cover side is near-zero) and stay `0f`, same as [UNCHANGED].
 * @property kinds this map's actual classification per cell, see [DiffCell].
 * @property changedBinRange the **span** (first qualifying bin through last, inclusive of
 *   whatever sits between them) of bins whose mean `|deltaNats|` across changed frames is
 *   `>= 0.03` nats, or `null` if no bin qualifies. The thresholded *set* of
 *   qualifying bins alone can miss 2-4 bins at the edges of a 4-frame strength-4 embed, so the
 *   span — not the set — is what recovers the codec's true `ELIGIBLE_BIN_START until
 *   ELIGIBLE_BIN_START + binsPerFrame` band exactly, measured across 18 cases
 *   (`scratchpad/proto/run5.txt`).
 * @property nudgedCells total [NUDGED] cell count across the whole map.
 * @property createdCells total [CREATED] cell count across the whole map — `0` whenever the cover
 *   had real energy everywhere the codec touched (e.g. SOFT_SYNTH past its fade-in).
 * @property maxNudgeNats the largest `|deltaNats|` seen at any [NUDGED] cell — bounded in theory by
 *   QIM's own construction at `1.5 * QUANTIZATION_STEP` = 0.18 nats, but the
 *   real 16-bit-rounded codec measures slightly past it: worst case 0.18752 nats / ~1.5627×
 *   QUANTIZATION_STEP (SOFT_SYNTH, strength=3, at its own max payload — `SpectrogramTest`'s
 *   encoder-level sweep across every strength/payload/cover combination), from `ifft`-then-
 *   `roundToShort` overshoot on top of `embedBitInBin`'s exact continuous math, not a QIM bug.
 */
class StegoDifferenceMap(
    val frameSize: Int,
    val sampleRateHz: Int,
    val totalFrames: Int,
    val firstChangedFrame: Int,
    val lastChangedFrame: Int,
    val binCount: Int,
    val deltaNats: Array<FloatArray>,
    val kinds: Array<Array<DiffCell>>,
    val changedBinRange: IntRange?,
    val nudgedCells: Int,
    val createdCells: Int,
    val maxNudgeNats: Double,
)

/** [dev.herakles.nightjar.AudioStegoCarrier]'s own `FRAME_SIZE` -- duplicated here as a private
 *  constant (that file's constants are `private`, same drift note [AudioStegDetector] documents
 *  for its own copies) since [stegoDifference] must analyze on exactly the codec's grid to mean
 *  anything. */
private const val FRAME_SIZE = 1024

/** [dev.herakles.nightjar.AudioStegoCarrier]'s own `LOG_MAGNITUDE_FLOOR` -- the magnitude below
 *  which a bin is "informative-less" for QIM, so a cover cell below it had nothing for the codec
 *  to nudge (see [DiffCell.CREATED]'s KDoc). */
private const val LOG_MAGNITUDE_FLOOR = 1000.0

/** Minimum stego-minus-cover magnitude jump a below-floor cover cell must show to be classed
 *  [DiffCell.CREATED] rather than [DiffCell.UNCHANGED] -- distinguishes a bin QIM actually forced
 *  up to the lattice (jumps by hundreds of magnitude units, per `LOG_MAGNITUDE_FLOOR`'s own KDoc)
 *  from ordinary `ifft`-then-round-to-`Short` reconstruction noise elsewhere in a changed frame
 *  (empirically single-digit-to-low-double-digit magnitude units, same KDoc), which never gets
 *  anywhere close to 30. Matches this file's measured rule and
 *  `scratchpad/proto/run3.txt`'s "difference map classes" measurements exactly (e.g. SPOKEN_WORD
 *  40 B: 48 created cells, 200 B: 329, SOFT_SYNTH 5 B fade-in: 32). */
private const val CREATED_MAGNITUDE_JUMP = 30.0

/** Per-bin mean-`|deltaNats|` threshold [changedBinRange][StegoDifferenceMap.changedBinRange] uses
 *  to decide a bin belongs to the embedded band -- measured out-of-band (pure
 *  reconstruction-noise) means stay `<= 0.027` nats and every in-band bin's mean is `>= 0.0141`
 *  across 18 measured strength/payload/cover combinations (`scratchpad/proto/run5.txt`), so 0.03
 *  sits in the gap between them. */
private const val CHANGED_BIN_MEAN_THRESHOLD = 0.03

/** Floor under a magnitude before taking its natural log here, purely to keep [ln] finite for an
 *  exactly-silent stego bin -- same role as [Spectrogram]'s `MIN_MAGNITUDE`, not
 *  [LOG_MAGNITUDE_FLOOR] (which is a much larger, QIM-specific threshold this file only uses for
 *  cell classification, not for keeping a log call defined). */
private const val MIN_LOG_MAGNITUDE = 1e-9

/**
 * Computes the cell-by-cell difference between [cover] and its [dev.herakles.nightjar
 * .AudioStegoTechnique.SPECTROGRAM_LSB] [stego] on the codec's own [FRAME_SIZE]-sample,
 * un-windowed FFT grid — see [StegoDifferenceMap]'s class KDoc for what each field means and
 * [DiffCell] for the three cell kinds. `require(cover.size == stego.size)`, since a codec-produced
 * stego is always exactly [cover]'s length (unlike [matchCover], which treats a length mismatch
 * between an unverified candidate and an arbitrary stego clip as a soft non-match, this function's
 * contract is that both inputs are already known to correspond — a length mismatch here is a
 * caller bug).
 *
 * A frame is *changed* iff `max|stego-cover| > 1 LSB` anywhere in it — the ">1" (not ">=1")
 * tolerates the same kind of ±1-LSB round-trip jitter [matchCover] tolerates, without ever
 * flagging a frame the codec provably never touched. Only changed frames are FFT'd at all: an
 * untouched frame's row that falls between [StegoDifferenceMap.firstChangedFrame] and
 * [StegoDifferenceMap.lastChangedFrame] is left at its default all-[DiffCell.UNCHANGED]
 * classification with no FFT spent on it. Originally written defensively for embedding that
 * "should not happen in practice" to be non-contiguous; as of `AudioStegoCarrier`'s v6
 * near-silent-frame-skip fix (follow-up A), it genuinely is — [SPECTROGRAM_LSB]'s payload+trailer
 * region can now skip real, near-silent frames *within* the embedded range, and this is exactly
 * the code path that makes those skipped frames render as UNCHANGED rather than a phantom NUDGED/
 * CREATED cell.
 */
fun stegoDifference(cover: PcmAudio, stego: PcmAudio, binCount: Int = 128): StegoDifferenceMap {
    require(cover.size == stego.size) {
        "cover (${cover.size} samples) and stego (${stego.size} samples) must be the same length"
    }
    require(binCount in 1..(FRAME_SIZE / 2)) {
        "binCount must be in 1..${FRAME_SIZE / 2} (Nyquist), was $binCount"
    }

    val totalFrames = cover.size / FRAME_SIZE
    val frameChanged = BooleanArray(totalFrames)
    var firstChanged = -1
    var lastChanged = -1
    for (f in 0 until totalFrames) {
        val start = f * FRAME_SIZE
        var changed = false
        for (i in start until start + FRAME_SIZE) {
            if (abs(stego[i] - cover[i]) > 1) {
                changed = true
                break
            }
        }
        frameChanged[f] = changed
        if (changed) {
            if (firstChanged == -1) firstChanged = f
            lastChanged = f
        }
    }

    if (firstChanged == -1) {
        return StegoDifferenceMap(
            frameSize = FRAME_SIZE,
            sampleRateHz = NightjarAcoustics.SAMPLE_RATE_HZ,
            totalFrames = totalFrames,
            firstChangedFrame = -1,
            lastChangedFrame = -1,
            binCount = binCount,
            deltaNats = emptyArray(),
            kinds = emptyArray(),
            changedBinRange = null,
            nudgedCells = 0,
            createdCells = 0,
            maxNudgeNats = 0.0,
        )
    }

    val rows = lastChanged - firstChanged + 1
    val deltaNats = Array(rows) { FloatArray(binCount) }
    val kinds = Array(rows) { Array(binCount) { DiffCell.UNCHANGED } }
    var nudgedCells = 0
    var createdCells = 0
    var maxNudgeNats = 0.0
    val binDeltaSum = DoubleArray(binCount)
    val binDeltaCount = IntArray(binCount)

    for (f in firstChanged..lastChanged) {
        if (!frameChanged[f]) continue // codec never touched this frame -- no FFT, stays UNCHANGED
        val row = f - firstChanged
        val start = f * FRAME_SIZE

        val coverRe = DoubleArray(FRAME_SIZE) { i -> cover[start + i].toDouble() }
        val coverIm = DoubleArray(FRAME_SIZE)
        fft(coverRe, coverIm)
        val stegoRe = DoubleArray(FRAME_SIZE) { i -> stego[start + i].toDouble() }
        val stegoIm = DoubleArray(FRAME_SIZE)
        fft(stegoRe, stegoIm)

        for (bin in 0 until binCount) {
            val coverMagnitude = sqrt(coverRe[bin] * coverRe[bin] + coverIm[bin] * coverIm[bin])
            val stegoMagnitude = sqrt(stegoRe[bin] * stegoRe[bin] + stegoIm[bin] * stegoIm[bin])

            if (coverMagnitude >= LOG_MAGNITUDE_FLOOR) {
                val delta = ln(maxOf(stegoMagnitude, MIN_LOG_MAGNITUDE)) - ln(coverMagnitude)
                kinds[row][bin] = DiffCell.NUDGED
                deltaNats[row][bin] = delta.toFloat()
                nudgedCells++
                val absDelta = abs(delta)
                if (absDelta > maxNudgeNats) maxNudgeNats = absDelta
                binDeltaSum[bin] += absDelta
                binDeltaCount[bin]++
            } else if (stegoMagnitude - coverMagnitude >= CREATED_MAGNITUDE_JUMP) {
                kinds[row][bin] = DiffCell.CREATED
                createdCells++
                // deltaNats[row][bin] stays 0f -- no well-defined ratio with a near-zero cover
                // magnitude, and this cell contributes nothing to changedBinRange's per-bin mean.
            }
            // else: stays DiffCell.UNCHANGED (the row's default), e.g. reconstruction noise that
            // never crossed CREATED_MAGNITUDE_JUMP.
        }
    }

    var minBin = -1
    var maxBin = -1
    for (bin in 0 until binCount) {
        if (binDeltaCount[bin] == 0) continue
        val mean = binDeltaSum[bin] / binDeltaCount[bin]
        if (mean >= CHANGED_BIN_MEAN_THRESHOLD) {
            if (minBin == -1) minBin = bin
            maxBin = bin
        }
    }
    val changedBinRange = if (minBin == -1) null else minBin..maxBin

    return StegoDifferenceMap(
        frameSize = FRAME_SIZE,
        sampleRateHz = NightjarAcoustics.SAMPLE_RATE_HZ,
        totalFrames = totalFrames,
        firstChangedFrame = firstChanged,
        lastChangedFrame = lastChanged,
        binCount = binCount,
        deltaNats = deltaNats,
        kinds = kinds,
        changedBinRange = changedBinRange,
        nudgedCells = nudgedCells,
        createdCells = createdCells,
        maxNudgeNats = maxNudgeNats,
    )
}
