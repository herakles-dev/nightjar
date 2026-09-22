package dev.herakles.nightjar.incoming

/**
 * Physical medium/domain a [SniffedType] belongs to -- picks which pipeline
 * `dev.herakles.nightjar.incoming.IncomingPipeline` routes a file through.
 */
enum class SniffedDomain { IMAGE, AUDIO, UNSUPPORTED }

/**
 * A file's real container format, determined by [FileSniffer.sniff] from its own magic bytes --
 * never from a caller-supplied MIME type or filename extension ("Sniffing by magic bytes, not
 * the declared MIME"). Both a share-sheet's declared type and a
 * document picker's extension are sender-controlled and routinely wrong (screenshots saved as
 * `.jpg`, voice memos shared as generic `application/octet-stream`, etc.), so
 * [dev.herakles.nightjar.incoming.IncomingRouter] never trusts either.
 *
 * [lossless] feeds the NoFirefly-vs-Squeezed half of [dev.herakles.nightjar.incoming.IncomingOutcome]
 * (this pipeline's five outcomes): a lossless container that decodes nothing is
 * [dev.herakles.nightjar.incoming.IncomingOutcome.NoFirefly], a lossy one is
 * [dev.herakles.nightjar.incoming.IncomingOutcome.Squeezed].
 */
enum class SniffedType(val domain: SniffedDomain, val lossless: Boolean) {
    PNG(SniffedDomain.IMAGE, lossless = true),
    JPEG(SniffedDomain.IMAGE, lossless = false),
    WEBP(SniffedDomain.IMAGE, lossless = false),
    HEIF(SniffedDomain.IMAGE, lossless = false),
    WAV(SniffedDomain.AUDIO, lossless = true),
    M4A(SniffedDomain.AUDIO, lossless = false),
    MP3(SniffedDomain.AUDIO, lossless = false),
    OGG(SniffedDomain.AUDIO, lossless = false),
    OPUS(SniffedDomain.AUDIO, lossless = false),
    AMR(SniffedDomain.AUDIO, lossless = false),
    UNKNOWN(SniffedDomain.UNSUPPORTED, lossless = false),
}

/** Default on-disk extension for a [SniffedType], used when persisting a caught firefly's
 *  carrier bytes ([dev.herakles.nightjar.modules.fireflyjar.FireflyMediaStore.write]'s
 *  `extension` parameter is a bare filename suffix, not validated against a fixed set). */
fun SniffedType.defaultExtension(): String = when (this) {
    SniffedType.PNG -> "png"
    SniffedType.JPEG -> "jpg"
    SniffedType.WEBP -> "webp"
    SniffedType.HEIF -> "heic"
    SniffedType.WAV -> "wav"
    SniffedType.M4A -> "m4a"
    SniffedType.MP3 -> "mp3"
    SniffedType.OGG -> "ogg"
    SniffedType.OPUS -> "opus"
    SniffedType.AMR -> "amr"
    SniffedType.UNKNOWN -> "bin"
}

/**
 * Pure-JVM magic-byte sniffer for every container [dev.herakles.nightjar.incoming.IncomingRouter]
 * knows how to route. No Android imports at all -- takes and returns plain
 * types so [FileSnifferTest] can exercise every branch without Robolectric, and never throws on
 * malformed/truncated/empty input (every helper below bounds-checks before it reads).
 */
object FileSniffer {

    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val OGG_MAGIC = byteArrayOf(0x4F, 0x67, 0x67, 0x53) // "OggS" -- covers OGG and Opus-in-Ogg
    private val ID3_MAGIC = byteArrayOf(0x49, 0x44, 0x33) // "ID3" -- MP3 with a leading ID3 tag
    // Narrowband ("#!AMR\n") and wideband ("#!AMR-WB\n") share this 5-byte prefix.
    private val AMR_MAGIC = byteArrayOf(0x23, 0x21, 0x41, 0x4D, 0x52)
    // "OpusHead" -- the codec-identification header that opens the very first Ogg page's payload,
    // right after that page's own header (whose length varies with segment count).
    private val OPUS_HEAD_MAGIC = byteArrayOf(0x4F, 0x70, 0x75, 0x73, 0x48, 0x65, 0x61, 0x64)
    // Bounds the OpusHead search well short of fully parsing the Ogg page structure -- the header
    // is typically ~28 bytes plus a small segment table, so "OpusHead" starts well inside this.
    private const val OPUS_HEAD_SEARCH_BOUND = 96

