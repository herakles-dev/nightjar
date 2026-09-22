package dev.herakles.nightjar

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Module 5 — passive acoustic anomaly detector.
 *
 * Implements [CovertDetector] over [PcmAudio].
 * This is a ONE-WAY analyzer: it never demodulates or decodes a payload; it only scores
 * whether nightjar's own Module 3 FSK tone signature (or a spoof of it) is present in a
 * captured PCM stream — the passive, defensive posture: "the app flags the
 * very channel it transmits on".
 *
 * Algorithm:
 *  1. Run a 1024-point FFT at 48 kHz ([NightjarAcoustics.FFT_SIZE] /
 *     [NightjarAcoustics.SAMPLE_RATE_HZ]) over each analysis frame of the mic stream.
 *  2. Maintain a per-bin running noise floor — the median magnitude (dB) over the last
 *     ~1 s of frames — tracked only for the bins that fall inside either protocol's
 *     tone-grid band ([NightjarAcoustics.Protocol.baseBin]..[NightjarAcoustics.Protocol.topBin]),
 *     since those are the only bins the flag condition ever inspects.
 *  3. A frame is "hot" when >= [NightjarAcoustics.DETECTOR_MIN_TONES] on-grid tones, within
 *     either the AUDIBLE band or the NEAR_ULTRASONIC band, simultaneously exceed that bin's
 *     running noise floor by >= [NightjarAcoustics.DETECTOR_TONE_MARGIN_DB] dB.
 *  4. A frame "meets the flag condition" once the *run* of consecutive hot frames it belongs
 *     to reaches >= [NightjarAcoustics.DETECTOR_SUSTAIN_FRAMES] frames (~100 ms) — the
 *     START-marker / steady-symbol signature described above. The first
 *     `DETECTOR_SUSTAIN_FRAMES - 1` frames of a hot run do not (yet) qualify; once the run
 *     is long enough, every frame in it (including the ramp-up) qualifies.
 *  5. `confidence` = fraction of the last [NightjarAcoustics.DETECTOR_CONFIDENCE_WINDOW_FRAMES]
 *     analysis frames that met the flag condition.
 *
 * Stateful: one instance holds the per-bin noise
 * floor history, the raw hot/cold frame history (needed to evaluate run length), and a small
 * leftover-sample buffer across successive [analyze] calls. The streaming capture loop
 * (transport layer) is responsible for feeding successive chunks of the mic stream; this
 * class tolerates chunk sizes that are not exact multiples of [NightjarAcoustics.FRAME_SAMPLES]
 * by buffering the remainder for the next call.
 *
 * No Android imports: [PcmAudio] is a plain `ShortArray`, so this class is a pure JVM analyzer
 * over in-memory buffers (the codec vs. transport scope boundary), runnable in
 * a unit test with no device, speaker, or mic in the loop.
 */
class AcousticDetector : CovertDetector<PcmAudio> {

    override val descriptor: ModuleDescriptor = ModuleDescriptor(
        id = ModuleId.ACOUSTIC_DETECTOR,
        displayName = "Acoustic Anomaly Detector",
        domain = CarrierDomain.AUDIO,
        role = ModuleRole.DETECTOR,
    )

    /**
     * confidence >= this => [DetectionResult.flagged] = true. An explicit, tunable
     * false-positive/false-negative knob (Module Interface §3), set to StegExpose's own
     * documented default detection threshold (the steganography literature) — the same interface
     * contract that names StegExpose's threshold as the analog this field unifies with the
     * §9 acoustic rule.
     */
    override val flagThreshold: Float = FLAG_THRESHOLD

    // --- Tracked bins: union of both protocols' tone-grid bands ---

    private data class Band(val loBin: Int, val hiBin: Int)

    private val bands: List<Band> = NightjarAcoustics.Protocol.entries.map { proto ->
        Band(loBin = proto.baseBin, hiBin = proto.topBin)
    }

    /** All bins any band cares about, ascending, deduplicated. */
    private val trackedBins: IntArray = bands
        .flatMap { band -> band.loBin..band.hiBin }
        .distinct()
        .sorted()
        .toIntArray()

    // --- Running state (persists across `analyze` calls) ---

    /** Recent per-bin magnitude-dB history (oldest first), used to derive the noise-floor median. */
    private val noiseFloorHistory: Map<Int, ArrayDeque<Double>> =
        trackedBins.associateWith { ArrayDeque() }

    /** How many frames of per-bin history to retain: ~1 s. */
    private val noiseFloorWindowFrames: Int = max(
        1,
        round(NightjarAcoustics.SAMPLE_RATE_HZ.toDouble() / NightjarAcoustics.FRAME_SAMPLES).toInt(),
    )

    /**
     * Raw hot/cold history (oldest first) for as many trailing frames as are needed to compute
     * run lengths for every frame inside the confidence window: window size + sustain - 1.
     */
    private val rawHotHistory: ArrayDeque<Boolean> = ArrayDeque()
    private val rawHotHistoryCapacity: Int =
        NightjarAcoustics.DETECTOR_CONFIDENCE_WINDOW_FRAMES + NightjarAcoustics.DETECTOR_SUSTAIN_FRAMES - 1

    /** Leftover samples not yet consumed into a full [NightjarAcoustics.FRAME_SAMPLES]-sized frame. */
    private var pending: ShortArray = ShortArray(0)

