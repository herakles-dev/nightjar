package dev.herakles.nightjar

import android.graphics.Bitmap
import java.util.zip.CRC32

/**
 * Module 1 — image LSB steganography codec (task #11).
 *
 * Hides a benign payload in the least-significant bit of each color channel (R, G, B — the
 * alpha channel is never touched) of a cover [Bitmap], one bit per channel, per the
 * "Least-significant-bit (LSB) embedding" technique in
 * `covert-data/library/02_image_steganography.md`. This is the *raw-pixel* LSB technique from
 * that document, not the DCT-coefficient ("Javid steganography") variant that survives JPEG
 * re-compression — Module 1's scope here is raw-pixel LSB encode/decode plus its steganalysis
 * counterpart (task #12), so this codec only guarantees a lossless round trip through lossless
 * bitmap handling (spec.md "In scope").
 *
 * ## Framing (analogous to the acoustic protocol's framing, architecture.md §5)
 *
 * The embedded bitstream is a self-describing frame so [decode] can distinguish "no payload
 * present" (an ordinary, un-embedded image) from "a payload is present but corrupt", per the
 * `CovertCarrier` contract ("decode MUST NEVER return corrupt data as success"):
 *
 * ```
 * ┌───────────────────────────────────────────────────────────────────────┐
 * │ HEADER  (7 bytes)                                                       │
 * │   magic       1 byte   0x4E              ('N' — matches the acoustic    │
 * │                                            header's magic byte)         │
 * │   version     1 byte   0x01                                             │
 * │   length      4 bytes  payload byte count, big-endian                   │
 * │   header_crc  1 byte   CRC-8 (poly 0x07) over the 6 preceding bytes      │
 * ├───────────────────────────────────────────────────────────────────────┤
 * │ PAYLOAD  (0..maxPayloadBytes bytes benign data)                          │
 * ├───────────────────────────────────────────────────────────────────────┤
 * │ TRAILER  (4 bytes)                                                       │
 * │   payload_crc32  4 bytes  CRC-32 (IEEE 802.3) over the raw payload bytes │
 * └───────────────────────────────────────────────────────────────────────┘
 * ```
 *
 * The length field is 4 bytes (wider than the acoustic header's 2-byte field, which is capped
 * at the acoustic modem's fixed 1024-byte `MAX_PAYLOAD_BYTES`) because image capacity scales
 * with the cover image's dimensions rather than being capped at a fixed size
 * (architecture.md §2: "Image codec: capacity-derived from the bitmap").
 *
 * No forward error correction is applied: per the `CovertCarrier.DecodeResult.Success` KDoc,
 * this carrier always reports `correctedByteErrors = 0` (lossless image LSB has no FEC, unlike
 * the acoustic modem's Reed-Solomon).
 *
 * ## Capacity
 *
 * `maxPayloadBytes` is derived from the specific [coverImage] this instance was constructed
 * with: one bit per color channel (R, G, B; alpha is never touched) across every pixel, minus
 * the 11-byte header+trailer overhead:
 *
 * ```
 * totalCapacityBytes = floor(width * height * 3 / 8)
 * maxPayloadBytes     = max(0, totalCapacityBytes - 11)
 * ```
 *
 * [encode] always embeds into a mutable copy of [coverImage] and returns that copy;
 * [coverImage] itself is never mutated. [decode] operates on whichever [Bitmap] it is given
 * (typically, but not necessarily, one this instance produced) — it does not depend on
 * [coverImage] at all, matching the acoustic codec's symmetric `decode(carrier: PcmAudio)`.
 *
 * Operational note (library §02, Computerphile/Dr. Mike Pound): never release the unmodified
 * [coverImage] alongside a stego copy produced by [encode] — without a reference original,
 * raw-pixel LSB at this level is not detectable by eye, but a known original lets an observer
 * diff directly against it and read the embedding trivially. That is a deployment concern for
 * callers of this class, not something the codec itself can enforce.
 */
class ImageStegoCarrier(private val coverImage: Bitmap) : CovertCarrier<Bitmap> {

    override val descriptor = ModuleDescriptor(
        id = ModuleId.IMAGE_LSB_CODEC,
        displayName = "Image LSB Steganography",
        domain = CarrierDomain.IMAGE,
        role = ModuleRole.CARRIER,
    )

    private val totalCapacityBytes: Int = capacityBytes(coverImage.width, coverImage.height)

    override val maxPayloadBytes: Int = (totalCapacityBytes - FRAME_OVERHEAD_BYTES).coerceAtLeast(0)

    /**
     * True if [coverImage] has enough raw LSB capacity to hold even an empty-payload frame
     * ([FRAME_OVERHEAD_BYTES] bytes). False is a fundamentally different situation than
     * "[maxPayloadBytes] is 0 because the payload got trimmed to fit" — there is no payload
     * size, not even zero, this cover can actually carry a frame for. Before this property
     * existed (codec-H01), a caller could see `maxPayloadBytes == 0`, reasonably read that as
     * "an empty payload is fine," and still have [encode] throw from a second, buried capacity
     * check (`frame.size <= totalCapacityBytes`) it had no way to predict. [encode] now checks
     * this first, with a message that says so directly; callers (the screens) use it to show
     * "this cover is too small to hide anything" instead of a bare `0 / 0 bytes` counter.
     */
    val canEmbed: Boolean = totalCapacityBytes >= FRAME_OVERHEAD_BYTES

