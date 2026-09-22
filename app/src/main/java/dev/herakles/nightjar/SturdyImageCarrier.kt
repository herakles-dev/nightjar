package dev.herakles.nightjar

import java.util.zip.CRC32

/**
 * Module 1 — SFLY "sturdy" image firefly codec (nightjar v6, task W1-1, spec.md gate-27).
 *
 * A second Module-1 image technique, sibling to the frozen exact-LSB [ImageStegoCarrier]
 * (INV-9) — not a change to it. Where exact-LSB needs a byte-perfect PNG, sturdy carries a
 * small payload that survives what messaging apps do to photos: JPEG recompression,
 * downscaling, 4:2:0 chroma subsampling and metadata stripping. Every constant below is the
 * round-2 shipped, measured design from `architecture.md` "Sturdy image technique (v6)" —
 * this class ports it, it does not re-derive it; see that doc for the offline measurement
 * matrix and the visibility/survival trade-off it records.
 *
 * ## Scheme (dither-QIM on a logical luminance grid)
 *  1. The image is reduced to luminance (`Y = 0.299R + 0.587G + 0.114B`). Chroma (Cb/Cr) is left
 *     untouched because messaging apps subsample it 4:2:0 — only luma survives reliably.
 *  2. A logical [GX] x [GY] grid is laid over the image *relative to its dimensions* — cell
 *     `(gx, gy)` owns source columns `[gx*W/GX, (gx+1)*W/GX)` and the matching rows. A uniform
 *     downscale keeps this mapping, so [decode] just recomputes the same grid on whatever
 *     dimensions it receives and reads each cell's **mean luminance**; no registration, no side
 *     channel.
 *  3. Each coded bit is embedded into one cell's mean luminance by dither Quantization Index
 *     Modulation: bit 0 snaps the mean to the lattice `{k*DELTA}`, bit 1 to `{k*DELTA + DELTA/2}`.
 *     The whole cell is shifted by one flat luma delta to reach the target — a low-spatial-
 *     frequency change that JPEG's DC term and any resampler preserve, and that the eye tolerates
 *     far better than the same energy as high-frequency noise.
 *  4. The payload is wrapped in a self-describing frame (own magic, distinct from exact-LSB's
 *     `0x4E` — INV-9/INV-12), protected by Reed-Solomon over GF(256) (this app's existing
 *     [ReedSolomon]/[GF256] — no second RS implementation), and each coded bit is repeated [REP]
 *     times, its copies dispersed across the grid by a fixed interleave so a JPEG-block burst
 *     only nicks each symbol a little. [decode] soft-combines the copies, hard-decides, then
 *     RS-corrects; RS + the frame's CRC-32 are the safety net that makes a damaged image decode
 *     to "damaged", never to a wrong message.
 *  5. **DC-offset search.** JPEG/resampling can shift absolute luminance, biasing every QIM
 *     decision the same way. [decode] tries a small grid of DC-offset hypotheses (fractions of
 *     [DELTA]) and accepts the first whose RS decode **and** frame CRC-32 both pass (false accept
 *     odds ~2^-32 per trial, from the CRC alone).
 *
 * ## Frame (before FEC)
 * ```
 *   magic     2 bytes  0x53 0x46  ('S','F' — distinct from exact-LSB's 0x4E)
 *   version   1 byte   0x01
 *   length    2 bytes  the REAL message length n, big-endian, 0 <= n <= PAYLOAD_BYTES
 *   slot      N bytes  (N == PAYLOAD_BYTES this version — the SLOT is fixed size, so decoder
 *                        geometry needs no side info; the message occupies the first n bytes,
 *                        the remaining PAYLOAD_BYTES - n bytes are zero-padded)
 *   crc32     4 bytes  CRC-32 (IEEE) over magic+version+length+the WHOLE slot (padding included),
 *                        big-endian
 * ```
 * Frame = 5 + [PAYLOAD_BYTES] + 4 = [FRAME_BYTES] bytes, RS-encoded as one systematic codeword
 * `RS(FRAME_BYTES + RS_PARITY, FRAME_BYTES)`, correcting up to `RS_PARITY / 2` byte errors.
 *
 * The slot's fixed size keeps the FEC/interleave geometry constant regardless of message length
 * (task W2-7); only the length field and the content of the first n bytes vary. Padding with
 * zero bytes (not truncating the frame) means a flipped padding bit still lands under the same
 * whole-slot CRC-32 as the message bytes, so a damaged pad reads as [DecodeFailure.INTEGRITY_MISMATCH]
 * (Damaged), never silently as a shorter message.
 *
 * ## Never returns a wrong payload (INV-12)
 * [decode] only ever returns [DecodeResult.Success] once RS decode succeeds *and* the frame's own
 * CRC-32 matches — both gates have to pass. Everything else resolves to [DecodeResult.Failure]:
 * [DecodeFailure.INTEGRITY_MISMATCH] once any DC-offset hypothesis produced a plausible SF header
 * (magic + version + declared length all matched) whose payload nonetheless failed CRC — this is
 * [IncomingRouter]'s Damaged case — and [DecodeFailure.NO_PAYLOAD_FOUND] otherwise, when no
 * hypothesis ever got that far. The latter is the safe, non-guessing answer for ordinary images
 * and for attacks (crop/rotate/heavy filter) that scramble the grid enough that RS never even
 * converges on a plausible codeword — INV-12's "receiving never guesses": this carrier does not
 * claim "damaged" for data it never had positive evidence was ever a sturdy frame.
 *
 * ## Porting notes (task W1-1)
 * Pure Kotlin, no `android.*` — the Android send path lives in the small adapter file
 * (`BitmapPixelSurface`, `prepareSturdyCover`, `encodeSturdyJpeg`) so this class and its tests
 * stay plain-JVM. [encode] never mutates the [cover] passed to the constructor (matching
 * [ImageStegoCarrier]'s contract): it works on an internal [ArrayPixelSurface] copy and returns
 * that. The bundled prototype's own `Gf256`/`Rs` were deleted; this class calls the app's
 * existing [ReedSolomon] (identical GF, primitive polynomial 0x11d, `AcousticCarrier.kt`)
 * instead. The fixed LCG interleave ports as-is.
 */
