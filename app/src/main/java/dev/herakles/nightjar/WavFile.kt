package dev.herakles.nightjar

/**
 * Task #31. Builds a standard, self-contained RIFF/WAVE file (44-byte canonical header +
 * raw little-endian PCM16 data, no extension chunks) from a [PcmAudio] sample array.
 *
 * Deliberately pure byte-array arithmetic — zero Android API references — so
 * `AcousticModemScreen.kt`'s "save"/"share" actions can hand this object's output straight to
 * `MediaStore.Audio` (Android side), while the header arithmetic itself is verifiable in a plain
 * JVM unit test (`WavFileTest.kt`) with no device/Robolectric dependency, matching how
 * `AcousticCarrier`/`ReedSolomon` in this same package keep their pure math separate from any
 * Android surface.
 *
 * Layout (all multi-byte integer fields little-endian, per the canonical WAV/RIFF spec):
 * ```
 * offset  size  field
 * 0       4     "RIFF"
 * 4       4     ChunkSize        = 36 + dataBytes
 * 8       4     "WAVE"
 * 12      4     "fmt "
 * 16      4     Subchunk1Size    = 16 (PCM)
 * 20      2     AudioFormat      = 1  (PCM, no compression)
 * 22      2     NumChannels      = 1  (mono)
 * 24      4     SampleRate
 * 28      4     ByteRate         = SampleRate * NumChannels * BitsPerSample/8
 * 32      2     BlockAlign       = NumChannels * BitsPerSample/8
 * 34      2     BitsPerSample    = 16
 * 36      4     "data"
 * 40      4     Subchunk2Size    = dataBytes (== pcm.size * 2)
 * 44      dataBytes  raw PCM16 samples, little-endian
 * ```
 *
 * Task #32 update: adds [decodePcm16], the inverse of [encodePcm16Mono] — parses an arbitrary
 * RIFF/WAVE PCM16 byte array back into sample rate, channel count, and raw samples, for
 * `AcousticModemScreen.kt`'s "import" action (pick an existing audio file from the device and run
 * it through `AcousticCarrier.decode()` instead of a live `AudioRecord` capture). Unlike
 * [encodePcm16Mono], which only ever emits the exact 44-byte canonical layout documented above, a
 * real device WAV file may carry extra chunks before `data` (`LIST`, `fact`, ...) — [decodePcm16]
 * walks the chunk list generically rather than assuming a fixed 44-byte offset.
 *
 * Update: adds [encodePcm16Stereo], a NumChannels=2 sibling of [encodePcm16Mono] for
 * [AudioStegoCarrier]'s PHASE_INVERSION technique, whose `encode()` output is an already
 * interleaved-stereo `PcmAudio` (see that class's KDoc) rather than mono. Both functions share
 * the same byte-assembly logic via the private [assemblePcm16Wav] helper, parameterized by
 * channel count, rather than duplicating the whole header/data-writing routine. [decodePcm16] did
 * not need any change for this — it already returns `numChannels` from the file's own header
 * rather than assuming mono.
 */
object WavFile {

    /** Canonical header size in bytes (RIFF descriptor + fmt subchunk + data subchunk header). */
    const val HEADER_BYTES: Int = 44

    private const val PCM_AUDIO_FORMAT: Int = 1

    /**
     * `AudioFormat` value marking a `fmt ` chunk as `WAVE_FORMAT_EXTENSIBLE` (codec-M02): the
     * real codec is deferred to a 16-byte `SubFormat` GUID inside the chunk body rather than
     * `AudioFormat` itself. Written by tools that need >2 channels or an explicit channel-mask
     * (and some Android/desktop recorders even for plain stereo/mono PCM16) — not an exotic
     * corner case, so [decodePcm16] accepts it exactly like [PCM_AUDIO_FORMAT] once the
     * `SubFormat` confirms it's really PCM. See [isPcmAudioFormat].
     */
    private const val WAVE_FORMAT_EXTENSIBLE: Int = 0xFFFE

