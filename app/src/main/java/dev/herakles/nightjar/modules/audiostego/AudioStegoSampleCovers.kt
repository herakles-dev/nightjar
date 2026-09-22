package dev.herakles.nightjar.modules.audiostego

import dev.herakles.nightjar.NightjarAcoustics
import dev.herakles.nightjar.PcmAudio
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The two bundled sample cover clips [dev.herakles.nightjar.modules.audiostego.AudioStegoScreen]
 * offers — this app has no `res/raw/` or `assets/` audio (confirmed: nothing to load), so both
 * covers are synthesized in-memory as [PcmAudio] (mono `ShortArray` at
 * [NightjarAcoustics.SAMPLE_RATE_HZ]) rather than bundled as files, matching how the rest of this
 * app already treats [PcmAudio] as in-memory-only.
 */
enum class AudioSampleCover(val label: String) {
    SPOKEN_WORD("spoken word"),
    SOFT_SYNTH("soft synth"),
}

/**
 * Clip length for both sample covers: 5 seconds (240,000 samples @ 48 kHz). Comfortable headroom
 * over every technique's minimum, most notably [dev.herakles.nightjar.AudioStegoTechnique.MFSK]'s
 * fixed ~64-symbol-block codeword (64 blocks * 1024 samples/block = 65,536 samples) — 240,000
 * samples leaves ~3.7x that floor, so all three techniques always have real embeddable capacity
 * on either cover.
 */
private const val COVER_DURATION_SECONDS = 5.0
private const val COVER_SAMPLE_COUNT = (COVER_DURATION_SECONDS * NightjarAcoustics.SAMPLE_RATE_HZ).toInt()

/**
 * Peak-amplitude ceiling both synthesized covers are kept under — comfortably below
 * [Short.MAX_VALUE] (32767) so [dev.herakles.nightjar.AudioStegoCarrier]'s own saturating-
 * arithmetic headroom (documented in its class KDoc for all three techniques) isn't stressed,
 * matching the `[-16000, 16000]` discipline [dev.herakles.nightjar.AudioStegoCarrierTest]'s own
 * synthetic noise covers already follow.
 */
private const val PEAK_AMPLITUDE_CEILING = 20000

/** Builds the [PcmAudio] for [cover] — pure, deterministic, no I/O, safe to call from Compose
 *  directly (see [dev.herakles.nightjar.modules.audiostego.AudioStegoScreen]'s `remember(cover)`). */
fun synthesizeSampleCover(cover: AudioSampleCover): PcmAudio = when (cover) {
    AudioSampleCover.SOFT_SYNTH -> synthesizeSoftSynthCover()
    AudioSampleCover.SPOKEN_WORD -> synthesizeSpokenWordApproximationCover()
}

// --- SOFT_SYNTH ---

/** Fundamental frequency (Hz) of the pad tone — A3, squarely in the "low-mid" range the brief
 *  asked for (220-330 Hz). */
private const val SOFT_SYNTH_FUNDAMENTAL_HZ = 220.0

/** (harmonic multiple, relative amplitude) pairs summed to build the pad — a fundamental plus
 *  two quieter upper harmonics, the standard cheap way to make a single sine read as a pad
 *  instead of a sterile pure tone. */
private val SOFT_SYNTH_HARMONICS = listOf(1.0 to 1.0, 2.0 to 0.5, 3.0 to 0.25)

private val SOFT_SYNTH_HARMONIC_AMPLITUDE_SUM = SOFT_SYNTH_HARMONICS.sumOf { it.second }

/** Peak instantaneous amplitude of the summed harmonics, before the attack/release envelope and
 *  vibrato are applied — comfortably under [PEAK_AMPLITUDE_CEILING] with margin for vibrato's
 *  +5% swing. */
private const val SOFT_SYNTH_PEAK_AMPLITUDE = 9000.0

/** Slow attack/release so the clip never clicks at start or end (brief requirement). Long enough
 *  relative to the 5s clip to read as a deliberate fade, not a blip. */
private const val SOFT_SYNTH_ATTACK_SECONDS = 0.4
private const val SOFT_SYNTH_RELEASE_SECONDS = 0.6

/** Slow amplitude "vibrato" wobble — a touch of movement so the pad doesn't read as a dead-flat
 *  synth tone, per the brief. */
private const val SOFT_SYNTH_VIBRATO_RATE_HZ = 5.0
private const val SOFT_SYNTH_VIBRATO_DEPTH = 0.05

/**
 * A synthesized pad-like tone: [SOFT_SYNTH_FUNDAMENTAL_HZ] plus two quieter harmonics, a
 * raised-cosine attack/release envelope (no hard on/off click), and a slow amplitude-modulation
 * vibrato. Fully deterministic (no [Random] involved at all) — every app run produces the exact
 * same clip.
 */
