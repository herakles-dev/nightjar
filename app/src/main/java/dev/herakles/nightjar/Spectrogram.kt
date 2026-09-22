package dev.herakles.nightjar

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * One time-ordered column per STFT frame; [magnitudesDb] inside each
 * [DoubleArray] runs bin 0 (DC) .. [binCount] - 1 (Nyquist, `frameSize/2`), in dB
 * (`20*log10`, same convention [AcousticDetector] already uses) -- brightness in a rendered
 * spectrogram is this value, not the raw linear magnitude.
 */
data class SpectrogramData(
    val frameSize: Int,
    val hop: Int,
    val sampleRateHz: Int,
    val binCount: Int,
    val columns: List<DoubleArray>,
)

/**
 * Short-time Fourier transform producing the AUDIO carrier's "where it
 * hid" spectrogram -- the honest counterpart to Stage D/2's [LsbBitPlane] for the IMAGE
 * carrier. Pure `ShortArray`-in/[SpectrogramData]-out, no Android types, so the transform is
 * verifiable in a plain JVM test ([SpectrogramTest]) -- the same "keep the math separate from
 * any Android surface" split [LsbBitPlane]/[WavFile] already use in this package.
 * `JarDetailScreen.kt`'s `FireflyCarrierBlock` is the thin Android/Compose adapter that turns
 * a [SpectrogramData] into a rendered image, same shape as [LsbBitPlane.compute]/[LsbBitPlane
 * .ofBitmap]'s own split.
 *
 * [pcm] is [channels]-interleaved PCM16, the exact layout [WavFile.ParsedWav.samples] already
 * returns. Stereo is mono-mixed (plain per-frame average across channels) before windowing --
 * and that mono sum is itself the DECODE for [AudioStegoTechnique.PHASE_INVERSION], which holds
 * its payload in L-vs-R polarity: averaging L+R cancels the cover and leaves the payload exposed
 * (measured near-maximal in the normalized image), so `FireflyCarrierBlock` labels it visible,
 * not hidden. The one genuinely sub-perceptual "can't show" case is [AudioStegoTechnique
 * .SPECTROGRAM_LSB]'s log-magnitude QIM (see that caption's logic).
 *
 * Applies the SAME standard Hann window [AcousticDetector] already uses per frame
 * (`0.5 - 0.5*cos(2*PI*n/(N-1))`, `AcousticDetector.kt`) -- no separate window formula invented
 * here -- then the shared [fft] ([Fft.kt]). [hop] < [frameSize] overlaps frames (50% default,
 * matching §1's Rx overlap). The final partial frame (fewer than [frameSize]
 * samples remaining) is zero-padded rather than dropped, so a clip shorter than one frame still
 * produces exactly one column instead of none.
 */
internal fun spectrogram(
    pcm: ShortArray,
    channels: Int = 1,
    frameSize: Int = 1024,
    hop: Int = frameSize / 2,
    sampleRateHz: Int = NightjarAcoustics.SAMPLE_RATE_HZ,
): SpectrogramData {
    require(frameSize > 0 && (frameSize and (frameSize - 1)) == 0) {
        "frameSize must be a power of two, was $frameSize"
    }
    require(hop > 0) { "hop must be positive, was $hop" }

    val safeChannels = channels.coerceAtLeast(1)
    val frameCount = pcm.size / safeChannels
    val binCount = frameSize / 2 + 1

    if (frameCount <= 0) {
        return SpectrogramData(frameSize, hop, sampleRateHz, binCount, emptyList())
    }

    val hann = DoubleArray(frameSize) { n -> 0.5 - 0.5 * cos(2.0 * PI * n / (frameSize - 1)) }
    val columns = mutableListOf<DoubleArray>()

    var start = 0
    while (start < frameCount) {
        val re = DoubleArray(frameSize)
        for (n in 0 until frameSize) {
            val sampleIndex = start + n
            val monoSample = if (sampleIndex < frameCount) {
                var sum = 0.0
                for (ch in 0 until safeChannels) sum += pcm[sampleIndex * safeChannels + ch]
                (sum / safeChannels) / Short.MAX_VALUE
            } else {
                0.0 // zero-pad the trailing partial frame instead of dropping it
            }
            re[n] = monoSample * hann[n]
        }
        val im = DoubleArray(frameSize)
        fft(re, im)

        val column = DoubleArray(binCount) { bin ->
            val magnitude = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
            20.0 * log10(max(magnitude, MIN_MAGNITUDE))
        }
        columns.add(column)
        start += hop
    }

    return SpectrogramData(frameSize, hop, sampleRateHz, binCount, columns)
}

/** Same floor [AcousticDetector]/[AudioStegoCarrier] use so an exactly-silent bin's dB value
 *  never comes out `-Infinity`. */
private const val MIN_MAGNITUDE = 1e-9