    private const val MONO_CHANNELS: Int = 1
    private const val STEREO_CHANNELS: Int = 2
    private const val BITS_PER_SAMPLE: Int = 16
    private const val BYTES_PER_SAMPLE: Int = BITS_PER_SAMPLE / 8

    /**
     * Task #32. Result of a successful [decodePcm16]: the container's declared sample rate and
     * channel count, plus the raw interleaved PCM16 samples (still sign-extended, little-endian
     * decoded). Channel count is deliberately NOT validated/normalized here — [decodePcm16] is
     * pure container parsing; whether `numChannels`/`sampleRateHz` actually match what
     * `AcousticCarrier.decode()` requires (48kHz mono) is the caller's job.
     */
    data class ParsedWav(val sampleRateHz: Int, val numChannels: Int, val samples: ShortArray)

    /**
     * Encodes [pcm] as PCM16 mono at [sampleRateHz] into a complete .wav file byte array.
     * Accepts an empty [pcm] (produces a header-only, zero-length-data file rather than throwing
     * — mirrors [dev.herakles.nightjar.AcousticCarrier.encode]'s own "empty payload is valid"
     * contract).
     */
    fun encodePcm16Mono(pcm: PcmAudio, sampleRateHz: Int): ByteArray =
        assemblePcm16Wav(pcm, sampleRateHz, numChannels = MONO_CHANNELS)

    /**
     * Encodes [pcm] — an already interleaved-stereo PCM16 buffer (`pcm[2*i]` = left channel,
     * `pcm[2*i+1]` = right channel) at [sampleRateHz] per channel — into a complete .wav file
     * byte array with `NumChannels = 2`. Added for [AudioStegoCarrier]'s PHASE_INVERSION
     * technique: that codec's `encode()` returns a dual-mono, phase-inverted stereo signal (see
     * its class KDoc) rather than mono, so its "save as WAV" path needs a real stereo header
     * instead of [encodePcm16Mono]'s hardcoded `NumChannels = 1`. The interleaving convention
     * itself is [AudioStegoCarrier]'s own private contract — this function only needs the
     * channel count to put in the header; the byte layout is otherwise identical to
     * [encodePcm16Mono]'s. Accepts an odd-length [pcm] without validation (this is pure container
     * writing, not a stego-frame check) — an odd length simply means the last written sample
     * ends up alone in what would be an incomplete final L/R pair, same as any other malformed
     * input this object doesn't attempt to reject.
     */
    fun encodePcm16Stereo(pcm: PcmAudio, sampleRateHz: Int): ByteArray =
        assemblePcm16Wav(pcm, sampleRateHz, numChannels = STEREO_CHANNELS)

    /**
     * Shared RIFF/WAVE PCM16 byte-assembly routine behind both [encodePcm16Mono] and
     * [encodePcm16Stereo] — the two differ only in the `NumChannels` header field (and the
     * derived `ByteRate`/`BlockAlign` fields), never in how the bytes themselves get laid out, so
     * this is parameterized by [numChannels] rather than duplicated.
     */
    private fun assemblePcm16Wav(pcm: ShortArray, sampleRateHz: Int, numChannels: Int): ByteArray {
        val dataBytes = pcm.size * BYTES_PER_SAMPLE
        val byteRate = sampleRateHz * numChannels * BYTES_PER_SAMPLE
        val blockAlign = numChannels * BYTES_PER_SAMPLE
        val riffChunkSize = 36 + dataBytes // 4("WAVE") + 8+16("fmt " subchunk) + 8("data" header) + dataBytes

        val out = ByteArray(HEADER_BYTES + dataBytes)
        var pos = 0
        pos = writeAscii(out, pos, "RIFF")
        pos = writeLE32(out, pos, riffChunkSize)
        pos = writeAscii(out, pos, "WAVE")
        pos = writeAscii(out, pos, "fmt ")
        pos = writeLE32(out, pos, 16)
        pos = writeLE16(out, pos, PCM_AUDIO_FORMAT)
        pos = writeLE16(out, pos, numChannels)
        pos = writeLE32(out, pos, sampleRateHz)
        pos = writeLE32(out, pos, byteRate)
        pos = writeLE16(out, pos, blockAlign)
        pos = writeLE16(out, pos, BITS_PER_SAMPLE)
        pos = writeAscii(out, pos, "data")
        pos = writeLE32(out, pos, dataBytes)
        for (sample in pcm) {
            pos = writeLE16(out, pos, sample.toInt())
        }
        check(pos == out.size) { "WAV header/data assembly wrote $pos bytes, expected ${out.size}" }
        return out
    }