class SturdyImageCarrier(private val cover: PixelSurface) : CovertCarrier<SturdyImageCarrier.PixelSurface> {

    override val descriptor = ModuleDescriptor(
        id = ModuleId.STURDY_IMAGE_CODEC,
        displayName = "Sturdy Image Firefly (SFLY)",
        domain = CarrierDomain.IMAGE,
        role = ModuleRole.CARRIER,
    )

    /** The SLOT is fixed this version (architecture.md: "fixed size keeps decoder geometry
     *  deterministic") — not capacity-derived from [cover] the way [ImageStegoCarrier]'s raw-LSB
     *  capacity is, since the grid size ([GX] x [GY]) is itself fixed, independent of the cover's
     *  actual pixel dimensions. The real message length is variable (task W2-7): [encode] accepts
     *  any payload from 0 to [PAYLOAD_BYTES] bytes, carried in the frame's own length field, so
     *  [maxPayloadBytes] here means "largest message this slot can hold," not "the only size
     *  accepted." */
    override val maxPayloadBytes: Int = PAYLOAD_BYTES

    /** Minimal pixel access this codec needs. ARGB int per pixel, `0xAARRGGBB`, alpha ignored —
     *  the Android adapter backs this with a `Bitmap`; tests back it with [ArrayPixelSurface]. */
    interface PixelSurface {
        val width: Int
        val height: Int
        fun getPixel(x: Int, y: Int): Int
        fun setPixel(x: Int, y: Int, argb: Int)
    }

