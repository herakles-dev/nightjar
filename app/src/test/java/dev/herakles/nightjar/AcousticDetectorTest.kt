package dev.herakles.nightjar

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rate-measurement verification for the Module 5 acoustic detector.
 *
 * [AcousticDetector] was previously verified only via an isolated scratch JVM driver,
 * not a committed Gradle test. This file is the committed replacement: it measures actual
 * true-positive rate on signal-present captures -- real [AcousticCarrier.encode] output built
 * from the documented DSP parameters in [NightjarAcoustics], with synthetic ambient noise mixed
 * in -- across a payload-size x noise-condition sweep, and the false-positive rate on
 * signal-absent (pure noise / digital silence) captures across the same noise conditions.
 * Both are reported as concrete measured numbers via println (captured in the Gradle test
 * report system-out) and asserted where the expected behavior is a real acceptance criterion,
 * not tuned to force a pass on a condition documented as a boundary (NEAR_THRESHOLD).
 *
 * All noise is synthetic uniform white noise (kotlin.random.Random over the range -amp to amp),
 * fixed seeds throughout for deterministic, reproducible runs -- no real microphone or speaker
 * in the loop, matching the pure-codec-buffer methodology also used by AcousticCarrierTest.
 */
class AcousticDetectorTest {

    // -----------------------------------------------------------------
    // Sweep parameters
    // -----------------------------------------------------------------

    /** Payload sizes swept: small, medium, and the architecture max payload ceiling (1024 B). */
    private val payloadSizes = listOf("SMALL" to 16, "MEDIUM" to 256, "MAX" to NightjarAcoustics.MAX_PAYLOAD_BYTES)

    /**
     * Synthetic ambient-noise amplitudes (raw int16 PCM units; full scale = 32767).
     * QUIET and MODERATE model realistic ambient recording conditions (a quiet room, or a room
     * with background chatter or a fan). NEAR_THRESHOLD is a deliberately loud stress condition,
     * sized to approach the per-tone amplitude budget of the tone segments themselves
     * (TONE_AMPLITUDE=27847 shared across up to 6 simultaneous data-symbol tones, about 4641 per
     * tone), to characterize where DETECTOR_TONE_MARGIN_DB=15dB starts to erode -- not a level
     * realistic ambient noise reaches.
     */
    private val noiseLevels = listOf("QUIET" to 250.0, "MODERATE" to 2500.0, "NEAR_THRESHOLD" to 9000.0)

    private val trialsPerSize = mapOf("SMALL" to 6, "MEDIUM" to 4, "MAX" to 2)

    /** About 1s of ambient noise fed before the tone phase, to build a realistic noise floor baseline. */
    private val ambientWarmupFrames = 50

    // -----------------------------------------------------------------
    // True-positive sweep: real AcousticCarrier.encode() output, noise mixed in
    // -----------------------------------------------------------------

    @Test
    fun truePositiveRateSweepAcrossPayloadSizeAndNoiseCondition() {
        val rows = mutableListOf<String>()
        val tpRates = mutableMapOf<Pair<String, String>, Double>()

        for ((sizeName, payloadBytes) in payloadSizes) {
            val trials = trialsPerSize.getValue(sizeName)
            for ((noiseName, noiseAmp) in noiseLevels) {
                var flaggedCount = 0
                var ambientFlaggedCount = 0
                var confidenceSum = 0.0
                for (t in 0 until trials) {
                    val seed = seedFor(sizeName, noiseName, t)
                    val payload = randomPayload(seed, payloadBytes)
                    val (toneResult, ambientResult) = runTruePositiveTrial(payload, noiseAmp, seed)
                    if (toneResult.everFlagged) flaggedCount++
                    if (ambientResult.everFlagged) ambientFlaggedCount++
                    confidenceSum += toneResult.maxConfidence
                }
                val tpRate = flaggedCount.toDouble() / trials
                val meanMaxConfidence = confidenceSum / trials
                tpRates[sizeName to noiseName] = tpRate
                rows += "%-6s payload=%5dB noise=%-14s amp=%7.0f trials=%d flagged=%d TP_rate=%.2f meanMaxConfidence=%.3f ambientFalsePositives=%d/%d"
                    .format(sizeName, payloadBytes, noiseName, noiseAmp, trials, flaggedCount, tpRate, meanMaxConfidence, ambientFlaggedCount, trials)
            }
        }

        println("\n=== AcousticDetector true-positive rate sweep ===")
        rows.forEach(::println)

        for ((sizeName, _) in payloadSizes) {
            for (noiseName in listOf("QUIET", "MODERATE")) {
                val rate = tpRates.getValue(sizeName to noiseName)
                assertTrue(
                    "expected TP rate >= 0.8 for " + sizeName + " payload under " + noiseName + " ambient noise, measured " + rate,
                    rate >= 0.8,
                )
            }
        }

        for ((sizeName, _) in payloadSizes) {
            val nearRate = tpRates.getValue(sizeName to "NEAR_THRESHOLD")
            println("NEAR_THRESHOLD TP rate for " + sizeName + " payload: " + nearRate + " (recorded, not gated)")
        }
    }

    // -----------------------------------------------------------------
    // False-positive sweep: pure noise/silence, no transmission at all
    // -----------------------------------------------------------------