    private fun writeAscii(out: ByteArray, pos: Int, tag: String): Int {
        for (i in tag.indices) {
            out[pos + i] = tag[i].code.toByte()
        }
        return pos + tag.length
    }

    private fun writeLE32(out: ByteArray, pos: Int, value: Int): Int {
        out[pos] = (value and 0xFF).toByte()
        out[pos + 1] = ((value ushr 8) and 0xFF).toByte()
        out[pos + 2] = ((value ushr 16) and 0xFF).toByte()
        out[pos + 3] = ((value ushr 24) and 0xFF).toByte()
        return pos + 4
    }

    private fun writeLE16(out: ByteArray, pos: Int, value: Int): Int {
        out[pos] = (value and 0xFF).toByte()
        out[pos + 1] = ((value ushr 8) and 0xFF).toByte()
        return pos + 2
    }

    /**
     * Task #32. Parses [bytes] as a RIFF/WAVE PCM16 file — the inverse of [encodePcm16Mono].
     * Walks the chunk list generically (`fmt `/`data` may appear in either order, with other
     * chunks — `LIST`, `fact`, ... — interleaved; every RIFF chunk is word-aligned, so an
     * odd-sized chunk carries one pad byte after it), rather than assuming the fixed 44-byte
     * layout [encodePcm16Mono] itself always produces.
     *
     * Returns `null` (never throws) for anything that isn't a usable PCM16 carrier: not a
     * RIFF/WAVE file, no `fmt `/`data` chunk found, a non-PCM `fmt ` (`AudioFormat` neither `1`
     * nor a `WAVE_FORMAT_EXTENSIBLE` (`0xFFFE`) `fmt ` chunk whose `SubFormat` GUID is the PCM
     * one — see [isPcmAudioFormat]), or a bit depth other than 16 — `AcousticCarrier.decode()`
     * only ever operates on PCM16 samples (architecture.md §1). A *correct* PCM16 file at the
     * wrong sample rate or channel count still parses successfully here — that check belongs to
     * the caller (`AcousticModemScreen.kt`'s import path), which needs the real numbers to report
     * a useful "file is Xhz Y, need 48000Hz mono" message rather than a bare parse failure.
     *
     * `data`'s declared size is clamped to what's actually present in [bytes] — a capture cut off
     * mid-write (or a file truncated in transit) still parses whatever audio survived, rather
     * than throwing.
     */
    fun decodePcm16(bytes: ByteArray): ParsedWav? {
        if (bytes.size < 12 || readAscii(bytes, 0, 4) != "RIFF" || readAscii(bytes, 8, 4) != "WAVE") {
            return null
        }

        var pos = 12
        var audioFormat: Int? = null
        var numChannels: Int? = null
        var sampleRateHz: Int? = null
        var bitsPerSample: Int? = null
        var subFormatIsPcm = false
        var dataOffset = -1
        var dataSize = -1

        while (pos + 8 <= bytes.size) {
            val chunkId = readAscii(bytes, pos, 4)
            val declaredSize = readLE32(bytes, pos + 4)
            val chunkDataStart = pos + 8
            if (declaredSize < 0) break // corrupt/overflowing chunk size -- stop, use whatever was already found

            when (chunkId) {
                "fmt " -> if (chunkDataStart + 16 <= bytes.size) {
                    audioFormat = readLE16(bytes, chunkDataStart)
                    numChannels = readLE16(bytes, chunkDataStart + 2)
                    sampleRateHz = readLE32(bytes, chunkDataStart + 4)
                    bitsPerSample = readLE16(bytes, chunkDataStart + 14)
                    // WAVE_FORMAT_EXTENSIBLE (codec-M02): the real codec lives in the 16-byte
                    // SubFormat GUID at fmt-body offset 24 (chunkDataStart + 24), not in
                    // AudioFormat itself (always 0xFFFE for this variant). Only the GUID's
                    // leading 2 bytes distinguish KSDATAFORMAT_SUBTYPE_PCM (0x0001) from every
                    // other subtype -- the trailing 14 bytes are the same fixed
                    // "...0000-0010-8000-00AA00389B71" suffix for every Microsoft-defined
                    // subtype, so comparing just those 2 bytes is sufficient and matches how the
                    // GUID's own leading Data1 field encodes the format tag.
                    subFormatIsPcm = chunkDataStart + 26 <= bytes.size &&
                        readLE16(bytes, chunkDataStart + 24) == PCM_AUDIO_FORMAT
                }
                "data" -> {
                    dataOffset = chunkDataStart
                    // A capture cut off mid-write can have fewer bytes than `data`'s own declared
                    // size -- clamp to what's actually present rather than reading past `bytes`.
                    dataSize = minOf(declaredSize, maxOf(0, bytes.size - chunkDataStart))
                }
            }

            val advance = declaredSize + (declaredSize and 1) // word-aligned: odd sizes carry a pad byte
            val next = chunkDataStart + advance
            if (next <= pos || next > bytes.size) break // non-advancing or past-EOF chunk -- stop here
            pos = next
        }

        if (!isPcmAudioFormat(audioFormat, subFormatIsPcm) || bitsPerSample != 16 ||
            dataOffset < 0 || dataSize < 0
        ) {
            return null
        }
        val channels = numChannels ?: return null
        val rate = sampleRateHz ?: return null
        if (channels <= 0 || rate <= 0) return null

        val sampleCount = dataSize / 2
        val samples = ShortArray(sampleCount)
        for (i in 0 until sampleCount) {
            samples[i] = readLE16(bytes, dataOffset + i * 2).toShort()
        }
        return ParsedWav(sampleRateHz = rate, numChannels = channels, samples = samples)
    }