    /** Plain in-memory [PixelSurface], backing [encode]'s working copy and used directly by
     *  pure-JVM tests (no `Bitmap`/Robolectric required). */
    class ArrayPixelSurface(override val width: Int, override val height: Int) : PixelSurface {
        private val pixels = IntArray((width.toLong() * height.toLong()).coerceAtLeast(0).toInt())
        override fun getPixel(x: Int, y: Int): Int = pixels[y * width + x]
        override fun setPixel(x: Int, y: Int, argb: Int) {
            pixels[y * width + x] = argb
        }

        companion object {
            /** Pixel-for-pixel copy of any [PixelSurface] — how [encode] avoids mutating [cover]. */
            fun copyOf(src: PixelSurface): ArrayPixelSurface {
                val out = ArrayPixelSurface(src.width, src.height)
                for (y in 0 until src.height) {
                    for (x in 0 until src.width) {
                        out.setPixel(x, y, src.getPixel(x, y))
                    }
                }
                return out
            }
        }
    }

    /** Embeds [payload] into a mutable copy of [cover] and returns that copy; [cover] itself is
     *  never mutated (matches [ImageStegoCarrier]'s contract). [payload] may be any length from
     *  0 to [PAYLOAD_BYTES] (task W2-7, variable-length messages) — a shorter message is
     *  zero-padded out to the fixed slot size internally by [frame]; the slot geometry never
     *  changes, only the frame's own length field and how many of the slot's bytes are "real". */
    override fun encode(payload: ByteArray): PixelSurface {
        require(payload.size in 0..PAYLOAD_BYTES) {
            "sturdy payload must be between 0 and $PAYLOAD_BYTES bytes (was ${payload.size}) -- " +
                "the fixed-size slot keeps decoder geometry deterministic (architecture.md " +
                "'Sturdy image technique (v6)', shipped parameters)"
        }
        val work = ArrayPixelSurface.copyOf(cover)
        val bits = codedBits(frame(payload))
        val map = interleave(GX * GY, bits.size, REP)
        val grid = cellMeans(work)
        val target = grid.means.copyOf()
        for (b in bits.indices) {
            val db = if (bits[b] == 0) 0.0 else DELTA / 2.0
            for (cell in map[b]) {
                val m = grid.means[cell]
                target[cell] = DELTA * Math.round((m - db) / DELTA) + db
            }
        }
        // Apply one flat luma delta per cell -- a low-frequency change, not per-pixel noise.
        for (gy in 0 until GY) {
            for (gx in 0 until GX) {
                val cell = gy * GX + gx
                val d = target[cell] - grid.means[cell]
                if (d == 0.0) continue
                for (y in grid.rowB[gy] until grid.rowB[gy + 1]) {
                    for (x in grid.colB[gx] until grid.colB[gx + 1]) {
                        work.setPixel(x, y, shiftLuma(work.getPixel(x, y), d))
                    }
                }
            }
        }
        return work
    }

