package dev.herakles.nightjar

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Module 2's blind audio steganalysis detector (design-v5.md §2, spec.md INV-7/gate-22).
 *
 * "Blind" here means **stego-only**: [analyze] receives only the clip under test, never a cover,
 * a key, or a decode. "Targeted" means each of the three statistics below knows exactly what
 * [AudioStegoCarrier]'s three techniques do to a spectrum/channel-pair — the same posture
 * [ImageSteganalysis]'s chi-square attack already takes toward [ImageStegoCarrier]'s raw-pixel
 * LSB replacement. Honest framing for the UI: *"looks for this app's own three techniques.
 * 'clear' means none of those three, not that nothing is hidden."*
 *
 * **INV-7 (blind posture, enforced structurally, not just by convention)**: this file never
 * constructs an [AudioStegoCarrier], never invokes its decode entry point, and never reads
 * nightjar's frame header (magic byte, version, length, CRC). Every statistic below is a property
 * of the raw sample data — correlation, spectral-lattice distance, tone-band energy — never a
 * recovered payload byte. [AudioStegDetectorTest] enforces this with a source-text check, so a
 * future edit that reaches for the real codec fails loudly instead of silently breaking the blind
 * contract.
 *
 * **Interface binding (deviation from architecture.md §7's `AudioStegDetector : CovertDetector<
 * PcmAudio>` sketch)**: [PcmAudio] is mono by contract, but [AudioStegoTechnique.PHASE_INVERSION]
 * output is interleaved stereo — a detector over a bare `ShortArray` would have to *guess* the
 * channel layout, and for phase-inversion the layout **is** the signal. [WavFile.ParsedWav]
 * already carries the `(sampleRateHz, numChannels, samples)` triple every caller has on hand (the
 * jar detail gets one from `decodePcm16`; the screens build one from `workingAudio` +
 * `workingChannelCount`), so this detector binds to that instead.
 *
 * ## The three statistics (design-v5.md §2.3)
 *
 * Shared analysis grid: [AudioStegoCarrier]'s own — 1024-sample frames from sample 0, rectangular
 * window, the shared [fft]. Using the embedder's own basis is what makes these statistics exact
 * rather than approximate. For stereo input, [spectrogramLsbScore] and [mfskScore] run per
 * channel and report the louder channel's result (the codec never itself produces stereo output
 * for those two techniques, but a stereo *file* — e.g. a phase-inversion carrier re-analyzed, or
 * an imported stereo WAV — should still be scored on both channels). [phaseInversionScore] only
 * runs when the input is genuinely stereo (`numChannels == 2`); mono input reports it "n/a (mono)".
 *
 * **Phase inversion — anti-phase dual-mono with a surviving residual.** The codec writes
 * `L = cover`, `R = -cover + (±64 per 480-sample segment)`. Two numbers separate a real
 * phase-inversion stego from an innocent polarity-flipped export (`L = -R`, nothing mixed in,
 * which alone is a mastering accident, not this technique): `ρ = corr(L, R)` (stego measures
 * -0.99936..-0.99998; ordinary stereo sits around +0.3..+1) and the surviving `L+R` residual
 * energy — a plain inverted export cancels to exactly zero, while this technique's mixed-in
 * offset survives the cancellation. [StereoPolarity] (§4, `StereoPolarity.kt`) already computes
 * both numbers for its own polarity view; [phaseInversionScore] reuses
 * [StereoPolarity.residualWindowRms] and [StereoPolarity.correlation] directly rather than
 * recomputing them, so the anti-correlation number this detector scores and the number the
 * polarity view renders can never disagree (architecture.md §5: encoder/decoder/detector drift is
 * "the #1 integration hazard").
 *
 * **Spectrogram-LSB — lattice snapping (unkeyed QIM).** The codec sets `ln|X[k]|` to exactly
 * `idx*Δ` (Δ = [SLSB_QUANTIZATION_STEP]) in its eligible bins. After ifft and 16-bit rounding, the
 * recomputed `ln|X|/Δ` still sits within a few hundredths of an integer. In clean audio that
 * fractional distance is close to uniform on `[0, 0.5]`. This detector never reads a bit: it only
 * measures how close the spectrum sits to the public lattice, using the same 8
 * always-eligible bins ([SLSB_BASE_BINS], bins 32-39 — every valid `stegoStrength` uses at least
 * these) over a 3-frame sliding window (the smallest possible nightjar embedding, strength 4 with
 * an empty payload, is 2.75 frames), restricted to *informative* cells
 * (`magnitude > `[SLSB_INFORMATIVE_FLOOR], mirroring the codec's own `LOG_MAGNITUDE_FLOOR`) so a
 * floored, silent bin's constant distance doesn't dilute the statistic.
 *
 * **MFSK — keyed tones at the top of hearing.** Per 1024-sample frame, this detector computes the
 * median magnitude of a guard band around the 8 candidate tone bins ([MFSK_BASE_BIN]..
 * `+7`, ~19.7 kHz) and calls a frame "keyed" if any candidate bin exceeds that local floor by
 * [MFSK_DETECTION_MARGIN_DB]. `score` counts *distinct* keyed bit-patterns rather than just keyed
 * frames, so a steady pilot tone (always the same one pattern) reads very differently from genuine
 * byte-varying keying — see [mfskScore]'s KDoc. This never demodulates a codeword (no
 * Reed-Solomon, no byte recovery): counting which of 8 bins are "on" in a frame is one step short
 * of reading the byte that pattern encodes, and this detector deliberately stops there (spec's C5
 * "the detector never recovers a payload").
 *
 * ## Fusion
 *
 * `confidence = max(applicable technique scores)`, `flagged = confidence >= `[flagThreshold].
 * `estimatedPayloadBytes` is surfaced only when the *overall* result is flagged, and only ever
 * comes from the winning technique's own estimate (phase-inversion always computes one from the
 * residual-window count; spectrogram-LSB only when its own score already clears
 * [flagThreshold], via a maximum-likelihood prefix-changepoint search described on
 * [estimateSpectrogramLsbBytes]; MFSK's is always `null` — see its KDoc). `detail` is one line,
 * one clause per technique, surfaced verbatim to the technical screen and the humming jar's peek
 * report.
 *
 * ## Duplicated codec constants — a deliberate, closed-loop risk
 *
 * This file keeps **private copies** of the codec's public parameters (Δ, the informative-cell
 * floor, the eligible bin range, frame size, MFSK's tone/guard bins) rather than reaching into
 * [AudioStegoCarrier]'s `companion object` constants, because those constants are `private` there
 * (and that file is under concurrent review — hoisting them to `internal` is noted as v5 follow-up
 * work). A drifted copy would silently blind this detector, which is exactly why
 * [AudioStegDetectorTest]'s calibration test encodes through the **real** [AudioStegoCarrier]: any
 * drift between the two copies fails that test loudly instead of silently degrading detection.
 */
class AudioStegDetector : CovertDetector<WavFile.ParsedWav> {

    override val descriptor = ModuleDescriptor(
        id = ModuleId.AUDIO_STEGANALYSIS,
        displayName = "Audio Steganalysis (polarity / lattice / tone-band)",
        domain = CarrierDomain.AUDIO,
        role = ModuleRole.DETECTOR,
    )

    /**
     * Score at/above which [analyze] flags a clip. Measured against the real codec
     * ([AudioStegDetectorTest]): every one of the app's own stego clips (all three techniques,
     * both bundled covers, every spectrogram-LSB strength, every tested payload size) scores
     * >= 0.95 confidence; every clean cover, silence, steady-tone and seeded-noise control stays
     * well clear underneath. 0.85 matches [ImageSteganalysis]'s own threshold — this codebase's
     * established convention for "comfortable margin on both sides of the measured gap."
     */
    override val flagThreshold: Float = FLAG_THRESHOLD

    override fun analyze(sample: WavFile.ParsedWav): DetectionResult {
        if (sample.sampleRateHz != NightjarAcoustics.SAMPLE_RATE_HZ) {
            return DetectionResult(
                confidence = 0f,
                flagged = false,
                detail = "needs ${NightjarAcoustics.SAMPLE_RATE_HZ} hz pcm16, got " +
                    "${sample.sampleRateHz} — not analyzed",
            )
        }

        val channels = deinterleaveChannels(sample.samples, sample.numChannels)

        val piScore = if (sample.numChannels == 2) {
            phaseInversionScore(sample.samples)
        } else {
            TechniqueScore(
                technique = AudioStegoTechnique.PHASE_INVERSION,
                applicable = false,
                score = 0.0,
                estimatedEmbeddedBytes = null,
                evidence = "n/a (mono)",
            )
        }
        val slsbScore = channels.map { spectrogramLsbScore(it) }.maxByScoreOrNotApplicable(AudioStegoTechnique.SPECTROGRAM_LSB)
        val mfskScoreResult = channels.map { mfskScore(it) }.maxByScoreOrNotApplicable(AudioStegoTechnique.MFSK)

        val allScores = listOf(piScore, slsbScore, mfskScoreResult)
        val winning = allScores.filter { it.applicable }.maxByOrNull { it.score }
        val confidence = (winning?.score ?: 0.0).toFloat()
        val flagged = confidence >= flagThreshold

        return DetectionResult(
            confidence = confidence,
            flagged = flagged,
            estimatedPayloadBytes = if (flagged) winning?.estimatedEmbeddedBytes else null,
            detail = allScores.joinToString(" · ") { formatTechniqueDetail(it) },
        )
    }

    // --- Phase inversion ---

    /**
     * Scores [interleaved] L/R PCM16 for [AudioStegoTechnique.PHASE_INVERSION]-shaped anti-phase
     * content with a surviving residual (class KDoc). `anti` rewards strong negative correlation
     * (stego measures -0.999..-1.000); `presence` rewards a residual that didn't fully cancel to
     * silence (a plain polarity-flipped export, `L = -R` with nothing mixed in, cancels to exactly
     * zero and would otherwise score identically to real stego — see [StereoPolarity]'s KDoc for
     * why that's a documented false-positive class, not a gap in this formula). `score = anti *
     * presence`, so either signal alone (strong correlation on a silent difference, or a loud but
     * uncorrelated residual) scores near zero.
     *
     * Estimate: [StereoPolarity.residualWindowRms] windows at or above half the clip's own peak
     * residual, divided by 8 — nightjar's public rate is 1 bit per 10 ms window, and this includes
     * the 11-byte frame overhead, the same run-samples/8 convention [ImageSteganalysis] uses for
     * its own estimate. Computed unconditionally (unlike [spectrogramLsbScore]'s estimate, which
     * only fires once its own score already clears [flagThreshold]) — a residual-window count is a
     * meaningful number regardless of how confident the anti-phase call itself is.
     */
    internal fun phaseInversionScore(interleaved: ShortArray): TechniqueScore {
        val polarity = stereoPolarity(interleaved)
        val residuals = polarity.residualWindowRms
        if (residuals.isEmpty()) {
            return TechniqueScore(
                technique = AudioStegoTechnique.PHASE_INVERSION,
                applicable = true,
                score = 0.0,
                estimatedEmbeddedBytes = 0,
                evidence = "corr ${"%.4f".format(polarity.correlation)}, not enough signal for residual windows",
            )
        }

        val maxRms = residuals.max()
        val negatedCorrelation = -polarity.correlation
        val anti = ((negatedCorrelation - PI_ANTI_FLOOR) / PI_ANTI_SPAN).coerceIn(0.0, 1.0)
        val presence = ((maxRms - PI_PRESENCE_FLOOR) / PI_PRESENCE_SPAN).coerceIn(0.0, 1.0)
        val score = anti * presence

        val activeThreshold = 0.5 * maxRms
        val activeWindowCount = residuals.count { it >= activeThreshold }
        val estimatedBytes = if (maxRms > 0.0) activeWindowCount / BITS_PER_BYTE else 0

        return TechniqueScore(
            technique = AudioStegoTechnique.PHASE_INVERSION,
            applicable = true,
            score = score,
            estimatedEmbeddedBytes = estimatedBytes,
            evidence = "corr ${"%.4f".format(polarity.correlation)}, l+r residual ${Math.round(maxRms)} lsb " +
                "in $activeWindowCount/${residuals.size} 10ms windows",
        )
    }

    // --- Spectrogram-LSB ---

    /**
     * Scores [mono] for [AudioStegoTechnique.SPECTROGRAM_LSB]-shaped lattice snapping (class
     * KDoc). Not applicable when [mono] is shorter than [SLSB_WINDOW_FRAMES] frames, or when no
     * 3-frame window anywhere in the clip ever reaches [SLSB_MIN_INFORMATIVE_CELLS] informative
     * (`magnitude > `[SLSB_INFORMATIVE_FLOOR]`) cells among its `[SLSB_WINDOW_FRAMES]` * `
     * [SLSB_BASE_BINS] = 24 candidate cells (e.g. digital silence, where every bin floors).
     *
     * `D*` is the minimum, over every qualifying 3-frame window, of the mean per-cell distance to
     * the nearest lattice point (`d = |ln(magnitude)/Δ - round(ln(magnitude)/Δ)|`). `score =
     * clamp((`[SLSB_D_STAR_CEILING]` - D*) / `[SLSB_D_STAR_SPAN]`, 0, 1)` — smaller D* (spectrum
     * sitting closer to the public lattice) scores higher.
     */
    internal fun spectrogramLsbScore(mono: ShortArray): TechniqueScore {
        val numFrames = mono.size / SLSB_FRAME_SIZE
        if (numFrames < SLSB_WINDOW_FRAMES) {
            return notApplicable(AudioStegoTechnique.SPECTROGRAM_LSB, "n/a (not enough signal)")
        }

        // magnitudes[frame][binSlot], binSlot 0 until SLSB_MAX_BINS covering bins
        // SLSB_ELIGIBLE_BIN_START .. SLSB_ELIGIBLE_BIN_START + SLSB_MAX_BINS - 1 -- computed once
        // per frame and reused by both the D* search (first SLSB_BASE_BINS columns only) and the
        // estimate's wider B search below, one FFT pass per frame.
        val magnitudes = Array(numFrames) { frameIndex -> slsbFrameMagnitudes(mono, frameIndex) }

        var bestMeanD = Double.MAX_VALUE
        var bestWindowStart = -1
        for (start in 0..numFrames - SLSB_WINDOW_FRAMES) {
            var sumD = 0.0
            var informativeCount = 0
            for (frame in start until start + SLSB_WINDOW_FRAMES) {
                for (binSlot in 0 until SLSB_BASE_BINS) {
                    val magnitude = magnitudes[frame][binSlot]
                    if (magnitude > SLSB_INFORMATIVE_FLOOR) {
                        sumD += latticeDistance(magnitude)
                        informativeCount++
                    }
                }
            }
            if (informativeCount >= SLSB_MIN_INFORMATIVE_CELLS) {
                val meanD = sumD / informativeCount
                if (meanD < bestMeanD) {
                    bestMeanD = meanD
                    bestWindowStart = start
                }
            }
        }

        if (bestMeanD == Double.MAX_VALUE) {
            return notApplicable(AudioStegoTechnique.SPECTROGRAM_LSB, "n/a (not enough signal)")
        }

        val score = ((SLSB_D_STAR_CEILING - bestMeanD) / SLSB_D_STAR_SPAN).coerceIn(0.0, 1.0)
        val estimate = if (score >= FLAG_THRESHOLD) {
            estimateSpectrogramLsbBytes(magnitudes, numFrames, bestWindowStart)
        } else {
            null
        }

        return TechniqueScore(
            technique = AudioStegoTechnique.SPECTROGRAM_LSB,
            applicable = true,
            score = score,
            estimatedEmbeddedBytes = estimate,
            evidence = "lattice distance ${"%.3f".format(bestMeanD)}",
        )
    }

    /** FFT's one frame of [mono] at [frameIndex] and returns the magnitude of each of the
     *  [SLSB_MAX_BINS] eligible bins starting at [SLSB_ELIGIBLE_BIN_START]. */
    private fun slsbFrameMagnitudes(mono: ShortArray, frameIndex: Int): DoubleArray {
        val start = frameIndex * SLSB_FRAME_SIZE
        val re = DoubleArray(SLSB_FRAME_SIZE) { mono[start + it].toDouble() }
        val im = DoubleArray(SLSB_FRAME_SIZE)
        fft(re, im)
        return DoubleArray(SLSB_MAX_BINS) { binSlot ->
            val bin = SLSB_ELIGIBLE_BIN_START + binSlot
            sqrt(re[bin] * re[bin] + im[bin] * im[bin])
        }
    }

    /** `|ln(magnitude)/Δ - round(ln(magnitude)/Δ)|` — distance from the QIM log-magnitude lattice
     *  [AudioStegoCarrier.embedBitInBin] snaps embedded bins onto. Caller guarantees `magnitude >
     *  `[SLSB_INFORMATIVE_FLOOR]`, so `ln` is always well-defined and away from the codec's own
     *  flooring behavior. */
    private fun latticeDistance(magnitude: Double): Double {
        val idx = ln(magnitude) / SLSB_QUANTIZATION_STEP
        return abs(idx - round(idx))
    }

    /**
     * Payload-size estimate, run only once [spectrogramLsbScore] has already decided this clip is
     * flagged on its own. Two stages, both anchored on [bestWindowStart] -- the 3-frame,
     * [SLSB_BASE_BINS]-bin window [spectrogramLsbScore] already found has the *lowest* mean
     * lattice distance, so it is guaranteed to sit somewhere inside the true embedded span, but
     * not necessarily at that span's start: within a real embed, individual frames' snap
     * precision varies with local signal magnitude (class KDoc: recomputed distance "sits within
     * ~0.002-0.06 of an integer" -- a range, not a constant), so the single tightest 3-frame
     * window can land anywhere from the first to the last embedded frame. An estimate that only
     * ever walks *forward* from that window (an earlier version of this function did exactly
     * that) silently truncates whenever the window lands late, which is exactly the systematic
     * under-estimate [AudioStegDetectorTest] caught.
     *
     * 1. **Recover the strength.** For each 8-bin block `b` in `0..3` (bins `32+8b .. 32+8b+7`),
     *    the mean lattice distance over [bestWindowStart]'s own 3 frames decides whether that
     *    block is part of the real embedding (`mean d < `[SNAP_D_THRESHOLD]`, needs >=`
     *    [SLSB_MIN_BLOCK_INFORMATIVE_CELLS]` informative cells). `blocks` stops at the first block
     *    that fails -- this recovers `stegoStrength` exactly, since [AudioStegoCarrier] always
     *    embeds a *contiguous* `stegoStrength * 8`-bin prefix of the eligible range.
     * 2. **Recover the span.** Using the just-recovered `blocks * `[SLSB_BASE_BINS]` bins (the
     *    real bit-per-frame count, not just the 8-bin search window), walk outward in *both*
     *    directions from `[bestWindowStart, bestWindowStart + `[SLSB_WINDOW_FRAMES]`)` one frame
     *    at a time via [frameSnapVerdict]: a [SnapVerdict.SNAPPED] frame extends the span, a
     *    [SnapVerdict.CLEAN] frame stops it, and up to [SLSB_MAX_INCONCLUSIVE_GAP] consecutive
     *    [SnapVerdict.INCONCLUSIVE] frames (too little informative signal to say either way -- a
     *    real risk on SPOKEN_WORD's own burst/gap cadence) are tolerated without stopping the
     *    walk, on the working assumption that a brief low-energy dip mid-embed is more likely than
     *    a coincidentally snapped frame immediately past a real boundary.
     *
     * `estimatedEmbeddedBytes = (spanEnd - spanStart) * blocks` (the `* `[SLSB_BASE_BINS]` / `
     * [SLSB_BASE_BINS]` that would convert a bit count to bytes cancels exactly, since `blocks *`
     * [SLSB_BASE_BINS]` bits-per-frame `/ 8 == blocks`) -- includes the 11-byte frame overhead,
     * same as every other estimate in this file.
     *
     * **Measured accuracy** ([AudioStegDetectorTest.spectrogramLsbFlagsEveryCoverStrengthAndPayloadSize],
     * both bundled covers x strength 1-4 x payload {0, 1, 5, 20, max}): typically exact or off by
     * 1 byte; **worst measured error is 4 bytes**, always at a span boundary where per-frame snap
     * precision genuinely degrades rather than in the strength recovery or the walk's own logic --
     * either the cover's own fade envelope pushes a boundary frame's magnitude down near
     * [SLSB_INFORMATIVE_FLOOR] (16-bit-rounding noise on the recomputed lattice distance then
     * swamps the real signal there), or the embed's own last frame is only partially filled (fewer
     * than `blocks * `[SLSB_BASE_BINS]` bins actually carry payload bits, diluting that frame's
     * mean distance). A UI surfacing this number should present it as "about N bytes," not exact.
     */
    private fun estimateSpectrogramLsbBytes(magnitudes: Array<DoubleArray>, numFrames: Int, bestWindowStart: Int): Int {
        var blocks = 0
        for (block in 0 until SLSB_MAX_BINS / SLSB_BASE_BINS) {
            var sumD = 0.0
            var informativeCount = 0
            for (frame in bestWindowStart until bestWindowStart + SLSB_WINDOW_FRAMES) {
                for (binSlot in block * SLSB_BASE_BINS until (block + 1) * SLSB_BASE_BINS) {
                    val magnitude = magnitudes[frame][binSlot]
                    if (magnitude > SLSB_INFORMATIVE_FLOOR) {
                        sumD += latticeDistance(magnitude)
                        informativeCount++
                    }
                }
            }
            if (informativeCount >= SLSB_MIN_BLOCK_INFORMATIVE_CELLS && sumD / informativeCount < SNAP_D_THRESHOLD) {
                blocks++
            } else {
                break
            }
        }
        if (blocks == 0) return 0 // degenerate: bestWindowStart's own block0 didn't reconfirm here

        val bitsPerFrame = blocks * SLSB_BASE_BINS

        var spanStart = bestWindowStart
        var gap = 0
        while (spanStart > 0) {
            when (frameSnapVerdict(magnitudes, spanStart - 1, bitsPerFrame)) {
                SnapVerdict.CLEAN -> break
                SnapVerdict.SNAPPED -> { spanStart--; gap = 0 }
                SnapVerdict.INCONCLUSIVE -> {
                    if (gap >= SLSB_MAX_INCONCLUSIVE_GAP) break
                    spanStart--
                    gap++
                }
            }
        }

        var spanEnd = bestWindowStart + SLSB_WINDOW_FRAMES
        gap = 0
        while (spanEnd < numFrames) {
            when (frameSnapVerdict(magnitudes, spanEnd, bitsPerFrame)) {
                SnapVerdict.CLEAN -> break
                SnapVerdict.SNAPPED -> { spanEnd++; gap = 0 }
                SnapVerdict.INCONCLUSIVE -> {
                    if (gap >= SLSB_MAX_INCONCLUSIVE_GAP) break
                    spanEnd++
                    gap++
                }
            }
        }

        return (spanEnd - spanStart) * blocks
    }

    private enum class SnapVerdict { SNAPPED, CLEAN, INCONCLUSIVE }

    /** Classifies a single frame's first [bitsPerFrame] eligible bins as snapped, clean, or
     *  inconclusive (too few informative cells to say either way) -- see
     *  [estimateSpectrogramLsbBytes]'s KDoc for how this is used to walk the span outward. */
    private fun frameSnapVerdict(magnitudes: Array<DoubleArray>, frame: Int, bitsPerFrame: Int): SnapVerdict {
        var sumD = 0.0
        var informativeCount = 0
        for (binSlot in 0 until bitsPerFrame) {
            val magnitude = magnitudes[frame][binSlot]
            if (magnitude > SLSB_INFORMATIVE_FLOOR) {
                sumD += latticeDistance(magnitude)
                informativeCount++
            }
        }
        val required = maxOf(SLSB_MIN_FRAME_INFORMATIVE_CELLS, bitsPerFrame / 2)
        if (informativeCount < required) return SnapVerdict.INCONCLUSIVE
        return if (sumD / informativeCount < SNAP_D_THRESHOLD) SnapVerdict.SNAPPED else SnapVerdict.CLEAN
    }

    // --- MFSK ---

    /**
     * Scores [mono] for [AudioStegoTechnique.MFSK]-shaped tone keying (class KDoc). Per
     * 1024-sample frame, reads each of the 8 candidate tone bins ([MFSK_BASE_BIN]..`+7`) as
     * "active" when its magnitude exceeds the surrounding guard band's median by
     * [MFSK_DETECTION_MARGIN_DB] — mirroring [AudioStegoCarrier]'s own `demodulateMfskBlock`
     * margin-over-median technique, independently re-derived here since that method is private to
     * the codec. A frame with any active bin is "keyed"; its 8-bit active/inactive pattern is
     * recorded.
     *
     * `score = 0` when fewer than [MFSK_MIN_KEYED_FRAMES] frames are keyed (guards against a
     * handful of spurious frames from noise — a genuine keyed bin clearing 15 dB above its own
     * local noise floor by chance has probability on the order of 1e-10 per bin, but a clip has
     * many frames * bins, so a small floor still matters); otherwise `score =
     * min(1, distinctPatterns / 8)`. Counting *distinct* patterns rather than raw keyed-frame
     * count is what separates real MFSK keying (every codeword byte differs, so a real transmission
     * covers most of the 256-pattern space quickly) from a steady interfering tone at the same
     * frequency (always the same one pattern, capped at `1/8 = 0.125`, comfortably under
     * [flagThreshold]).
     *
     * Estimate: always `null`. Length is technically inferable from where the keying stops, but
     * that means reading per-frame tone patterns one step short of demodulating the codeword
     * outright — this detector deliberately stops at "keyed or not," never at "what does the
     * pattern say" (class KDoc, spec's C5).
     */
    internal fun mfskScore(mono: ShortArray): TechniqueScore {
        val numFrames = mono.size / MFSK_FRAME_SIZE
        if (numFrames == 0) {
            return TechniqueScore(
                technique = AudioStegoTechnique.MFSK,
                applicable = true,
                score = 0.0,
                estimatedEmbeddedBytes = null,
                evidence = "0 keyed frames",
            )
        }

        var keyedFrameCount = 0
        val distinctPatterns = HashSet<Int>()
        for (frameIndex in 0 until numFrames) {
            val start = frameIndex * MFSK_FRAME_SIZE
            val re = DoubleArray(MFSK_FRAME_SIZE) { mono[start + it].toDouble() }
            val im = DoubleArray(MFSK_FRAME_SIZE)
            fft(re, im)

            val toneMagnitudes = DoubleArray(MFSK_TONE_COUNT) { bit -> magnitudeAt(re, im, MFSK_BASE_BIN + bit) }
            val guardMagnitudes = ArrayList<Double>(2 * MFSK_GUARD_BINS)
            for (bin in (MFSK_BASE_BIN - MFSK_GUARD_BINS) until MFSK_BASE_BIN) {
                guardMagnitudes.add(magnitudeAt(re, im, bin))
            }
            for (bin in (MFSK_BASE_BIN + MFSK_TONE_COUNT) until (MFSK_BASE_BIN + MFSK_TONE_COUNT + MFSK_GUARD_BINS)) {
                guardMagnitudes.add(magnitudeAt(re, im, bin))
            }
            val floorDb = magnitudeDb(median(guardMagnitudes))

            var pattern = 0
            var anyActive = false
            for (bit in 0 until MFSK_TONE_COUNT) {
                val toneDb = magnitudeDb(toneMagnitudes[bit])
                if (toneDb - floorDb >= MFSK_DETECTION_MARGIN_DB) {
                    pattern = pattern or (1 shl (MFSK_TONE_COUNT - 1 - bit))
                    anyActive = true
                }
            }
            if (anyActive) {
                keyedFrameCount++
                distinctPatterns.add(pattern)
            }
        }

        val score = if (keyedFrameCount < MFSK_MIN_KEYED_FRAMES) {
            0.0
        } else {
            min(1.0, distinctPatterns.size.toDouble() / MFSK_TONE_COUNT)
        }
        val evidence = if (keyedFrameCount == 0) {
            "0 keyed frames"
        } else {
            "$keyedFrameCount keyed frame${if (keyedFrameCount == 1) "" else "s"}, " +
                "${distinctPatterns.size} distinct pattern${if (distinctPatterns.size == 1) "" else "s"}"
        }
        return TechniqueScore(
            technique = AudioStegoTechnique.MFSK,
            applicable = true,
            score = score,
            estimatedEmbeddedBytes = null,
            evidence = evidence,
        )
    }

    private fun magnitudeAt(re: DoubleArray, im: DoubleArray, bin: Int): Double =
        sqrt(re[bin] * re[bin] + im[bin] * im[bin])

    /** `20*log10(magnitude)`, floored so an exactly-silent bin doesn't produce `-Infinity` — same
     *  floor [AudioStegoCarrier.magnitudeDb] uses, independently re-derived (that method is
     *  private to the codec). */
    private fun magnitudeDb(magnitude: Double): Double = 20.0 * log10(max(magnitude, 1e-9))

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    // --- Shared helpers ---

    private fun notApplicable(technique: AudioStegoTechnique, evidence: String) = TechniqueScore(
        technique = technique,
        applicable = false,
        score = 0.0,
        estimatedEmbeddedBytes = null,
        evidence = evidence,
    )

    /** Picks the louder (higher-score) channel's result for a per-channel-then-max technique
     *  ([spectrogramLsbScore]/[mfskScore] on stereo input, design-v5.md §2.3). Never empty in
     *  practice ([deinterleaveChannels] always returns at least one channel), but falls back to a
     *  "not applicable" placeholder rather than throwing if it somehow were. */
    private fun List<TechniqueScore>.maxByScoreOrNotApplicable(technique: AudioStegoTechnique): TechniqueScore =
        maxByOrNull { it.score } ?: notApplicable(technique, "n/a (no channels)")

    private fun formatTechniqueDetail(score: TechniqueScore): String {
        val label = techniqueLabel(score.technique)
        return if (score.applicable) {
            "$label ${"%.2f".format(score.score)} (${score.evidence})"
        } else {
            "$label ${score.evidence}"
        }
    }

    private fun techniqueLabel(technique: AudioStegoTechnique): String = when (technique) {
        AudioStegoTechnique.PHASE_INVERSION -> "phase-inversion"
        AudioStegoTechnique.SPECTROGRAM_LSB -> "spectrogram-lsb"
        AudioStegoTechnique.MFSK -> "mfsk"
    }

    /** Splits [samples] into one `ShortArray` per channel (`samples[frame * numChannels + ch]`).
     *  `numChannels <= 1` returns [samples] itself as the sole channel, unchanged. */
    private fun deinterleaveChannels(samples: ShortArray, numChannels: Int): List<ShortArray> {
        if (numChannels <= 1) return listOf(samples)
        val frames = samples.size / numChannels
        return (0 until numChannels).map { channel ->
            ShortArray(frames) { i -> samples[i * numChannels + channel] }
        }
    }

    private companion object {
        const val FLAG_THRESHOLD = 0.85f
        const val BITS_PER_BYTE = 8

        // --- Phase-inversion scoring constants (design-v5.md §2.3) ---
        const val PI_ANTI_FLOOR = 0.90
        const val PI_ANTI_SPAN = 0.09
        const val PI_PRESENCE_FLOOR = 2.0
        const val PI_PRESENCE_SPAN = 6.0

        // --- Spectrogram-LSB scoring constants -- private copies of AudioStegoCarrier's own
        // (that file's companion constants are private; see the class KDoc's "Duplicated codec
        // constants" section for why, and AudioStegDetectorTest's drift canary for the guard). ---
        const val SLSB_FRAME_SIZE = 1024
        const val SLSB_ELIGIBLE_BIN_START = 32
        const val SLSB_BASE_BINS = 8
        const val SLSB_MAX_BINS = 32
        const val SLSB_WINDOW_FRAMES = 3
        const val SLSB_MIN_INFORMATIVE_CELLS = 12
        const val SLSB_INFORMATIVE_FLOOR = 1000.0
        const val SLSB_QUANTIZATION_STEP = 0.12
        const val SLSB_D_STAR_CEILING = 0.19
        const val SLSB_D_STAR_SPAN = 0.105
        const val SNAP_D_THRESHOLD = 0.10

        // Estimate-only knobs (estimateSpectrogramLsbBytes / frameSnapVerdict).
        /** Minimum informative cells, within [bestWindowStart]'s own 3 frames, before a block's
         *  mean distance is trusted for strength recovery (`3 frames * 8 bins = 24` max). */
        const val SLSB_MIN_BLOCK_INFORMATIVE_CELLS = 12

        /** Minimum informative cells within a SINGLE frame (out of up to [SLSB_MAX_BINS]) before
         *  [frameSnapVerdict] calls it snapped/clean rather than inconclusive. */
        const val SLSB_MIN_FRAME_INFORMATIVE_CELLS = 4

        /** Consecutive inconclusive frames [estimateSpectrogramLsbBytes]'s span walk tolerates
         *  before giving up and stopping -- covers SPOKEN_WORD's own 40-90ms burst/gap cadence
         *  (a gap this short is at most 4-5 frames at 21.3ms/frame) without letting the walk run
         *  away across a long stretch of genuinely clean, silent, or otherwise inconclusive audio. */
        const val SLSB_MAX_INCONCLUSIVE_GAP = 5

        // --- MFSK scoring constants -- private copies of AudioStegoCarrier's own. ---
        const val MFSK_FRAME_SIZE = 1024
        const val MFSK_BASE_BIN = 420
        const val MFSK_TONE_COUNT = 8
        const val MFSK_GUARD_BINS = 16
        const val MFSK_DETECTION_MARGIN_DB = 15.0
        const val MFSK_MIN_KEYED_FRAMES = 3
    }
}

/**
 * One technique's contribution to an [AudioStegDetector.analyze] call — internal, exposed only
 * for [AudioStegDetector]'s own JVM tests (the same visibility pattern
 * [ImageSteganalysis.chiSquarePValueForWindow] uses).
 *
 * @param applicable false when this technique couldn't be evaluated at all (mono input for
 *   phase-inversion; too little signal for spectrogram-LSB) -- [score] is always 0.0 in that case
 *   and never contributes to [AudioStegDetector]'s fused confidence.
 * @param score this technique's own [0,1] confidence, independent of [AudioStegDetector.flagThreshold].
 * @param estimatedEmbeddedBytes includes the 11-byte frame overhead; always `null` for MFSK
 *   (class KDoc's C5 note), and for spectrogram-LSB only computed once its own [score] already
 *   clears [AudioStegDetector.flagThreshold].
 * @param evidence lower-case fragment folded into [DetectionResult.detail].
 */
internal data class TechniqueScore(
    val technique: AudioStegoTechnique,
    val applicable: Boolean,
    val score: Double,
    val estimatedEmbeddedBytes: Int?,
    val evidence: String,
)