    /**
     * True if a `fmt ` chunk whose `AudioFormat` field is [audioFormat] carries genuine PCM16
     * data: either the plain [PCM_AUDIO_FORMAT] (`1`) tag, or [WAVE_FORMAT_EXTENSIBLE] (`0xFFFE`)
     * with [subFormatIsPcm] confirming the `SubFormat` GUID is `KSDATAFORMAT_SUBTYPE_PCM`
     * (codec-M02). `null` (no `fmt ` chunk found, or too short to read) is never PCM.
     */
    private fun isPcmAudioFormat(audioFormat: Int?, subFormatIsPcm: Boolean): Boolean =
        audioFormat == PCM_AUDIO_FORMAT || (audioFormat == WAVE_FORMAT_EXTENSIBLE && subFormatIsPcm)

    private fun readAscii(bytes: ByteArray, pos: Int, length: Int): String {
        val chars = CharArray(length) { bytes[pos + it].toInt().toChar() }
        return String(chars)
    }

    private fun readLE16(bytes: ByteArray, pos: Int): Int =
        (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)

    private fun readLE32(bytes: ByteArray, pos: Int): Int =
        (bytes[pos].toInt() and 0xFF) or
            ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
            ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
            ((bytes[pos + 3].toInt() and 0xFF) shl 24)
}