    /** Attempts to recover an SF frame from [carrier] (possibly recompressed/resized/cropped).
     *  See the class KDoc's "Never returns a wrong payload" section for the failure mapping. */
    override fun decode(carrier: PixelSurface): DecodeResult {
        if (carrier.width <= 0 || carrier.height <= 0) {
            return DecodeResult.Failure(DecodeFailure.NO_PAYLOAD_FOUND, "empty carrier")
        }
        val coded = FRAME_BYTES + RS_PARITY
        val nbits = coded * 8
        val map = interleave(GX * GY, nbits, REP)
        val grid = cellMeans(carrier)

        var sawPlausibleHeader = false
        for (g in DC_HYPOTHESES) {
            val soft = DoubleArray(nbits)
            for (b in 0 until nbits) {
                var acc = 0.0
                for (cell in map[b]) {
                    val m = grid.means[cell] - g * DELTA
                    val q0 = DELTA * Math.round(m / DELTA)
                    val q1 = DELTA * Math.round((m - DELTA / 2.0) / DELTA) + DELTA / 2.0
                    acc += Math.abs(m - q0) - Math.abs(m - q1) // > 0 => closer to the 1-lattice
                }
                soft[b] = acc
            }
            val codedBytes = ByteArray(coded)
            var bi = 0
            for (i in 0 until coded) {
                var v = 0
                for (k in 0 until 8) {
                    v = (v shl 1) or (if (soft[bi++] > 0) 1 else 0)
                }
                codedBytes[i] = v.toByte()
            }
            val rs = ReedSolomon.decode(codedBytes, RS_PARITY) ?: continue
            val parsed = parseFrame(rs.data)
            if (parsed.sawPlausibleHeader) sawPlausibleHeader = true
            if (parsed.payload != null) {
                return DecodeResult.Success(payload = parsed.payload, correctedByteErrors = rs.correctedErrors)
            }
        }

        return if (sawPlausibleHeader) {
            DecodeResult.Failure(
                DecodeFailure.INTEGRITY_MISMATCH,
                "SF header (magic+version+length) verified under a DC-offset hypothesis, but the " +
                    "frame CRC-32 never matched -- treated as damaged, not guessed at",
            )
        } else {
            DecodeResult.Failure(DecodeFailure.NO_PAYLOAD_FOUND, "no SF magic found under any DC-offset hypothesis")
        }
    }

    // ---------- frame assembly/parsing ----------

    /** Builds the fixed-size (`5 + [PAYLOAD_BYTES] + 4` = [FRAME_BYTES]-byte) frame for [payload]
     *  (0..[PAYLOAD_BYTES] bytes): the length field carries the real message length `n`, the slot
     *  is [payload] followed by `PAYLOAD_BYTES - n` zero bytes, and the CRC-32 covers
     *  magic+version+length+the whole zero-padded slot -- so a flipped padding bit fails CRC too
     *  (task W2-7's "zero-padding under the CRC" gate). `internal`, not `private`, so
     *  `SturdyImageCarrierTest` can build/corrupt frames directly without going through the
     *  QIM/RS channel -- same "internal for direct unit testing" precedent as
     *  `ImageSteganalysis.chiSquarePValueForWindow`. */
    internal fun frame(payload: ByteArray): ByteArray {
        val n = payload.size
        val slot = ByteArray(PAYLOAD_BYTES)
        System.arraycopy(payload, 0, slot, 0, n)
        val head = byteArrayOf(MAGIC0, MAGIC1, VERSION, ((n ushr 8) and 0xFF).toByte(), (n and 0xFF).toByte())
        val core = head + slot
        val crc = CRC32().apply { update(core) }.value
        val tail = byteArrayOf(
            ((crc ushr 24) and 0xFF).toByte(),
            ((crc ushr 16) and 0xFF).toByte(),
            ((crc ushr 8) and 0xFF).toByte(),
            (crc and 0xFF).toByte(),
        )
        return core + tail
    }

    /** [sawPlausibleHeader] is true once magic+version+declared-length all check out, even if the
     *  CRC then fails -- the signal [decode] uses to tell Damaged apart from NoFirefly. `internal`
     *  alongside [parseFrame], for the same direct-unit-test reason as [frame]. */
    internal class ParsedFrame(val payload: ByteArray?, val sawPlausibleHeader: Boolean)

