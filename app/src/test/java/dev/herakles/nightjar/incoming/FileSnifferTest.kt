package dev.herakles.nightjar.incoming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Magic-byte sniffing coverage (spec.md v6 receive-plumbing task W1-2: "Sniffing by magic bytes,
 * not the declared MIME"). Plain JUnit, no Robolectric -- [FileSniffer] has no Android import.
 */
class FileSnifferTest {

    @Test
    fun `PNG magic bytes sniff as PNG, a lossless image`() {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(8)
        assertEquals(SniffedType.PNG, FileSniffer.sniff(bytes))
        assertTrue(SniffedType.PNG.lossless)
        assertEquals(SniffedDomain.IMAGE, SniffedType.PNG.domain)
    }

    @Test
    fun `JPEG magic bytes sniff as JPEG, a lossy image`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(8)
        assertEquals(SniffedType.JPEG, FileSniffer.sniff(bytes))
        assertFalse(SniffedType.JPEG.lossless)
    }

    @Test
    fun `RIFF WEBP sniffs as WEBP`() {
        val bytes = riff("WEBP") + ByteArray(4)
        assertEquals(SniffedType.WEBP, FileSniffer.sniff(bytes))
        assertFalse(SniffedType.WEBP.lossless)
    }

    @Test
    fun `RIFF WAVE sniffs as WAV, a lossless container`() {
        val bytes = riff("WAVE") + ByteArray(4)
        assertEquals(SniffedType.WAV, FileSniffer.sniff(bytes))
        assertTrue(SniffedType.WAV.lossless)
        assertEquals(SniffedDomain.AUDIO, SniffedType.WAV.domain)
    }

    @Test
    fun `ftyp box with a heic brand sniffs as HEIF`() {
        val bytes = isoBmff("heic") + ByteArray(8)
        assertEquals(SniffedType.HEIF, FileSniffer.sniff(bytes))
        assertFalse(SniffedType.HEIF.lossless)
    }

    @Test
    fun `ftyp box with a mif1 brand sniffs as HEIF`() {
        val bytes = isoBmff("mif1") + ByteArray(8)
        assertEquals(SniffedType.HEIF, FileSniffer.sniff(bytes))
    }

    @Test
    fun `ftyp box with an m4a brand sniffs as M4A, a lossy audio container`() {
        val bytes = isoBmff("M4A ") + ByteArray(8)
        assertEquals(SniffedType.M4A, FileSniffer.sniff(bytes))
        assertFalse(SniffedType.M4A.lossless)
        assertEquals(SniffedDomain.AUDIO, SniffedType.M4A.domain)
        assertEquals("m4a", SniffedType.M4A.defaultExtension())
    }

    @Test
    fun `ftyp box with an isom brand sniffs as M4A`() {
        val bytes = isoBmff("isom") + ByteArray(8)
        assertEquals(SniffedType.M4A, FileSniffer.sniff(bytes))
    }

    @Test
    fun `OggS magic with no OpusHead sniffs as OGG, a lossy audio container`() {
        val bytes = byteArrayOf(0x4F, 0x67, 0x67, 0x53) + ByteArray(60)
        assertEquals(SniffedType.OGG, FileSniffer.sniff(bytes))
        assertFalse(SniffedType.OGG.lossless)
        assertEquals(SniffedDomain.AUDIO, SniffedType.OGG.domain)
        assertEquals("ogg", SniffedType.OGG.defaultExtension())
    }

    @Test
    fun `OggS magic with an OpusHead payload within the search window sniffs as OPUS`() {
        // A real Ogg page header runs ~27 bytes plus a segment table; this fixture doesn't bother
        // reproducing that shape exactly -- it just places "OpusHead" a plausible distance past
        // the "OggS" capture pattern, well inside FileSniffer's bounded search window.
        val bytes = byteArrayOf(0x4F, 0x67, 0x67, 0x53) + ByteArray(28) +
            "OpusHead".toByteArray(Charsets.US_ASCII) + ByteArray(8)
        assertEquals(SniffedType.OPUS, FileSniffer.sniff(bytes))
        assertFalse(SniffedType.OPUS.lossless)
        assertEquals(SniffedDomain.AUDIO, SniffedType.OPUS.domain)
        assertEquals("opus", SniffedType.OPUS.defaultExtension())
    }

    @Test
    fun `OggS magic with an OpusHead payload past the search window still sniffs as OGG`() {
        val bytes = byteArrayOf(0x4F, 0x67, 0x67, 0x53) + ByteArray(200) +
            "OpusHead".toByteArray(Charsets.US_ASCII)
        assertEquals(SniffedType.OGG, FileSniffer.sniff(bytes))
    }

    @Test
    fun `ID3 tag sniffs as MP3, a lossy audio container`() {
        val bytes = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00) + ByteArray(8)
        assertEquals(SniffedType.MP3, FileSniffer.sniff(bytes))
        assertFalse(SniffedType.MP3.lossless)
        assertEquals(SniffedDomain.AUDIO, SniffedType.MP3.domain)
        assertEquals("mp3", SniffedType.MP3.defaultExtension())
    }

    @Test
    fun `bare MP3 frame sync (no ID3 tag) sniffs as MP3`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFB.toByte()) + ByteArray(8)
        assertEquals(SniffedType.MP3, FileSniffer.sniff(bytes))
    }

    @Test
    fun `narrowband AMR magic sniffs as AMR, a lossy audio container`() {
        val bytes = "#!AMR\n".toByteArray(Charsets.US_ASCII) + ByteArray(4)
        assertEquals(SniffedType.AMR, FileSniffer.sniff(bytes))
        assertFalse(SniffedType.AMR.lossless)
        assertEquals(SniffedDomain.AUDIO, SniffedType.AMR.domain)
        assertEquals("amr", SniffedType.AMR.defaultExtension())
    }

    @Test
    fun `wideband AMR magic sniffs as AMR`() {
        val bytes = "#!AMR-WB\n".toByteArray(Charsets.US_ASCII) + ByteArray(4)
        assertEquals(SniffedType.AMR, FileSniffer.sniff(bytes))
    }

    @Test
    fun `random garbage bytes sniff as UNKNOWN, an unsupported domain`() {
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        assertEquals(SniffedType.UNKNOWN, FileSniffer.sniff(bytes))
        assertEquals(SniffedDomain.UNSUPPORTED, SniffedType.UNKNOWN.domain)
    }

    @Test
    fun `empty byte array sniffs as UNKNOWN without throwing`() {
        assertEquals(SniffedType.UNKNOWN, FileSniffer.sniff(ByteArray(0)))
    }

    @Test
    fun `a single truncated byte sniffs as UNKNOWN without throwing`() {
        assertEquals(SniffedType.UNKNOWN, FileSniffer.sniff(byteArrayOf(0x89.toByte())))
    }

    @Test
    fun `a RIFF header truncated before its form type sniffs as UNKNOWN without throwing`() {
        val bytes = "RIFF".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x10, 0x00, 0x00, 0x00)
        assertEquals(SniffedType.UNKNOWN, FileSniffer.sniff(bytes))
    }

    @Test
    fun `default extensions match each sniffed type`() {
        assertEquals("png", SniffedType.PNG.defaultExtension())
        assertEquals("jpg", SniffedType.JPEG.defaultExtension())
        assertEquals("webp", SniffedType.WEBP.defaultExtension())
        assertEquals("heic", SniffedType.HEIF.defaultExtension())
        assertEquals("wav", SniffedType.WAV.defaultExtension())
        assertEquals("m4a", SniffedType.M4A.defaultExtension())
        assertEquals("mp3", SniffedType.MP3.defaultExtension())
        assertEquals("ogg", SniffedType.OGG.defaultExtension())
        assertEquals("opus", SniffedType.OPUS.defaultExtension())
        assertEquals("amr", SniffedType.AMR.defaultExtension())
        assertEquals("bin", SniffedType.UNKNOWN.defaultExtension())
    }

    // --- fixture builders ---

    private fun riff(formType: String): ByteArray =
        "RIFF".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0, 0, 0) + formType.toByteArray(Charsets.US_ASCII)

    private fun isoBmff(brand: String): ByteArray =
        byteArrayOf(0, 0, 0, 0x20) + "ftyp".toByteArray(Charsets.US_ASCII) + brand.toByteArray(Charsets.US_ASCII)
}
