package dev.herakles.nightjar.incoming

import dev.herakles.nightjar.AcousticCarrier
import dev.herakles.nightjar.AudioStegoCarrier
import dev.herakles.nightjar.AudioStegoTechnique
import dev.herakles.nightjar.NightjarAcoustics
import dev.herakles.nightjar.PcmAudio
import dev.herakles.nightjar.WavFile
import dev.herakles.nightjar.picker.Module
import dev.herakles.nightjar.share.OutgoingKind
import dev.herakles.nightjar.share.outgoingMimeAndExtensionFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IncomingRouter.routeWav] coverage (spec.md v6 receive-plumbing task W1-2, gate-31/32). Plain
 * JUnit, no Robolectric -- [PcmAudio] is a bare `ShortArray` and [WavFile] is pure byte-array
 * parsing, so none of this needs a real `Bitmap`/Android runtime.
 */
class IncomingRouterAudioTest {

    private val payload = "nightjar receive-plumbing test payload".toByteArray(Charsets.UTF_8)

    @Test
    fun `an acoustic-modem WAV is caught in the right jar with the modem technique`() {
        val pcm = AcousticCarrier().encode(payload)
        val wavBytes = WavFile.encodePcm16Mono(pcm, NightjarAcoustics.SAMPLE_RATE_HZ)

        val outcome = IncomingRouter.routeWav(wavBytes)

        assertTrue("expected Caught but got $outcome", outcome is IncomingOutcome.Caught)
        outcome as IncomingOutcome.Caught
        assertEquals(Module.ACOUSTIC_MODEM, outcome.module)
        assertEquals(IncomingRouter.ACOUSTIC_MODEM_TECHNIQUE, outcome.technique)
        assertTrue(payload.contentEquals(outcome.payload))
        assertTrue(wavBytes.contentEquals(outcome.carrierBytes))
        assertEquals("wav", outcome.extension)
    }

    @Test
    fun `a PHASE_INVERSION audio-stego WAV is caught in the audio-stego jar reporting its technique`() {
        // PHASE_INVERSION capacity = floor(floor(cover.size / 480) / 8) - 11 overhead bytes; the
        // 39-byte shared payload needs a cover well past the minimum, so this uses a generously
        // sized one rather than computing the exact threshold.
        val cover = silentCover(samples = 500_000)
        val stego = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_INVERSION).encode(payload)
        val wavBytes = WavFile.encodePcm16Stereo(stego, NightjarAcoustics.SAMPLE_RATE_HZ)

        val outcome = IncomingRouter.routeWav(wavBytes)

        assertTrue("expected Caught but got $outcome", outcome is IncomingOutcome.Caught)
        outcome as IncomingOutcome.Caught
        assertEquals(Module.AUDIO_STEGANOGRAPHY, outcome.module)
        assertEquals(AudioStegoTechnique.PHASE_INVERSION.name, outcome.technique)
        assertTrue(payload.contentEquals(outcome.payload))
    }

    @Test
    fun `a plain silent WAV with no firefly resolves to NoFirefly, a lossless container`() {
        val wavBytes = WavFile.encodePcm16Mono(silentCover(samples = 20_000), NightjarAcoustics.SAMPLE_RATE_HZ)

        assertEquals(IncomingOutcome.NoFirefly, IncomingRouter.routeWav(wavBytes))
    }

    @Test
    fun `a RIFF WAVE file with no usable fmt or data chunk resolves to Unsupported`() {
        val bytes = "RIFF".toByteArray(Charsets.US_ASCII) + byteArrayOf(4, 0, 0, 0) + "WAVE".toByteArray(Charsets.US_ASCII)

        val outcome = IncomingRouter.routeWav(bytes)

        assertTrue("expected Unsupported but got $outcome", outcome is IncomingOutcome.Unsupported)
    }

    @Test
    fun `routeCompressedAudio with no usable modem PCM resolves to Squeezed`() {
        val bytes = byteArrayOf(0x4F, 0x67, 0x67, 0x53) // OggS -- irrelevant to this outcome, just non-empty bytes

        val outcome = IncomingRouter.routeCompressedAudio(bytes, modemPcm = null, extension = "audio")

        assertEquals(IncomingOutcome.Squeezed(SqueezedContainer.COMPRESSED_AUDIO), outcome)
    }

    @Test
    fun `routeCompressedAudio catches a modem firefly when the demuxed PCM decodes`() {
        val pcm = AcousticCarrier().encode(payload)
        val bytes = byteArrayOf(0x4F, 0x67, 0x67, 0x53)

        val outcome = IncomingRouter.routeCompressedAudio(bytes, modemPcm = pcm, extension = "audio")

        assertTrue("expected Caught but got $outcome", outcome is IncomingOutcome.Caught)
        outcome as IncomingOutcome.Caught
        assertEquals(Module.ACOUSTIC_MODEM, outcome.module)
        assertEquals("audio", outcome.extension)
    }

    @Test
    fun `a modem firefly caught from a fake M4A container shares as audio-mp4, not audio-wav`() {
        // Closes the loop review finding #3 flagged: FileSniffer used to collapse every compressed
        // audio container into one OTHER_AUDIO bucket (defaultExtension "audio"), so
        // outgoingMimeAndExtensionFor's real extension->MIME map could never match. Now the
        // sniffer records the real container, so a caught modem firefly's own file extension is
        // "m4a" end to end, and export resolves the real audio/mp4 MIME instead of the audio/wav
        // fallback.
        val m4aContainer = byteArrayOf(0, 0, 0, 0x20) + "ftyp".toByteArray(Charsets.US_ASCII) + "M4A ".toByteArray(Charsets.US_ASCII) + ByteArray(8)
        val sniffed = FileSniffer.sniff(m4aContainer)
        assertEquals(SniffedType.M4A, sniffed)
        val extension = sniffed.defaultExtension()
        assertEquals("m4a", extension)

        // The modem's demuxed PCM is produced separately by IncomingAndroidAdapters in production
        // (Android media decoding, not exercised here) -- this simulates a successful demux
        // yielding the same modem-tone PCM the acoustic carrier itself encodes.
        val modemPcm = AcousticCarrier().encode(payload)

        val outcome = IncomingRouter.routeCompressedAudio(m4aContainer, modemPcm = modemPcm, extension = extension)

        assertTrue("expected Caught but got $outcome", outcome is IncomingOutcome.Caught)
        outcome as IncomingOutcome.Caught
        assertEquals(Module.ACOUSTIC_MODEM, outcome.module)
        assertEquals(IncomingRouter.ACOUSTIC_MODEM_TECHNIQUE, outcome.technique)
        assertTrue(payload.contentEquals(outcome.payload))
        assertEquals("m4a", outcome.extension)

        val mediaPath = "a1b2c3d4.${outcome.extension}"
        val (mimeType, exportExtension) = outgoingMimeAndExtensionFor(OutgoingKind.AUDIO, mediaPath)
        assertEquals("audio/mp4", mimeType)
        assertEquals("m4a", exportExtension)
    }

    private fun silentCover(samples: Int): PcmAudio = ShortArray(samples)
}