    /** Parses a candidate [FRAME_BYTES]-byte frame body. `n` (0..[PAYLOAD_BYTES]) is read from the
     *  length field; a forged header claiming `n > PAYLOAD_BYTES` is rejected outright (task
     *  W2-7's "n > 64 in a forged header is rejected") before any CRC work. The CRC-32 is always
     *  verified over the WHOLE [PAYLOAD_BYTES]-byte slot (matching [frame]), not just the first
     *  `n` bytes, so a corrupted padding byte fails CRC exactly like a corrupted message byte --
     *  only once that whole-slot CRC passes does this return the first `n` bytes as the real
     *  message. */
    internal fun parseFrame(body: ByteArray): ParsedFrame {
        if (body.size < FRAME_BYTES) return ParsedFrame(null, false)
        if (body[0] != MAGIC0 || body[1] != MAGIC1 || body[2] != VERSION) return ParsedFrame(null, false)
        val n = ((body[3].toInt() and 0xFF) shl 8) or (body[4].toInt() and 0xFF)
        if (n < 0 || n > PAYLOAD_BYTES) return ParsedFrame(null, false)
        val core = body.copyOfRange(0, 5 + PAYLOAD_BYTES)
        val stored = ((body[5 + PAYLOAD_BYTES].toInt() and 0xFF).toLong() shl 24) or
            ((body[6 + PAYLOAD_BYTES].toInt() and 0xFF).toLong() shl 16) or
            ((body[7 + PAYLOAD_BYTES].toInt() and 0xFF).toLong() shl 8) or
            (body[8 + PAYLOAD_BYTES].toInt() and 0xFF).toLong()
        val computed = CRC32().apply { update(core) }.value
        if (computed != stored) return ParsedFrame(null, true)
        return ParsedFrame(body.copyOfRange(5, 5 + n), true)
    }

    private fun codedBits(frameBytes: ByteArray): IntArray {
        val coded = ReedSolomon.encode(frameBytes, RS_PARITY)
        val bits = IntArray(coded.size * 8)
        var i = 0
        for (byte in coded) {
            val v = byte.toInt() and 0xFF
            for (k in 0 until 8) bits[i++] = (v ushr (7 - k)) and 1
        }
        return bits
    }

    // ---------- geometry ----------

    private class Grid(val means: DoubleArray, val colB: IntArray, val rowB: IntArray)

    /** Recomputes the [GX] x [GY] logical grid *relative to [s]'s current dimensions* and each
     *  cell's mean luminance -- the same computation [encode] and [decode] share, so a uniform
     *  downscale of the whole image keeps every cell's pixel membership consistent. */
    private fun cellMeans(s: PixelSurface): Grid {
        val w = s.width
        val h = s.height
        val colB = IntArray(GX + 1) { ((it.toLong() * w) / GX).toInt() }
        val rowB = IntArray(GY + 1) { ((it.toLong() * h) / GY).toInt() }
        val means = DoubleArray(GX * GY)
        val counts = IntArray(GX * GY)
        val gxOf = IntArray(w)
        run {
            var gx = 0
            for (x in 0 until w) {
                while (gx < GX - 1 && x >= colB[gx + 1]) gx++
                gxOf[x] = gx
            }
        }
        val gyOf = IntArray(h)
        run {
            var gy = 0
            for (y in 0 until h) {
                while (gy < GY - 1 && y >= rowB[gy + 1]) gy++
                gyOf[y] = gy
            }
        }
        for (y in 0 until h) {
            val gy = gyOf[y]
            for (x in 0 until w) {
                val p = s.getPixel(x, y)
                val r = (p ushr 16) and 0xFF
                val gg = (p ushr 8) and 0xFF
                val b = p and 0xFF
                val yl = 0.299 * r + 0.587 * gg + 0.114 * b
                val cell = gy * GX + gxOf[x]
                means[cell] += yl
                counts[cell]++
            }
        }
        for (c in means.indices) if (counts[c] > 0) means[c] /= counts[c]
        return Grid(means, colB, rowB)
    }