    private val hannWindow: DoubleArray = DoubleArray(NightjarAcoustics.FRAME_SAMPLES) { n ->
        0.5 - 0.5 * cos(2.0 * PI * n / (NightjarAcoustics.FRAME_SAMPLES - 1))
    }

    private var lastResult: DetectionResult = DetectionResult(confidence = 0f, flagged = false)

    override fun analyze(sample: PcmAudio): DetectionResult {
        val combined = pending + sample
        val frameSize = NightjarAcoustics.FRAME_SAMPLES
        var offset = 0

        while (combined.size - offset >= frameSize) {
            val frame = combined.copyOfRange(offset, offset + frameSize)
            lastResult = processFrame(frame)
            offset += frameSize
        }

        pending = if (offset < combined.size) combined.copyOfRange(offset, combined.size) else ShortArray(0)
        return lastResult
    }

    private fun processFrame(frame: ShortArray): DetectionResult {
        val magnitudesDb = magnitudeDbAtTrackedBins(frame)

        // Which tracked bins exceed THEIR OWN running noise floor by the tone margin, this frame?
        val hotBins = trackedBins.filter { bin ->
            val history = noiseFloorHistory.getValue(bin)
            // No history yet for this bin => nothing to compare against; treat this frame's own
            // level as the floor (delta 0) rather than an arbitrary constant, so a cold-start
            // detector can never spuriously flag before it has any real baseline.
            val floor = if (history.isEmpty()) magnitudesDb.getValue(bin) else medianOf(history)
            magnitudesDb.getValue(bin) - floor >= NightjarAcoustics.DETECTOR_TONE_MARGIN_DB
        }.toSet()

        // >= DETECTOR_MIN_TONES tones in EITHER single band (not summed
        // across bands).
        val bandHot = bands.any { band ->
            hotBins.count { bin -> bin in band.loBin..band.hiBin } >= NightjarAcoustics.DETECTOR_MIN_TONES
        }

        // Update the noise-floor history AFTER this frame's flag decision, so the floor reflects
        // *prior* ambient level rather than leaking the current frame's own energy into its own
        // threshold check.
        trackedBins.forEach { bin ->
            val history = noiseFloorHistory.getValue(bin)
            history.addLast(magnitudesDb.getValue(bin))
            while (history.size > noiseFloorWindowFrames) history.removeFirst()
        }

        rawHotHistory.addLast(bandHot)
        while (rawHotHistory.size > rawHotHistoryCapacity) rawHotHistory.removeFirst()

        val (confidence, sustainedNow) = confidenceAndCurrentSustain()
        val flagged = confidence >= flagThreshold
        val detail = if (sustainedNow) {
            "sustained tone-grid energy: ${hotBins.size} on-grid bin(s) >= ${NightjarAcoustics.DETECTOR_TONE_MARGIN_DB} dB over floor"
        } else {
            null
        }

        return DetectionResult(
            confidence = confidence,
            flagged = flagged,
            estimatedPayloadBytes = null,
            detail = detail,
        )
    }

    /**
     * Fraction of the last [NightjarAcoustics.DETECTOR_CONFIDENCE_WINDOW_FRAMES] frames whose
     * hot run reached [NightjarAcoustics.DETECTOR_SUSTAIN_FRAMES], plus whether the *most recent*
     * frame itself currently qualifies (for the [DetectionResult.detail] message).
     */
    private fun confidenceAndCurrentSustain(): Pair<Float, Boolean> {
        val history = rawHotHistory
        val n = history.size
        if (n == 0) return 0f to false

        var runLen = 0
        val metFlagCondition = BooleanArray(n)
        for (i in 0 until n) {
            runLen = if (history[i]) runLen + 1 else 0
            metFlagCondition[i] = runLen >= NightjarAcoustics.DETECTOR_SUSTAIN_FRAMES
        }

        val windowSize = minOf(n, NightjarAcoustics.DETECTOR_CONFIDENCE_WINDOW_FRAMES)
        val windowStart = n - windowSize
        var hits = 0
        for (i in windowStart until n) if (metFlagCondition[i]) hits++

        val confidence = hits.toFloat() / windowSize.toFloat()
        return confidence to metFlagCondition[n - 1]
    }

    private fun medianOf(values: Collection<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    /** Hann-windowed FFT magnitude in dB, computed only for [trackedBins] (the two protocol bands). */
    private fun magnitudeDbAtTrackedBins(frame: ShortArray): Map<Int, Double> {
        val n = frame.size
        val re = DoubleArray(n) { i -> (frame[i].toDouble() / Short.MAX_VALUE) * hannWindow[i] }
        val im = DoubleArray(n)
        fft(re, im)

        val out = HashMap<Int, Double>(trackedBins.size)
        trackedBins.forEach { bin ->
            val magnitude = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
            out[bin] = 20.0 * log10(max(magnitude, MIN_MAGNITUDE))
        }
        return out
    }

    private companion object {
        /** StegExpose's own documented default detection threshold (the steganography literature). */
        const val FLAG_THRESHOLD = 0.2f

        /** Floor under log10 to avoid -Infinity dB for a silent bin. */
        const val MIN_MAGNITUDE = 1e-9
    }
}