    /** ISO-BMFF `ftyp` major/compatible brands that mark the HEIC/HEIF family, as opposed to an
     *  MP4/M4A brand (both container families share the same `ftyp` box shape). */
    private val HEIF_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs", "mif1", "msf1")

    fun sniff(bytes: ByteArray): SniffedType {
        if (matches(bytes, 0, PNG_MAGIC)) return SniffedType.PNG
        if (matches(bytes, 0, JPEG_MAGIC)) return SniffedType.JPEG
        if (isRiff(bytes, "WAVE")) return SniffedType.WAV
        if (isRiff(bytes, "WEBP")) return SniffedType.WEBP
        if (isIsoBmff(bytes)) {
            val brand = asciiAt(bytes, 8, 4)
            // Everything else that carries a `ftyp` box in this app's reachable intent-filters
            // (image/*, audio/*) is an MP4-family audio container (M4A/AAC) -- HEIF is the only
            // ISO-BMFF *image* brand family nightjar can be sent, so an unrecognized brand
            // defaults to audio rather than a third "maybe video" bucket this app never receives.
            return if (brand in HEIF_BRANDS) SniffedType.HEIF else SniffedType.M4A
        }
        if (matches(bytes, 0, OGG_MAGIC)) return if (hasOpusHead(bytes)) SniffedType.OPUS else SniffedType.OGG
        if (matches(bytes, 0, ID3_MAGIC)) return SniffedType.MP3
        if (matches(bytes, 0, AMR_MAGIC)) return SniffedType.AMR
        if (isMp3FrameSync(bytes)) return SniffedType.MP3
        return SniffedType.UNKNOWN
    }

    private fun isRiff(bytes: ByteArray, formType: String): Boolean =
        bytes.size >= 12 && asciiAt(bytes, 0, 4) == "RIFF" && asciiAt(bytes, 8, 4) == formType

    /** ISO base media container (MP4/M4A/HEIF family): a `ftyp` box starting at byte offset 4. */
    private fun isIsoBmff(bytes: ByteArray): Boolean =
        bytes.size >= 12 && asciiAt(bytes, 4, 4) == "ftyp"

    /** MPEG audio frame sync word: 11 set sync bits, then any MPEG version/layer bit pattern --
     *  a permissive check for a raw MP3 stream with no leading ID3 tag. */
    private fun isMp3FrameSync(bytes: ByteArray): Boolean =
        bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xE0) == 0xE0

    /** Plain Ogg Vorbis/FLAC-in-Ogg has no "OpusHead" anywhere in the search window, so this stays
     *  false for it -- only Opus-in-Ogg streams carry this codec-identification header. */
    private fun hasOpusHead(bytes: ByteArray): Boolean {
        val lastOffset = minOf(bytes.size, OPUS_HEAD_SEARCH_BOUND) - OPUS_HEAD_MAGIC.size
        for (offset in 0..lastOffset) {
            if (matches(bytes, offset, OPUS_HEAD_MAGIC)) return true
        }
        return false
    }

    private fun matches(bytes: ByteArray, offset: Int, magic: ByteArray): Boolean {
        if (bytes.size < offset + magic.size) return false
        for (i in magic.indices) {
            if (bytes[offset + i] != magic[i]) return false
        }
        return true
    }

    private fun asciiAt(bytes: ByteArray, offset: Int, length: Int): String {
        if (offset < 0 || bytes.size < offset + length) return ""
        val chars = CharArray(length) { bytes[offset + it].toInt().toChar() }
        return String(chars)
    }
}
