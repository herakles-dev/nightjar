package dev.herakles.nightjar

import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Wire-format pins for [AcousticCarrier]. A test-side reference modulator (written from the
 * protocol description, not from the production code paths) builds the exact PCM a spec-compliant
 * transmitter emits, so:
 *  - [AcousticCarrier.encode] is checked sample-for-sample against it;
 *  - hand-damaged transmissions (bad header fields, RS errors, wrong trailer CRC, missing END
 *    marker, short buffers) are fed to [AcousticCarrier.decode] and must produce the exact
 *    [DecodeFailure] / corrected-byte count.
 */
class AcousticWireFormatTest {

    private val frame = NightjarAcoustics.FRAME_SAMPLES
    private val binHz = 48_000.0 / 1024

    // ------------------------------------------------------------------ reference modulator

    private fun crc8(d: ByteArray): Int {
        var c = 0
        for (b in d) {
            c = c xor (b.toInt() and 0xFF)
            repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
        }
        return c
    }

    private fun tone(bins: List<Int>, frames: Int, startSample: Long): ShortArray {
        val n = frames * frame
        val per = Short.MAX_VALUE * 0.85 / bins.size
        return ShortArray(n) { i ->
            val t = (startSample + i).toDouble() / 48_000
            var sum = 0.0
            for (b in bins) sum += sin(2.0 * PI * (b * binHz) * t)
            (sum * per).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun header(
        len: Int,
        proto: NightjarAcoustics.Protocol,
        magic: Int = 0x4E,
        version: Int = 1,
        mode: Int = proto.modeByte,
        crcDelta: Int = 0,
    ): ByteArray {
        val h = byteArrayOf(magic.toByte(), version.toByte(), mode.toByte(), (len shr 8).toByte(), len.toByte(), 0)
        h[5] = ((crc8(h.copyOfRange(0, 5)) + crcDelta) and 0xFF).toByte()
        return ReedSolomon.encode(h, 8)
    }

    private fun payloadBlocks(payload: ByteArray, trailerXor: Int = 0): List<ByteArray> {
        val crc = CRC32().apply { update(payload) }.value xor trailerXor.toLong()
        val trailer = ByteArray(4) { (crc shr (24 - 8 * it)).toByte() }
        val prot = payload + trailer
        return prot.toList().chunked(32).map { ReedSolomon.encode(it.toByteArray(), 16) }
    }

    private fun modulate(
        coded: ByteArray,
        proto: NightjarAcoustics.Protocol,
        rate: NightjarAcoustics.SymbolRate,
        endMarker: Boolean = true,
        start: Boolean = true,
    ): ShortArray {
        val bps = proto.bytesPerSymbol
        val padded = coded.copyOf(((coded.size + bps - 1) / bps) * bps)
        val segs = ArrayList<ShortArray>()
        var cursor = 0L
        if (start) {
            segs += tone(listOf(proto.baseBin, proto.baseBin + proto.gridToneCount - 1), 5, cursor)
        } else {
            segs += ShortArray(5 * frame)
        }
        cursor += 5L * frame
        var off = 0
        while (off < padded.size) {
            val bins = ArrayList<Int>()
            for (i in 0 until bps) {
                val v = padded[off + i].toInt() and 0xFF
                bins += proto.baseBin + 16 * (2 * i) + (v shr 4)
                bins += proto.baseBin + 16 * (2 * i + 1) + (v and 15)
            }
            segs += tone(bins, rate.framesPerTx, cursor)
            cursor += rate.framesPerTx.toLong() * frame
            off += bps
        }
        segs += if (endMarker) tone(listOf(proto.baseBin + 31, proto.baseBin + 32), 5, cursor) else ShortArray(5 * frame)
        val out = ShortArray(segs.sumOf { it.size })
        var p = 0
        for (s in segs) { s.copyInto(out, p); p += s.size }
        return out
    }

    private fun codedFor(payload: ByteArray, proto: NightjarAcoustics.Protocol): ByteArray {
        var c = header(payload.size, proto)
        for (b in payloadBlocks(payload)) c += b
        return c
    }

    private fun payload(n: Int) = ByteArray(n) { (it * 37 + 11).toByte() }

    private val aud = NightjarAcoustics.Protocol.AUDIBLE
    private val nu = NightjarAcoustics.Protocol.NEAR_ULTRASONIC
    private val normal = NightjarAcoustics.SymbolRate.NORMAL
    private val fast = NightjarAcoustics.SymbolRate.FAST

    private fun flip(b: ByteArray, pos: Int, mask: Int = 0x5A) { b[pos] = (b[pos].toInt() xor mask).toByte() }

    private fun assertPcmClose(expected: ShortArray, actual: ShortArray, label: String) {
        assertEquals("$label length", expected.size, actual.size)
        for (i in expected.indices) {
            if (kotlin.math.abs(expected[i] - actual[i]) > 1) fail("$label sample $i: expected ${expected[i]} got ${actual[i]}")
        }
    }

    private fun failure(r: DecodeResult): DecodeResult.Failure {
        assertTrue("expected Failure, was $r", r is DecodeResult.Failure)
        return r as DecodeResult.Failure
    }

    // ------------------------------------------------------------------ encode

    @Test
    fun `encode output equals the reference modulation for AUDIBLE NORMAL across block boundaries`() {
        for (size in intArrayOf(0, 1, 28, 29, 60, 61, 100)) {
            val p = payload(size)
            val expected = modulate(codedFor(p, aud), aud, normal)
            assertPcmClose(expected, AcousticCarrier().encode(p), "size=$size")
        }
    }

    @Test
    fun `encode output equals the reference modulation for NEAR_ULTRASONIC FAST and mixed settings`() {
        val p = payload(40)
        assertPcmClose(modulate(codedFor(p, nu), nu, fast), AcousticCarrier(nu, fast).encode(p), "nu/fast")
        assertPcmClose(modulate(codedFor(p, nu), nu, normal), AcousticCarrier(nu, normal).encode(p), "nu/normal")
        assertPcmClose(modulate(codedFor(p, aud), aud, fast), AcousticCarrier(aud, fast).encode(p), "aud/fast")
    }

    @Test
    fun `encoded length is 5 plus symbols times frames-per-symbol plus 5 frames`() {
        // empty payload: header 14 + (4 trailer + 16 parity) = 34 coded bytes
        assertEquals((5 + 12 * 9 + 5) * frame, AcousticCarrier().encode(ByteArray(0)).size) // 34/3 -> 12 symbols
        assertEquals((5 + 17 * 6 + 5) * frame, AcousticCarrier(nu, fast).encode(ByteArray(0)).size) // 34/2 -> 17 symbols
        // 28-byte payload: 32 prot -> one full block (48) + 14 = 62 -> 21 symbols; 29 -> 2 blocks
        assertEquals((5 + 21 * 9 + 5) * frame, AcousticCarrier().encode(ByteArray(28)).size)
        // 29-byte payload: 33 prot -> blocks 32 + 1 -> 48 + 17 + 14 = 79 -> 27 symbols
        assertEquals((5 + 27 * 9 + 5) * frame, AcousticCarrier().encode(ByteArray(29)).size)
    }

    @Test
    fun `encode peak amplitude stays within the 0_85 full-scale headroom`() {
        val pcm = AcousticCarrier().encode(payload(20))
        val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue("peak=$peak", peak <= (Short.MAX_VALUE * 0.85).toInt() + 1)
        assertTrue("signal must actually be loud, peak=$peak", peak > 10_000)
    }

    @Test
    fun `encode rejects payloads over 1024 bytes naming the sizes and accepts exactly 1024`() {
        try {
            AcousticCarrier().encode(ByteArray(1025))
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, e.message!!.contains("1025") && e.message!!.contains("1024"))
        }
        assertEquals(1024, AcousticCarrier().maxPayloadBytes)
    }

    @Test
    fun `descriptor identifies the acoustic modem carrier`() {
        val d = AcousticCarrier().descriptor
        assertEquals(ModuleId.ACOUSTIC_MODEM, d.id)
        assertEquals(CarrierDomain.AUDIO, d.domain)
        assertEquals(ModuleRole.CARRIER, d.role)
    }

    // ------------------------------------------------------------------ crc8

    @Test
    fun `crc8 known values and offset-length windowing`() {
        assertEquals(0x8C, AcousticCarrier.crc8(byteArrayOf(0x4E, 1, 0, 0, 0), 0, 5))
        assertEquals(0xA6, AcousticCarrier.crc8(byteArrayOf(0x4E, 1, 0, 0, 0x0E), 0, 5))
        assertEquals(0xB3, AcousticCarrier.crc8(byteArrayOf(0x4E, 1, 1, 4, 0), 0, 5))
        assertEquals(0x07, AcousticCarrier.crc8(byteArrayOf(1), 0, 1))
        assertEquals(0xF3, AcousticCarrier.crc8(byteArrayOf(0xFF.toByte()), 0, 1))
        assertEquals(0, AcousticCarrier.crc8(byteArrayOf(9, 9), 0, 0))
        // window: only bytes [1,2) count
        assertEquals(0x07, AcousticCarrier.crc8(byteArrayOf(0x55, 1, 0x66), 1, 1))
        assertEquals(0x8C, AcousticCarrier.crc8(byteArrayOf(0x77, 0x4E, 1, 0, 0, 0, 0x33), 1, 5))
    }

    // ------------------------------------------------------------------ decode: success paths

    @Test
    fun `decode of reference transmissions recovers payloads with zero corrections`() {
        for ((proto, rate) in listOf(aud to normal, aud to fast, nu to normal, nu to fast)) {
            for (size in intArrayOf(0, 1, 28, 29, 33)) {
                val p = payload(size)
                val r = AcousticCarrier(proto, rate).decode(modulate(codedFor(p, proto), proto, rate))
                assertTrue("$proto/$rate/$size -> $r", r is DecodeResult.Success)
                r as DecodeResult.Success
                assertArrayEquals(p, r.payload)
                assertEquals(0, r.correctedByteErrors)
            }
        }
    }

    @Test
    fun `decode sums corrected byte errors across header and every payload block`() {
        val p = payload(40) // protected 44 -> blocks of 32 and 12
        val h = header(40, aud)
        flip(h, 1); flip(h, 9)
        val blocks = payloadBlocks(p)
        flip(blocks[0], 3); flip(blocks[0], 20); flip(blocks[0], 47)
        flip(blocks[1], 0)
        val coded = h + blocks[0] + blocks[1]
        val r = AcousticCarrier().decode(modulate(coded, aud, normal))
        assertTrue(r.toString(), r is DecodeResult.Success)
        r as DecodeResult.Success
        assertArrayEquals(p, r.payload)
        assertEquals(2 + 3 + 1, r.correctedByteErrors)
    }

    @Test
    fun `decode succeeds with exactly 4 header errors and fails closed with 5`() {
        val p = payload(10)
        val ok = header(10, aud).also { for (i in intArrayOf(0, 4, 8, 12)) flip(it, i) }
        val rOk = AcousticCarrier().decode(modulate(ok + payloadBlocks(p).reduce { a, b -> a + b }, aud, normal))
        assertTrue(rOk.toString(), rOk is DecodeResult.Success)
        assertEquals(4, (rOk as DecodeResult.Success).correctedByteErrors)

        val bad = header(10, aud).also { for (i in intArrayOf(0, 2, 4, 8, 12)) flip(it, i) }
        val rBad = failure(AcousticCarrier().decode(modulate(bad + payloadBlocks(p).reduce { a, b -> a + b }, aud, normal)))
        assertEquals(DecodeFailure.UNRECOVERABLE_FEC, rBad.reason)
        assertTrue(rBad.detail!!, rBad.detail!!.contains("header"))
    }

    @Test
    fun `decode succeeds with exactly 8 payload block errors and fails closed with 9 naming the offset`() {
        val p = payload(60) // protected 64 -> two full blocks
        val h = header(60, aud)
        val blocks = payloadBlocks(p)
        val b0 = blocks[0].copyOf().also { for (i in intArrayOf(0, 6, 13, 21, 30, 32, 41, 47)) flip(it, i) }
        val r = AcousticCarrier().decode(modulate(h + b0 + blocks[1], aud, normal))
        assertTrue(r.toString(), r is DecodeResult.Success)
        assertEquals(8, (r as DecodeResult.Success).correctedByteErrors)

        // 9 errors in the SECOND block -> failure message reports payload offset 32
        val b1 = blocks[1].copyOf().also { for (i in intArrayOf(0, 5, 11, 17, 23, 29, 33, 40, 46)) flip(it, i, 0x9D) }
        val f = failure(AcousticCarrier().decode(modulate(h + blocks[0] + b1, aud, normal)))
        assertEquals(DecodeFailure.UNRECOVERABLE_FEC, f.reason)
        assertTrue(f.detail!!, f.detail!!.contains("offset 32"))
    }

    // ------------------------------------------------------------------ decode: header validation

    private fun txWithHeader(h: ByteArray, p: ByteArray = payload(5)): ShortArray =
        modulate(h + payloadBlocks(p).reduce { a, b -> a + b }, aud, normal)

    @Test
    fun `header with wrong CRC-8 is HEADER_INVALID`() {
        val f = failure(AcousticCarrier().decode(txWithHeader(header(5, aud, crcDelta = 1))))
        assertEquals(DecodeFailure.HEADER_INVALID, f.reason)
        assertTrue(f.detail!!, f.detail!!.contains("CRC-8"))
    }

    @Test
    fun `header with wrong magic or version is HEADER_INVALID even with a valid CRC`() {
        val m = failure(AcousticCarrier().decode(txWithHeader(header(5, aud, magic = 0x4F))))
        assertEquals(DecodeFailure.HEADER_INVALID, m.reason)
        assertTrue(m.detail!!, m.detail!!.contains("magic"))
        val v = failure(AcousticCarrier().decode(txWithHeader(header(5, aud, version = 2))))
        assertEquals(DecodeFailure.HEADER_INVALID, v.reason)
        assertTrue(v.detail!!, v.detail!!.contains("magic/version"))
        val v0 = failure(AcousticCarrier().decode(txWithHeader(header(5, aud, version = 0))))
        assertEquals(DecodeFailure.HEADER_INVALID, v0.reason)
    }

    @Test
    fun `header with the other protocols mode byte is HEADER_INVALID`() {
        val f = failure(AcousticCarrier().decode(txWithHeader(header(5, aud, mode = nu.modeByte))))
        assertEquals(DecodeFailure.HEADER_INVALID, f.reason)
        assertTrue(f.detail!!, f.detail!!.contains("mode"))
        val g = failure(AcousticCarrier().decode(txWithHeader(header(5, aud, mode = 0x7F))))
        assertEquals(DecodeFailure.HEADER_INVALID, g.reason)
    }

    @Test
    fun `declared length above 1024 is PAYLOAD_TOO_LARGE while 1025 and 65535 both trip it`() {
        for (len in intArrayOf(1025, 4096, 0xFFFF, 0x0401, 0x0500)) {
            val tx = modulate(header(len, aud), aud, normal)
            val f = failure(AcousticCarrier().decode(tx))
            assertEquals("len=$len", DecodeFailure.PAYLOAD_TOO_LARGE, f.reason)
            assertTrue(f.detail!!, f.detail!!.contains("$len"))
        }
    }

    @Test
    fun `declared length of exactly 1024 passes the size gate and then fails on a short carrier`() {
        val f = failure(AcousticCarrier().decode(modulate(header(1024, aud), aud, normal)))
        assertEquals(DecodeFailure.NO_PAYLOAD_FOUND, f.reason)
        assertTrue(f.detail!!, f.detail!!.contains("declared payload length"))
    }

    @Test
    fun `length field is big-endian across both bytes`() {
        // 300 = 0x012C: if bytes were swapped the declared length would be 0x2C01 = 11265 -> PAYLOAD_TOO_LARGE.
        val p = payload(300)
        val r = AcousticCarrier().decode(modulate(codedFor(p, aud), aud, normal))
        assertTrue(r.toString(), r is DecodeResult.Success)
        assertArrayEquals(p, (r as DecodeResult.Success).payload)
    }

    // ------------------------------------------------------------------ decode: framing failures

    @Test
    fun `wrong trailer CRC-32 with intact RS is INTEGRITY_MISMATCH`() {
        val p = payload(12)
        val coded = header(12, aud) + payloadBlocks(p, trailerXor = 0x1).reduce { a, b -> a + b }
        val f = failure(AcousticCarrier().decode(modulate(coded, aud, normal)))
        assertEquals(DecodeFailure.INTEGRITY_MISMATCH, f.reason)
        assertTrue(f.detail!!, f.detail!!.contains("CRC-32"))
        // the high byte of the trailer too
        val coded2 = header(12, aud) + payloadBlocks(p, trailerXor = 0x01000000).reduce { a, b -> a + b }
        assertEquals(DecodeFailure.INTEGRITY_MISMATCH, failure(AcousticCarrier().decode(modulate(coded2, aud, normal))).reason)
    }

    @Test
    fun `missing END marker is NO_PAYLOAD_FOUND`() {
        val f = failure(AcousticCarrier().decode(modulate(codedFor(payload(8), aud), aud, normal, endMarker = false)))
        assertEquals(DecodeFailure.NO_PAYLOAD_FOUND, f.reason)
        assertTrue(f.detail!!, f.detail!!.contains("END"))
    }

    @Test
    fun `END marker present for only some of its five frames is rejected`() {
        val tx = modulate(codedFor(payload(8), aud), aud, normal)
        // blank the last frame of the END marker
        for (i in tx.size - frame until tx.size) tx[i] = 0
        assertEquals(DecodeFailure.NO_PAYLOAD_FOUND, failure(AcousticCarrier().decode(tx)).reason)
        val tx2 = modulate(codedFor(payload(8), aud), aud, normal)
        for (i in tx2.size - 5 * frame until tx2.size - 4 * frame) tx2[i] = 0 // first END frame
        assertEquals(DecodeFailure.NO_PAYLOAD_FOUND, failure(AcousticCarrier().decode(tx2)).reason)
    }

    @Test
    fun `START marker of only four frames is not accepted`() {
        val tx = modulate(codedFor(payload(8), aud), aud, normal)
        for (i in 4 * frame until 5 * frame) tx[i] = 0
        val f = failure(AcousticCarrier().decode(tx))
        assertEquals(DecodeFailure.NO_PAYLOAD_FOUND, f.reason)
    }

    @Test
    fun `no START marker is reported as such`() {
        val f = failure(AcousticCarrier().decode(modulate(codedFor(payload(8), aud), aud, normal, start = false)))
        assertEquals(DecodeFailure.NO_PAYLOAD_FOUND, f.reason)
        assertTrue(f.detail!!, f.detail!!.contains("START"))
    }

    @Test
    fun `buffer-shape guards - empty, partial frame, and under eleven frames`() {
        val c = AcousticCarrier()
        assertTrue(failure(c.decode(ShortArray(0))).detail!!.contains("whole number of frames"))
        assertTrue(failure(c.decode(ShortArray(frame + 1))).detail!!.contains("whole number of frames"))
        assertTrue(failure(c.decode(ShortArray(10 * frame))).detail!!.contains("too short to hold both markers"))
        // 11 frames: first size that gets as far as the marker search
        assertTrue(failure(c.decode(ShortArray(11 * frame))).detail!!.contains("START"))
        assertTrue(failure(c.decode(ShortArray(1))).detail!!.contains("whole number of frames"))
    }

    @Test
    fun `carrier that ends inside the header symbols reports too short for the header`() {
        // START + 4 header symbols (of 5 needed) + END-sized tail
        val tx = modulate(header(5, aud).copyOf(12), aud, normal) // 12 bytes = 4 symbols
        val f = failure(AcousticCarrier().decode(tx))
        assertEquals(DecodeFailure.NO_PAYLOAD_FOUND, f.reason)
        assertTrue(f.detail!!, f.detail!!.contains("header symbols"))
    }

    @Test
    fun `carrier that exactly fits START, header symbols and END passes the header-size gate`() {
        // header(5) is 14 bytes = 5 symbols (15 bytes padded): 5 + 45 + 5 = 55 frames, an exact fit.
        val tx = modulate(header(5, aud), aud, normal)
        assertEquals(55 * frame, tx.size)
        val f = failure(AcousticCarrier().decode(tx))
        assertEquals(DecodeFailure.NO_PAYLOAD_FOUND, f.reason)
        assertTrue(f.detail!!, f.detail!!.contains("declared payload length")) // not "header symbols"
    }

    @Test
    fun `carrier truncated before the declared payload ends is NO_PAYLOAD_FOUND`() {
        val tx = AcousticCarrier().encode(payload(100))
        val cut = tx.copyOf(tx.size - 11 * frame)
        val f = failure(AcousticCarrier().decode(cut))
        assertEquals(DecodeFailure.NO_PAYLOAD_FOUND, f.reason)
    }

    @Test
    fun `trailing content after the END marker and leading silence do not disturb decoding`() {
        val p = payload(30)
        val tx = AcousticCarrier().encode(p)
        val padded = ShortArray(3 * frame) + tx + ShortArray(7 * frame)
        val r = AcousticCarrier().decode(padded)
        assertTrue(r.toString(), r is DecodeResult.Success)
        assertArrayEquals(p, (r as DecodeResult.Success).payload)
    }

    @Test
    fun `decode with a sub-frame leading offset still succeeds`() {
        val p = payload(30)
        val tx = AcousticCarrier().encode(p)
        for (lead in intArrayOf(1, 63, 64, 333, 1023)) {
            val buf = ShortArray(lead) + tx + ShortArray(frame - lead + 2 * frame)
            val r = AcousticCarrier().decode(buf.copyOf(((buf.size + frame - 1) / frame) * frame))
            assertTrue("lead=$lead $r", r is DecodeResult.Success)
            assertArrayEquals("lead=$lead", p, (r as DecodeResult.Success).payload)
        }
    }

    // ------------------------------------------------------------------ cancellation cadence

    @Test
    fun `checkCancelled is polled every 64 candidates of the marker search`() {
        // 200 frames of silence: candidates 0..190 -> polls at 0, 64, 128
        var calls = 0
        AcousticCarrier().decode(ShortArray(200 * frame)) { calls++ }
        assertEquals(3, calls)
        calls = 0
        AcousticCarrier().decode(ShortArray(11 * frame)) { calls++ } // candidates 0..1
        assertEquals(1, calls)
        calls = 0
        AcousticCarrier().decode(ShortArray(75 * frame)) { calls++ } // candidates 0..65 -> 0 and 64
        assertEquals(2, calls)
    }

    @Test
    fun `checkCancelled is polled once per header symbol, data symbol and payload block on a good carrier`() {
        val p = payload(40) // 14 + 44 + 32 = 90 coded bytes -> 30 symbols; 2 blocks
        var calls = 0
        val r = AcousticCarrier().decode(AcousticCarrier().encode(p)) { calls++ }
        assertTrue(r is DecodeResult.Success)
        // 1 (candidate 0) + 5 header symbols + 30 data symbols + 2 blocks
        assertEquals(1 + 5 + 30 + 2, calls)
    }

    @Test
    fun `a throwing checkCancelled aborts the decode`() {
        class Stop : RuntimeException()
        try {
            AcousticCarrier().decode(AcousticCarrier().encode(payload(5))) { throw Stop() }
            fail("expected Stop")
        } catch (_: Stop) {
        }
    }
}
