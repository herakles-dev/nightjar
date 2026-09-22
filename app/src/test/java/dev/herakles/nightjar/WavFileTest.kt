package dev.herakles.nightjar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-exact verification of [WavFile.encodePcm16Mono]'s RIFF/WAVE header against the
 * canonical WAV spec field-by-field, for a known PCM16 sample array. Plain JVM test — no
 * Android/Robolectric dependency, since [WavFile] is pure byte-array arithmetic (see its KDoc).
 *
 * Also adds [WavFile.decodePcm16] coverage — both a standalone chunk-parsing check
 * and a full [AcousticCarrier.encode] -> [WavFile.encodePcm16Mono] -> [WavFile.decodePcm16] ->
 * [AcousticCarrier.decode] round trip, proving the "import a WAV file" path
 * (`AcousticModemScreen.kt`'s `AcousticModemController.importAndDecode`) end to end without any
 * Android APIs, file picker, or real device in the loop — the same pure-JVM-testable seam
 * [AcousticCarrierTest]'s own encode/decode round-trip tests already rely on.
 */
class WavFileTest {

    private val sampleRateHz = NightjarAcoustics.SAMPLE_RATE_HZ

    // ---------------------------------------------------------------------
    // Known non-trivial sample array (positive, negative, zero, extremes)
    // ---------------------------------------------------------------------

    @Test
    fun `header fields are byte-correct for a known PCM sample array`() {
        val pcm = shortArrayOf(0, 1, -1, 32767, -32768, 12345, -12345)
        val wav = WavFile.encodePcm16Mono(pcm, sampleRateHz)

        val dataBytes = pcm.size * 2
        val expectedTotalBytes = WavFile.HEADER_BYTES + dataBytes

        assertEquals("total file length", expectedTotalBytes, wav.size)

        // RIFF chunk descriptor
        assertEquals("bytes 0-3 == 'RIFF'", "RIFF", ascii(wav, 0, 4))
        assertEquals("ChunkSize (bytes 4-7)", 36 + dataBytes, readLE32(wav, 4))
        assertEquals("bytes 8-11 == 'WAVE'", "WAVE", ascii(wav, 8, 4))

        // fmt subchunk
        assertEquals("bytes 12-15 == 'fmt '", "fmt ", ascii(wav, 12, 4))
        assertEquals("Subchunk1Size (bytes 16-19)", 16, readLE32(wav, 16))
        assertEquals("AudioFormat (bytes 20-21) == PCM(1)", 1, readLE16(wav, 20))
        assertEquals("NumChannels (bytes 22-23) == mono(1)", 1, readLE16(wav, 22))
        assertEquals("SampleRate (bytes 24-27)", sampleRateHz, readLE32(wav, 24))
        val expectedByteRate = sampleRateHz * 1 * 2 // NumChannels * BitsPerSample/8
        assertEquals("ByteRate (bytes 28-31)", expectedByteRate, readLE32(wav, 28))
        assertEquals("BlockAlign (bytes 32-33)", 2, readLE16(wav, 32))
        assertEquals("BitsPerSample (bytes 34-35)", 16, readLE16(wav, 34))

        // data subchunk
        assertEquals("bytes 36-39 == 'data'", "data", ascii(wav, 36, 4))
        assertEquals("Subchunk2Size (bytes 40-43)", dataBytes, readLE32(wav, 40))

        // PCM payload, byte-exact, little-endian, sign-preserving
        for (i in pcm.indices) {
            val sampleOffset = WavFile.HEADER_BYTES + i * 2
            val readBack = readLE16Signed(wav, sampleOffset)
            assertEquals("sample $i round-trips exactly", pcm[i].toInt(), readBack)
        }
    }

    // ---------------------------------------------------------------------
    // Empty PCM: header-only file, zero-length data chunk
    // ---------------------------------------------------------------------

    @Test
    fun `empty PCM array produces a header-only file with zero data chunk size`() {
        val wav = WavFile.encodePcm16Mono(ShortArray(0), sampleRateHz)

        assertEquals("total length is exactly the header size", WavFile.HEADER_BYTES, wav.size)
        assertEquals("ChunkSize", 36, readLE32(wav, 4))
        assertEquals("Subchunk2Size (data bytes)", 0, readLE32(wav, 40))
    }

    // ---------------------------------------------------------------------
    // Single-sample PCM: smallest non-empty case
    // ---------------------------------------------------------------------

    @Test
    fun `single sample PCM array has data chunk size 2 and correct total length`() {
        val wav = WavFile.encodePcm16Mono(shortArrayOf(4242), sampleRateHz)

        assertEquals("total length", WavFile.HEADER_BYTES + 2, wav.size)
        assertEquals("ChunkSize", 38, readLE32(wav, 4))
        assertEquals("Subchunk2Size (data bytes)", 2, readLE32(wav, 40))
        assertEquals("sample round-trips", 4242, readLE16Signed(wav, WavFile.HEADER_BYTES))
    }

    // ---------------------------------------------------------------------
    // A larger, realistic buffer — arithmetic must still hold, not just for tiny arrays
    // ---------------------------------------------------------------------

    @Test
    fun `larger PCM buffer keeps chunk-size arithmetic consistent`() {
        val pcm = ShortArray(48_000) { i -> ((i % 2000) - 1000).toShort() } // 1 second @ 48kHz
        val wav = WavFile.encodePcm16Mono(pcm, sampleRateHz)

        val dataBytes = pcm.size * 2
        assertEquals("total length", WavFile.HEADER_BYTES + dataBytes, wav.size)
        assertEquals("ChunkSize", 36 + dataBytes, readLE32(wav, 4))
        assertEquals("Subchunk2Size", dataBytes, readLE32(wav, 40))
        assertTrue("data chunk size sane for 1s @ 48kHz mono PCM16", dataBytes == 96_000)
    }

    // ---------------------------------------------------------------------
    // decodePcm16 -- the inverse parse, standalone
    // ---------------------------------------------------------------------

    @Test
    fun `decodePcm16 recovers sample rate, channel count, and samples from a file this app encoded`() {
        val pcm = shortArrayOf(0, 1, -1, 32767, -32768, 12345, -12345)
        val wav = WavFile.encodePcm16Mono(pcm, sampleRateHz)

        val parsed = WavFile.decodePcm16(wav)

        assertTrue("a file WavFile itself encoded must parse back", parsed != null)
        requireNotNull(parsed)
        assertEquals("sample rate", sampleRateHz, parsed.sampleRateHz)
        assertEquals("channel count", 1, parsed.numChannels)
        assertArrayEquals("samples", pcm, parsed.samples)
    }

    @Test
    fun `decodePcm16 returns null for bytes that are not a RIFF WAVE file`() {
        val notWav = "definitely not a wav file".toByteArray(Charsets.US_ASCII)
        assertNull(WavFile.decodePcm16(notWav))
    }

    @Test
    fun `decodePcm16 returns null for a truncated header`() {
        assertNull(WavFile.decodePcm16(ByteArray(4))) // shorter than even "RIFF"+ChunkSize+"WAVE"
    }

    @Test
    fun `decodePcm16 recovers an empty-data file`() {
        val wav = WavFile.encodePcm16Mono(ShortArray(0), sampleRateHz)
        val parsed = WavFile.decodePcm16(wav)
        assertTrue(parsed != null)
        requireNotNull(parsed)
        assertEquals(0, parsed.samples.size)
        assertEquals(sampleRateHz, parsed.sampleRateHz)
        assertEquals(1, parsed.numChannels)
    }

    // ---------------------------------------------------------------------
    // Full AcousticCarrier.encode() -> WavFile.encodePcm16Mono() -> WavFile.decodePcm16()
    // -> AcousticCarrier.decode() round trip -- this is the "import a WAV file" path end to end,
    // minus only the Android file-picker/ContentResolver plumbing around WavFile.decodePcm16 in
    // AcousticModemScreen.kt's AcousticModemController.importAndDecode.
    // ---------------------------------------------------------------------

    @Test
    fun `decodePcm16 round-trips a full AcousticCarrier encode-decode cycle through an encoded WAV file`() {
        val carrier = AcousticCarrier(NightjarAcoustics.Protocol.AUDIBLE, NightjarAcoustics.SymbolRate.NORMAL)
        val payload = "nightjar task 32 import round-trip".toByteArray(Charsets.US_ASCII)

        // 1. Encode a real payload to PCM, exactly like "transmit"/"save"/"share" already do.
        val pcm = carrier.encode(payload)

        // 2. Wrap it in a WAV file, exactly like "save"/"share" already do.
        val wavBytes = WavFile.encodePcm16Mono(pcm, sampleRateHz)

        // 3. Parse it back -- the new "import" path's WAV-container step.
        val parsed = WavFile.decodePcm16(wavBytes)
        assertTrue("a WAV this app itself produced must parse back", parsed != null)
        requireNotNull(parsed)
        assertEquals("sample rate survives the WAV round trip", sampleRateHz, parsed.sampleRateHz)
        assertEquals("mono channel count survives the WAV round trip", 1, parsed.numChannels)
        assertArrayEquals("PCM samples survive the WAV round trip byte-exact", pcm, parsed.samples)

        // 4. Hand the recovered samples to the same decode() pipeline "listen" uses.
        val result = carrier.decode(parsed.samples)
        assertTrue("decode() must succeed on the WAV-round-tripped samples", result is DecodeResult.Success)
        val success = result as DecodeResult.Success
        assertArrayEquals("recovered payload matches the original payload exactly", payload, success.payload)
        assertEquals("a clean synthetic round trip should need no RS correction", 0, success.correctedByteErrors)
    }

    // ---------------------------------------------------------------------
    // codec-M02: WAVE_FORMAT_EXTENSIBLE (0xFFFE) fmt chunks. Common for files with >2 channels
    // or an explicit channel-mask, and some Android/desktop recorders even for plain PCM16 mono/
    // stereo -- decodePcm16 used to reject these outright (`AudioFormat != 1`) even when the
    // real encoded data is fully readable 16-bit PCM. Hand-built rather than produced by
    // WavFile itself, since [WavFile.encodePcm16Mono]/[encodePcm16Stereo] never emit this
    // variant (only real-world/foreign files do).
    // ---------------------------------------------------------------------

    @Test
    fun `decodePcm16 accepts a WAVE_FORMAT_EXTENSIBLE fmt chunk whose SubFormat is PCM`() {
        val pcm = shortArrayOf(0, 1, -1, 32767, -32768, 12345, -12345)
        val wav = buildExtensibleWav(pcm, sampleRateHz, numChannels = 1, subFormatFirstTwoBytes = 1)

        val parsed = WavFile.decodePcm16(wav)

        assertTrue("a WAVE_FORMAT_EXTENSIBLE/PCM file must parse", parsed != null)
        requireNotNull(parsed)
        assertEquals("sample rate", sampleRateHz, parsed.sampleRateHz)
        assertEquals("channel count", 1, parsed.numChannels)
        assertArrayEquals("samples", pcm, parsed.samples)
    }

    @Test
    fun `decodePcm16 rejects a WAVE_FORMAT_EXTENSIBLE fmt chunk whose SubFormat is not PCM`() {
        val pcm = shortArrayOf(0, 1, -1)
        // SubFormat leading 2 bytes = 3 (KSDATAFORMAT_SUBTYPE_IEEE_FLOAT, say) instead of 1 (PCM).
        val wav = buildExtensibleWav(pcm, sampleRateHz, numChannels = 1, subFormatFirstTwoBytes = 3)

        assertNull(WavFile.decodePcm16(wav))
    }

    /**
     * Hand-builds a minimal RIFF/WAVE file with a `WAVE_FORMAT_EXTENSIBLE` (`0xFFFE`) `fmt `
     * chunk (40-byte body: the 16-byte base fields, `cbSize` = 22, `wValidBitsPerSample`,
     * `dwChannelMask`, and a 16-byte `SubFormat` GUID whose leading 2 bytes are
     * [subFormatFirstTwoBytes] — the only part [WavFile.decodePcm16] actually reads, per
     * codec-M02) followed by a `data` chunk holding [pcm] as little-endian PCM16.
     */
    private fun buildExtensibleWav(pcm: ShortArray, sampleRateHz: Int, numChannels: Int, subFormatFirstTwoBytes: Int): ByteArray {
        val fmtBodySize = 40 // 16 base + 2 cbSize + 2 validBits + 4 channelMask + 16 SubFormat GUID
        val dataBytes = pcm.size * 2
        val out = ByteArray(12 + 8 + fmtBodySize + 8 + dataBytes)
        var pos = 0

        fun ascii(tag: String) {
            for (c in tag) out[pos++] = c.code.toByte()
        }
        fun le16(value: Int) {
            out[pos++] = (value and 0xFF).toByte()
            out[pos++] = ((value ushr 8) and 0xFF).toByte()
        }
        fun le32(value: Int) {
            out[pos++] = (value and 0xFF).toByte()
            out[pos++] = ((value ushr 8) and 0xFF).toByte()
            out[pos++] = ((value ushr 16) and 0xFF).toByte()
            out[pos++] = ((value ushr 24) and 0xFF).toByte()
        }

        ascii("RIFF")
        le32(4 + (8 + fmtBodySize) + (8 + dataBytes)) // ChunkSize: "WAVE" + fmt chunk + data chunk
        ascii("WAVE")

        ascii("fmt ")
        le32(fmtBodySize)
        le16(0xFFFE) // wFormatTag = WAVE_FORMAT_EXTENSIBLE
        le16(numChannels)
        le32(sampleRateHz)
        le32(sampleRateHz * numChannels * 2) // nAvgBytesPerSec
        le16(numChannels * 2) // nBlockAlign
        le16(16) // wBitsPerSample
        le16(22) // cbSize (size of the extension past the base 16 bytes)
        le16(16) // wValidBitsPerSample
        le32(if (numChannels == 1) 0x4 else 0x3) // dwChannelMask (SPEAKER_FRONT_CENTER / L+R)
        le16(subFormatFirstTwoBytes) // SubFormat leading 2 bytes -- the only part decodePcm16 reads
        repeat(14) { out[pos++] = 0 } // rest of the 16-byte SubFormat GUID -- irrelevant to decodePcm16

        ascii("data")
        le32(dataBytes)
        for (sample in pcm) le16(sample.toInt())

        check(pos == out.size) { "buildExtensibleWav wrote $pos bytes, expected ${out.size}" }
        return out
    }

    // ---------------------------------------------------------------------
    // Little-endian / ASCII readers, independent of WavFile's own write helpers
    // ---------------------------------------------------------------------

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    private fun readLE16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    /** Same as [readLE16] but sign-extended to a signed 16-bit value (for PCM sample verification). */
    private fun readLE16Signed(bytes: ByteArray, offset: Int): Int =
        readLE16(bytes, offset).toShort().toInt()

    private fun readLE32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