    /** Shifts luminance by [dY]: adding the same delta to R, G and B moves Y by that delta and
     *  leaves Cb/Cr (which are channel *differences*) exactly where they were. */
    private fun shiftLuma(argb: Int, dY: Double): Int {
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val nr = clampByte(r + dY)
        val ng = clampByte(g + dY)
        val nb = clampByte(b + dY)
        return (a shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    private fun clampByte(v: Double): Int {
        val i = Math.round(v).toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }

    /** Deterministic dispersal: coded-bit `b` owns [rep] cells out of [ncells], spread by a fixed
     *  LCG Fisher-Yates shuffle -- so a localized JPEG-block burst only nicks a few of any one
     *  bit's copies rather than wiping it out. */
    private fun interleave(ncells: Int, nbits: Int, rep: Int): Array<IntArray> {
        val slots = nbits * rep
        check(slots <= ncells) { "grid too small: need $slots cells, have $ncells" }
        val perm = IntArray(ncells) { it }
        var state = INTERLEAVE_SEED
        for (i in ncells - 1 downTo 1) {
            state = state * 6364136223846793005uL + 1442695040888963407uL
            val j = ((state shr 33).toLong() % (i + 1)).toInt()
            val t = perm[i]
            perm[i] = perm[j]
            perm[j] = t
        }
        val out = Array(nbits) { IntArray(rep) }
        val fill = IntArray(nbits)
        for (sIdx in 0 until slots) {
            val bit = sIdx % nbits
            out[bit][fill[bit]++] = perm[sIdx]
        }
        return out
    }

    companion object {
        // ---- shipped v6 round-2 parameters (measured; architecture.md "Sturdy image technique
        // (v6)" / "Shipped parameters (round-2 retune, measured)") ----

        /** Logical grid width in cells. 96x96 = 9216 cells; enough for [REP]=8 at this payload
         *  size, and at a 640px long side each cell is ~7px (~50px averaged) -- small enough to
         *  survive the DC/JPEG path (architecture.md). */
        const val GX = 96

        /** Logical grid height in cells (see [GX]). */
        const val GY = 96

        /** QIM luma step. Round 1 shipped 24 and was visibly blocky; 10 is the lowest step that
         *  still cleared MMS-like on every cover under both measured JPEG encoders while cutting
         *  the artifact hard (PSNR 31.6->39.6 dB, max luma delta 12->5 -- architecture.md). */
        const val DELTA = 10.0

        /** RS parity bytes: `RS(FRAME_BYTES + RS_PARITY, FRAME_BYTES)`, correcting up to
         *  `RS_PARITY / 2` = 24 byte errors -- a safety net on top of the repetition. */
        const val RS_PARITY = 48

        /** Each coded bit's copies dispersed across the grid by [interleave]. Soft-combining 8
         *  copies is what lets [DELTA] drop to 10 and still survive q50 (architecture.md). */
        const val REP = 8

        /** Slot size in bytes -- fixed this version, so decoder geometry stays deterministic (no
         *  side channel needed). The real message length is variable, 0..[PAYLOAD_BYTES] (task
         *  W2-7), carried in the frame's own length field; [PAYLOAD_BYTES] is the largest message
         *  the slot can hold, not the only size [encode] accepts. */
        const val PAYLOAD_BYTES = 64

        /** `magic(2) + version(1) + length(2) + payload(PAYLOAD_BYTES) + crc32(4)`. */
        const val FRAME_BYTES = 5 + PAYLOAD_BYTES + 4

        /** Frame magic byte 0, 'S' -- deliberately distinct from exact-LSB's `0x4E` (INV-9/INV-12). */
        private const val MAGIC0: Byte = 0x53

        /** Frame magic byte 1, 'F'. */
        private const val MAGIC1: Byte = 0x46

        /** Frame version. */
        private const val VERSION: Byte = 0x01

        /** Seed for the fixed LCG Fisher-Yates shuffle behind [interleave] -- must never change
         *  once shipped (an encoder/decoder using different seeds could never agree on cell
         *  assignment). */
        private const val INTERLEAVE_SEED = 0x9E3779B97F4A7C15uL

        /** DC-offset hypotheses [decode] tries, as fractions of [DELTA] -- JPEG/resampling can
         *  shift absolute luminance, which would bias every QIM decision the same way. */
        private val DC_HYPOTHESES = doubleArrayOf(0.0, 0.25, -0.25, 0.5, -0.5, 0.125, -0.125, 0.375, -0.375)
    }
}