    @Test
    fun falsePositiveRateSweepAcrossNoiseConditionsAndSilence() {
        val trials = 8
        val framesPerTrial = 120
        val fpRates = mutableMapOf<String, Double>()
        val rows = mutableListOf<String>()

        for ((noiseName, noiseAmp) in noiseLevels) {
            var flaggedCount = 0
            var maxConfidenceOverall = 0f
            for (t in 0 until trials) {
                val seed = seedFor("FP", noiseName, t)
                val detector = AcousticDetector()
                val pcm = whiteNoise(framesPerTrial, noiseAmp, seed)
                val results = feedPerFrame(detector, pcm)
                val trial = toTrialResult(results)
                if (trial.everFlagged) flaggedCount++
                if (trial.maxConfidence > maxConfidenceOverall) maxConfidenceOverall = trial.maxConfidence
            }
            val fpRate = flaggedCount.toDouble() / trials
            fpRates[noiseName] = fpRate
            rows += "noise=%-14s amp=%7.0f trials=%d flagged=%d FP_rate=%.2f maxConfidenceObserved=%.3f"
                .format(noiseName, noiseAmp, trials, flaggedCount, fpRate, maxConfidenceOverall)
        }

        println("\n=== AcousticDetector false-positive rate sweep ===")
        rows.forEach(::println)

        val silenceDetector = AcousticDetector()
        val silenceResults = feedPerFrame(silenceDetector, silence(frames = 80))
        assertFalse("expected pure digital silence to never be flagged", silenceResults.any { it.flagged })
        assertTrue("expected pure digital silence to have zero confidence throughout", silenceResults.all { it.confidence == 0f })

        assertEquals(
            "expected zero false positives under QUIET ambient noise, measured " + fpRates.getValue("QUIET"),
            0.0, fpRates.getValue("QUIET"), 0.0,
        )
        assertEquals(
            "expected zero false positives under MODERATE ambient noise, measured " + fpRates.getValue("MODERATE"),
            0.0, fpRates.getValue("MODERATE"), 0.0,
        )

        println("NEAR_THRESHOLD false-positive rate: " + fpRates.getValue("NEAR_THRESHOLD") + " (recorded, not gated)")
    }

    // -----------------------------------------------------------------
    // Trial machinery
    // -----------------------------------------------------------------

    private data class TrialResult(
        val everFlagged: Boolean,
        val maxConfidence: Float,
        val finalConfidence: Float,
        val finalFlagged: Boolean,
    )

    private fun toTrialResult(results: List<DetectionResult>): TrialResult {
        if (results.isEmpty()) return TrialResult(everFlagged = false, maxConfidence = 0f, finalConfidence = 0f, finalFlagged = false)
        return TrialResult(
            everFlagged = results.any { it.flagged },
            maxConfidence = results.maxOf { it.confidence },
            finalConfidence = results.last().confidence,
            finalFlagged = results.last().flagged,
        )
    }

    private fun runTruePositiveTrial(payload: ByteArray, noiseAmp: Double, seed: Long): Pair<TrialResult, TrialResult> {
        val carrier = AcousticCarrier()
        val toneOnly = carrier.encode(payload)
        val noisyTone = mixNoise(toneOnly, noiseAmp, seed * 31 + 1)
        val ambient = whiteNoise(ambientWarmupFrames, noiseAmp, seed * 31 + 2)

        val detector = AcousticDetector()
        val ambientResults = feedPerFrame(detector, ambient)
        val toneResults = feedPerFrame(detector, noisyTone)
        return toTrialResult(toneResults) to toTrialResult(ambientResults)
    }

    // -----------------------------------------------------------------
    // Signal generation helpers
    // -----------------------------------------------------------------

    private fun toShort(x: Double): Short = x.coerceIn(-32768.0, 32767.0).toInt().toShort()

    private fun whiteNoise(frames: Int, amp: Double, seed: Long): PcmAudio {
        val random = Random(seed)
        return PcmAudio(frames * NightjarAcoustics.FRAME_SAMPLES) { toShort(random.nextDouble(-1.0, 1.0) * amp) }
    }

    private fun silence(frames: Int): PcmAudio = PcmAudio(frames * NightjarAcoustics.FRAME_SAMPLES)

    private fun mixNoise(signal: PcmAudio, amp: Double, seed: Long): PcmAudio {
        val random = Random(seed)
        return PcmAudio(signal.size) { i -> toShort(signal[i].toDouble() + random.nextDouble(-1.0, 1.0) * amp) }
    }

    private fun feedPerFrame(detector: AcousticDetector, pcm: PcmAudio): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()
        var offset = 0
        val frameSize = NightjarAcoustics.FRAME_SAMPLES
        while (offset + frameSize <= pcm.size) {
            results += detector.analyze(pcm.copyOfRange(offset, offset + frameSize))
            offset += frameSize
        }
        return results
    }

    private fun randomPayload(seed: Long, size: Int): ByteArray {
        val bytes = ByteArray(size)
        Random(seed).nextBytes(bytes)
        return bytes
    }

    private fun seedFor(a: String, b: String, c: Int): Long =
        a.hashCode().toLong() * 1000003L + b.hashCode().toLong() * 97L + c
}