    override fun encode(payload: ByteArray): Bitmap {
        require(canEmbed) {
            "cover image (${coverImage.width}x${coverImage.height}, $totalCapacityBytes-byte " +
                "total capacity) cannot hold even an empty payload frame ($FRAME_OVERHEAD_BYTES " +
                "bytes) -- this cover is too small to hide anything"
        }
        require(payload.size <= maxPayloadBytes) {
            "payload of ${payload.size} bytes exceeds this ${coverImage.width}x${coverImage.height} " +
                "cover image's capacity of $maxPayloadBytes bytes"
        }
        val frame = buildFrame(payload)
        check(frame.size <= totalCapacityBytes) {
            "frame (${frame.size} bytes) exceeds total capacity ($totalCapacityBytes bytes) -- " +
                "should be unreachable once canEmbed and the payload-size check above both hold"
        }

        val stego = coverImage.copy(Bitmap.Config.ARGB_8888, /* isMutable = */ true)
        val totalBits = frame.size * 8
        var bitIndex = 0
        while (bitIndex < totalBits) {
            val pixelIndex = bitIndex / BITS_PER_PIXEL
            val x = pixelIndex % stego.width
            val y = pixelIndex / stego.width
            val pixel = stego.getPixel(x, y)
            val a = (pixel shr 24) and 0xFF
            var r = (pixel shr 16) and 0xFF
            var g = (pixel shr 8) and 0xFF
            var b = pixel and 0xFF

            when (bitIndex % BITS_PER_PIXEL) {
                0 -> r = withLsb(r, bitAt(frame, bitIndex))
                1 -> g = withLsb(g, bitAt(frame, bitIndex))
                else -> b = withLsb(b, bitAt(frame, bitIndex))
            }
            stego.setPixel(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
            bitIndex++
        }
        return stego
    }

    override fun decode(carrier: Bitmap): DecodeResult {
        val carrierCapacityBytes = capacityBytes(carrier.width, carrier.height)
        if (carrierCapacityBytes < HEADER_BYTES) {
            return DecodeResult.Failure(
                DecodeFailure.NO_PAYLOAD_FOUND,
                "carrier (${carrier.width}x${carrier.height}) is too small to hold a header",
            )
        }

        val header = extractBytes(carrier, 0, HEADER_BYTES)
        if ((header[0].toInt() and 0xFF) != MAGIC) {
            return DecodeResult.Failure(DecodeFailure.NO_PAYLOAD_FOUND, "no stego magic byte found")
        }
        if ((header[1].toInt() and 0xFF) != VERSION) {
            return DecodeResult.Failure(
                DecodeFailure.HEADER_INVALID,
                "unsupported frame version ${header[1].toInt() and 0xFF}",
            )
        }
        val declaredHeaderCrc = header[6].toInt() and 0xFF
        val computedHeaderCrc = crc8(header, 0, 6)
        if (declaredHeaderCrc != computedHeaderCrc) {
            return DecodeResult.Failure(DecodeFailure.HEADER_INVALID, "header CRC-8 mismatch")
        }

        val length = ((header[2].toInt() and 0xFF) shl 24) or
            ((header[3].toInt() and 0xFF) shl 16) or
            ((header[4].toInt() and 0xFF) shl 8) or
            (header[5].toInt() and 0xFF)
        if (length < 0) {
            return DecodeResult.Failure(DecodeFailure.HEADER_INVALID, "negative declared length $length")
        }
        val maxPayloadForCarrier = (carrierCapacityBytes - FRAME_OVERHEAD_BYTES).coerceAtLeast(0)
        if (length > maxPayloadForCarrier) {
            return DecodeResult.Failure(
                DecodeFailure.PAYLOAD_TOO_LARGE,
                "declared length $length exceeds this carrier's $maxPayloadForCarrier-byte capacity",
            )
        }

        val payload = extractBytes(carrier, HEADER_BYTES * 8, length)
        val trailer = extractBytes(carrier, (HEADER_BYTES + length) * 8, TRAILER_BYTES)
        val declaredCrc32 = ((trailer[0].toLong() and 0xFF) shl 24) or
            ((trailer[1].toLong() and 0xFF) shl 16) or
            ((trailer[2].toLong() and 0xFF) shl 8) or
            (trailer[3].toLong() and 0xFF)
        val actualCrc32 = crc32Of(payload)
        if (declaredCrc32 != actualCrc32) {
            return DecodeResult.Failure(DecodeFailure.INTEGRITY_MISMATCH, "payload CRC-32 mismatch")
        }

        return DecodeResult.Success(payload = payload, correctedByteErrors = 0)
    }

    // --- Frame assembly ---

    private fun buildFrame(payload: ByteArray): ByteArray {
        val header = ByteArray(HEADER_BYTES)
        header[0] = MAGIC.toByte()
        header[1] = VERSION.toByte()
        header[2] = (payload.size ushr 24).toByte()
        header[3] = (payload.size ushr 16).toByte()
        header[4] = (payload.size ushr 8).toByte()
        header[5] = payload.size.toByte()
        header[6] = crc8(header, 0, 6).toByte()

        val crc32 = crc32Of(payload)
        val trailer = byteArrayOf(
            (crc32 ushr 24).toByte(),
            (crc32 ushr 16).toByte(),
            (crc32 ushr 8).toByte(),
            crc32.toByte(),
        )
        return header + payload + trailer
    }

    // --- Bit-level pixel I/O (shared indexing scheme between encode and decode) ---

    /**
     * Reads [numBytes] bytes ([startBit]-aligned) via ONE bulk [Bitmap.getPixels] call over the
     * covering row range, not one [Bitmap.getPixel] JNI call per bit (gate-41 safety re-audit,
     * finding F-5). [decode] calls this at most three times (header, payload, trailer) per
     * attempt, but a crafted header can declare a payload length up to this carrier's own
     * capacity -- tens of megabytes -- which used to mean tens of millions of individual native
     * calls (each pixel re-fetched up to [BITS_PER_PIXEL] times, once per bit sharing it) before
     * the length was ever checked against the actual payload's CRC-32.
     */
    private fun extractBytes(carrier: Bitmap, startBit: Int, numBytes: Int): ByteArray {
        val out = ByteArray(numBytes)
        if (numBytes == 0) return out

        val width = carrier.width
        val totalBits = numBytes * 8
        val startPixelIndex = startBit / BITS_PER_PIXEL
        val endPixelIndexExclusive = (startBit + totalBits + BITS_PER_PIXEL - 1) / BITS_PER_PIXEL
        val startY = startPixelIndex / width
        val endYInclusive = (endPixelIndexExclusive - 1) / width
        val rowCount = endYInclusive - startY + 1

        val pixels = IntArray(width * rowCount)
        carrier.getPixels(pixels, 0, width, 0, startY, width, rowCount)
        val rowOffsetPixelIndex = startY * width

        var bitIndex = startBit
        for (i in 0 until numBytes) {
            var value = 0
            repeat(8) {
                val pixel = pixels[bitIndex / BITS_PER_PIXEL - rowOffsetPixelIndex]
                val channelValue = when (bitIndex % BITS_PER_PIXEL) {
                    0 -> (pixel shr 16) and 0xFF
                    1 -> (pixel shr 8) and 0xFF
                    else -> pixel and 0xFF
                }
                value = (value shl 1) or (channelValue and 0x01)
                bitIndex++
            }
            out[i] = value.toByte()
        }
        return out
    }

    private fun withLsb(channelValue: Int, bit: Int): Int = (channelValue and 0xFE) or bit

    private fun bitAt(bytes: ByteArray, bitIndex: Int): Int {
        val byteIndex = bitIndex / 8
        val bitInByte = 7 - (bitIndex % 8)
        return (bytes[byteIndex].toInt() ushr bitInByte) and 1
    }

    // --- Checksums ---

    /** CRC-8, poly 0x07, init 0x00, MSB-first — same polynomial as the acoustic header_crc. */
    private fun crc8(bytes: ByteArray, offset: Int, length: Int): Int {
        var crc = 0
        for (i in offset until offset + length) {
            crc = crc xor (bytes[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) {
                    ((crc shl 1) xor HEADER_CRC8_POLY) and 0xFF
                } else {
                    (crc shl 1) and 0xFF
                }
            }
        }
        return crc
    }

    /** CRC-32 (IEEE 802.3, poly 0x04C11DB7) via the JDK's standard implementation. */
    private fun crc32Of(bytes: ByteArray): Long {
        val crc32 = CRC32()
        crc32.update(bytes)
        return crc32.value
    }

    companion object {
        /** Bits embedded per pixel: one LSB each in R, G, B. Alpha is never touched. */
        private const val BITS_PER_PIXEL = 3

        /** Header magic byte 'N' — matches the acoustic header's magic (architecture.md §5). */
        private const val MAGIC = 0x4E

        /** Frame version. */
        private const val VERSION = 0x01

        /** Header size in bytes (magic, version, length[4], header_crc). */
        private const val HEADER_BYTES = 7

        /** Trailer size in bytes (payload_crc32). */
        private const val TRAILER_BYTES = 4

        /** Total non-payload overhead embedded alongside every payload. */
        private const val FRAME_OVERHEAD_BYTES = HEADER_BYTES + TRAILER_BYTES

        /** CRC-8 polynomial (0x07) protecting the header — same polynomial as the acoustic header. */
        private const val HEADER_CRC8_POLY = 0x07

        /** Total embeddable bytes for a `width`x`height` image: floor(width*height*3 / 8). */
        private fun capacityBytes(width: Int, height: Int): Int =
            ((width.toLong() * height.toLong() * BITS_PER_PIXEL) / 8).toInt()
    }
}