private fun synthesizeSoftSynthCover(): PcmAudio {
    val sampleRate = NightjarAcoustics.SAMPLE_RATE_HZ
    val perHarmonicScale = SOFT_SYNTH_PEAK_AMPLITUDE / SOFT_SYNTH_HARMONIC_AMPLITUDE_SUM
    val attackSamples = (SOFT_SYNTH_ATTACK_SECONDS * sampleRate).toInt().coerceAtLeast(1)
    val releaseSamples = (SOFT_SYNTH_RELEASE_SECONDS * sampleRate).toInt().coerceAtLeast(1)

    return ShortArray(COVER_SAMPLE_COUNT) { n ->
        val t = n.toDouble() / sampleRate
        var raw = 0.0
        for ((multiple, relativeAmplitude) in SOFT_SYNTH_HARMONICS) {
            raw += sin(2.0 * PI * SOFT_SYNTH_FUNDAMENTAL_HZ * multiple * t) * relativeAmplitude * perHarmonicScale
        }
        val vibrato = 1.0 + SOFT_SYNTH_VIBRATO_DEPTH * sin(2.0 * PI * SOFT_SYNTH_VIBRATO_RATE_HZ * t)
        val envelope = when {
            n < attackSamples -> raisedCosineRamp(n.toDouble() / attackSamples)
            n >= COVER_SAMPLE_COUNT - releaseSamples ->
                raisedCosineRamp((COVER_SAMPLE_COUNT - n).toDouble() / releaseSamples)
            else -> 1.0
        }
        (raw * vibrato * envelope).roundToInt()
            .coerceIn(-PEAK_AMPLITUDE_CEILING, PEAK_AMPLITUDE_CEILING)
            .toShort()
    }
}

/** 0 -> 1 raised-cosine (half-Hann) ramp, `progress` in `[0, 1]` — smooth, click-free fade. */
private fun raisedCosineRamp(progress: Double): Double =
    (0.5 * (1.0 - cos(PI * progress.coerceIn(0.0, 1.0))))

// --- SPOKEN_WORD ---

/**
 * Fixed seed so the "spoken word" clip's noise content and burst/gap timing are identical on
 * every run (deterministic, per the brief) — a real recording is still not available, but at
 * least the demo is reproducible.
 */
private const val SPOKEN_WORD_SEED = 424242L

/** Burst ("syllable") length range, in ms — roughly syllable-like timing per the brief. */
private const val BURST_MIN_MS = 150
private const val BURST_MAX_MS = 300

/** Gap ("between syllables") length range, in ms — brief pauses, shorter than a burst. */
private const val GAP_MIN_MS = 40
private const val GAP_MAX_MS = 90

/** Lowpass cutoff (Hz) applied to the raw white noise to keep it band-limited — roughly where
 *  most speech energy lives, so the result reads as a dull, voice-band texture rather than a
 *  harsh full-spectrum hiss. */
private const val SPOKEN_WORD_LOWPASS_CUTOFF_HZ = 3000.0

/** Final amplitude scale applied to the band-limited, enveloped noise — comfortably under
 *  [PEAK_AMPLITUDE_CEILING]. */
private const val SPOKEN_WORD_AMPLITUDE = 12000.0

/**
 * **Synthesized approximation, NOT a real recording.** This app cannot bundle or record real
 * human speech, so this clip is amplitude-modulated, band-limited (lowpass-filtered) noise
 * shaped with a burst/gap "cadence" loosely resembling syllable timing — a demo stand-in for
 * "something with speech-like texture," never to be represented as, or mistaken for, an actual
 * voice recording. (Same honesty-about-synthetic-content discipline this project applies to
 * every synthetic payload/cover it generates.)
 *
 * Both the noise and the burst/gap timing are drawn from a fixed-seed [Random], so the clip is
 * fully reproducible across runs.
 */
private fun synthesizeSpokenWordApproximationCover(): PcmAudio {
    val sampleRate = NightjarAcoustics.SAMPLE_RATE_HZ
    val rng = Random(SPOKEN_WORD_SEED)

    val envelope = DoubleArray(COVER_SAMPLE_COUNT)
    var i = 0
    while (i < COVER_SAMPLE_COUNT) {
        val burstMs = rng.nextInt(BURST_MIN_MS, BURST_MAX_MS + 1)
        val burstSamples = (burstMs * sampleRate / 1000.0).toInt().coerceAtLeast(1)
        val burstEnd = (i + burstSamples).coerceAtMost(COVER_SAMPLE_COUNT)
        for (j in i until burstEnd) {
            // Half-sine shape within the burst itself (0 -> 1 -> 0) so each "syllable" fades in
            // and out rather than snapping on/off at its own boundaries.
            val localProgress = (j - i).toDouble() / burstSamples
            envelope[j] = sin(PI * localProgress).coerceIn(0.0, 1.0)
        }
        i = burstEnd
        if (i >= COVER_SAMPLE_COUNT) break
        val gapMs = rng.nextInt(GAP_MIN_MS, GAP_MAX_MS + 1)
        i += (gapMs * sampleRate / 1000.0).toInt().coerceAtLeast(1) // envelope stays 0 during the gap
    }

    // One-pole lowpass (simple RC filter) over raw white noise -- band-limits it toward the
    // speech-relevant range instead of leaving it full-spectrum hiss.
    val dt = 1.0 / sampleRate
    val rc = 1.0 / (2.0 * PI * SPOKEN_WORD_LOWPASS_CUTOFF_HZ)
    val alpha = dt / (rc + dt)
    var lowpassState = 0.0

    return ShortArray(COVER_SAMPLE_COUNT) { n ->
        val whiteNoise = rng.nextDouble(-1.0, 1.0)
        lowpassState += alpha * (whiteNoise - lowpassState)
        (lowpassState * envelope[n] * SPOKEN_WORD_AMPLITUDE).roundToInt()
            .coerceIn(-PEAK_AMPLITUDE_CEILING, PEAK_AMPLITUDE_CEILING)
            .toShort()
    }
}
