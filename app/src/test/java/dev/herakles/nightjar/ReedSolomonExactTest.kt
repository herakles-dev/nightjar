package dev.herakles.nightjar

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exact-value companions to [ReedSolomonTest] (whose root-vanishing checks are satisfied by many
 * wrong encoders). Expected parity bytes below were produced by an independent GF(2^8)/0x11D
 * reference implementation, so they pin the generator polynomial and field tables, not just
 * self-consistency. Decode tests pin the exact error count, every error position, and the
 * t / t+1 boundary.
 */
class ReedSolomonExactTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun flip(cw: ByteArray, pos: Int, mask: Int) {
        cw[pos] = (cw[pos].toInt() xor mask).toByte()
    }

    private val header = bytes(0x4E, 0x01, 0x00, 0x00, 0x0E, 0x12)

    // ---- GF256 -------------------------------------------------------------------------

    @Test
    fun `GF256 exp table matches the 0x11D field`() {
        assertEquals(1, GF256.exp(0))
        assertEquals(2, GF256.exp(1))
        assertEquals(0x1D, GF256.exp(8)) // x^8 reduces by 0x11D
        assertEquals(116, GF256.exp(10))
        assertEquals(142, GF256.exp(254))
        assertEquals(1, GF256.exp(255)) // order 255
        assertEquals(2, GF256.exp(256))
    }

    @Test
    fun `GF256 mul has known products and zero absorbs`() {
        assertEquals(0x31, GF256.mul(0x57, 0x83))
        assertEquals(0x31, GF256.mul(0x83, 0x57))
        assertEquals(0, GF256.mul(0, 0x83))
        assertEquals(0, GF256.mul(0x83, 0))
        assertEquals(0x83, GF256.mul(1, 0x83))
        assertEquals(0x1D, GF256.mul(0x80, 2))
    }

    @Test
    fun `GF256 inv is the multiplicative inverse for every nonzero element`() {
        for (a in 1..255) {
            assertEquals("a=$a", 1, GF256.mul(a, GF256.inv(a)))
        }
        assertEquals(1, GF256.inv(1))
        assertEquals(142, GF256.inv(2)) // alpha^-1 == alpha^254
    }

    @Test(expected = IllegalArgumentException::class)
    fun `GF256 inv of zero throws`() {
        GF256.inv(0)
    }

    // ---- encode: exact parity ----------------------------------------------------------

    @Test
    fun `RS(14,6) header parity bytes are exactly the reference values`() {
        val cw = ReedSolomon.encode(header, 8)
        assertArrayEquals(header, cw.copyOfRange(0, 6))
        assertArrayEquals(bytes(0xB7, 0x12, 0x5D, 0x16, 0xC7, 0x1D, 0x36, 0x51), cw.copyOfRange(6, 14))
    }

    @Test
    fun `RS(48,32) parity bytes are exactly the reference values`() {
        val data = ByteArray(32) { (it * 7 + 3).toByte() }
        val cw = ReedSolomon.encode(data, 16)
        assertArrayEquals(data, cw.copyOfRange(0, 32))
        assertArrayEquals(
            bytes(
                0x8A, 0xD6, 0x65, 0x11, 0x22, 0x44, 0x5B, 0xCA,
                0x75, 0x2E, 0xC7, 0xCA, 0x22, 0x70, 0x69, 0xB2,
            ),
            cw.copyOfRange(32, 48),
        )
    }

    @Test
    fun `small codewords match the reference including the generator polynomial`() {
        assertArrayEquals(bytes(0x01, 0x03, 0x02), ReedSolomon.encode(bytes(1), 2)) // g(x)=x^2+3x+2
        assertArrayEquals(
            bytes(0x41, 0x42, 0x43, 0x17, 0x4B, 0xA7, 0xBB),
            ReedSolomon.encode(bytes(0x41, 0x42, 0x43), 4),
        )
    }

    @Test
    fun `all-zero data encodes to all-zero parity and empty data yields parity-only length`() {
        assertArrayEquals(ByteArray(14), ReedSolomon.encode(ByteArray(6), 8))
        assertEquals(8, ReedSolomon.encode(ByteArray(0), 8).size)
    }

    @Test
    fun `encode does not modify its input`() {
        val copy = header.copyOf()
        ReedSolomon.encode(header, 8)
        assertArrayEquals(copy, header)
    }

    // ---- decode: exact corrections -----------------------------------------------------

    @Test
    fun `every single-byte error position in an RS(14,6) codeword is corrected with several magnitudes`() {
        val clean = ReedSolomon.encode(header, 8)
        for (pos in 0 until 14) {
            for (mask in intArrayOf(0x01, 0x80, 0xFF, 0x5A)) {
                val cw = clean.copyOf()
                flip(cw, pos, mask)
                val r = ReedSolomon.decode(cw, 8)
                assertNotNull("pos=$pos mask=$mask", r)
                assertArrayEquals("pos=$pos mask=$mask", header, r!!.data)
                assertEquals("pos=$pos mask=$mask", 1, r.correctedErrors)
            }
        }
    }

    @Test
    fun `decode does not modify the codeword it is given`() {
        val cw = ReedSolomon.encode(header, 8)
        flip(cw, 3, 0x77)
        val before = cw.copyOf()
        ReedSolomon.decode(cw, 8)
        assertArrayEquals(before, cw)
    }

    @Test
    fun `parity-only errors leave data intact and are counted`() {
        val clean = ReedSolomon.encode(header, 8)
        val cw = clean.copyOf()
        flip(cw, 6, 0x11)
        flip(cw, 13, 0x22)
        val r = ReedSolomon.decode(cw, 8)!!
        assertArrayEquals(header, r.data)
        assertEquals(2, r.correctedErrors)
    }

    @Test
    fun `two and three errors in RS(14,6) are corrected with exact counts`() {
        val clean = ReedSolomon.encode(header, 8)
        for (positions in listOf(intArrayOf(0, 13), intArrayOf(5, 6), intArrayOf(1, 7, 12), intArrayOf(0, 1, 2))) {
            val cw = clean.copyOf()
            for (p in positions) flip(cw, p, 0xC3)
            val r = ReedSolomon.decode(cw, 8)
            assertNotNull(positions.toList().toString(), r)
            assertArrayEquals(header, r!!.data)
            assertEquals(positions.size, r.correctedErrors)
        }
    }

    @Test
    fun `exactly t errors is corrected and t plus one is not silently accepted for RS(14,6)`() {
        val clean = ReedSolomon.encode(header, 8)
        val four = clean.copyOf().also { for (p in intArrayOf(1, 4, 8, 11)) flip(it, p, 0x3F) }
        val r4 = ReedSolomon.decode(four, 8)
        assertNotNull(r4)
        assertArrayEquals(header, r4!!.data)
        assertEquals(4, r4.correctedErrors)

        val random = Random(99)
        var detected = 0
        repeat(60) {
            val cw = clean.copyOf()
            for (p in (0 until 14).shuffled(random).take(5)) flip(cw, p, random.nextInt(1, 256))
            val r = ReedSolomon.decode(cw, 8)
            // A 5-error word may legally land inside another codeword's radius, but it must never
            // decode back to the ORIGINAL data claiming <= 4 fixes.
            if (r == null) detected++ else assertTrue(!r.data.contentEquals(header))
        }
        assertTrue("most 5-error words must be detected, was $detected/60", detected >= 50)
    }

    @Test
    fun `exactly 8 errors corrected in RS(48,32) and 9 errors are detected`() {
        val data = ByteArray(32) { (it * 5 + 1).toByte() }
        val clean = ReedSolomon.encode(data, 16)
        val eight = clean.copyOf().also { for (p in intArrayOf(0, 6, 13, 21, 30, 32, 41, 47)) flip(it, p, 0x9D) }
        val r8 = ReedSolomon.decode(eight, 16)
        assertNotNull(r8)
        assertArrayEquals(data, r8!!.data)
        assertEquals(8, r8.correctedErrors)

        val nine = clean.copyOf().also { for (p in intArrayOf(0, 5, 11, 17, 23, 29, 33, 40, 46)) flip(it, p, 0x9D) }
        val r9 = ReedSolomon.decode(nine, 16)
        assertTrue("9 errors must not yield the original data", r9 == null || !r9.data.contentEquals(data))
        assertNull("this fixed 9-error pattern is detected, not miscorrected", r9)
    }

    @Test
    fun `shortened block errors at first and last positions are corrected`() {
        val data = ByteArray(5) { (it + 100).toByte() }
        val clean = ReedSolomon.encode(data, 16)
        assertEquals(21, clean.size)
        for (pos in intArrayOf(0, 4, 5, 20)) {
            val cw = clean.copyOf()
            flip(cw, pos, 0x80)
            val r = ReedSolomon.decode(cw, 16)
            assertNotNull("pos=$pos", r)
            assertArrayEquals(data, r!!.data)
            assertEquals(1, r.correctedErrors)
        }
    }

    @Test
    fun `decode of a clean word reports zero corrections and exact data length`() {
        val data = ByteArray(32) { it.toByte() }
        val r = ReedSolomon.decode(ReedSolomon.encode(data, 16), 16)!!
        assertEquals(32, r.data.size)
        assertEquals(0, r.correctedErrors)
        assertArrayEquals(data, r.data)
    }

    @Test
    fun `decode with no data bytes or no parity returns null`() {
        assertNull(ReedSolomon.decode(ByteArray(8), 8)) // k == 0
        assertNull(ReedSolomon.decode(ByteArray(4), 8)) // k < 0
        assertNull(ReedSolomon.decode(ByteArray(6), 0)) // nsym == 0
        assertNull(ReedSolomon.decode(ByteArray(0), 0))
    }

    @Test
    fun `smallest viable code corrects one error with nsym 2 and reports two errors as not recovered`() {
        val clean = ReedSolomon.encode(bytes(0x10, 0x20, 0x30), 2)
        val one = clean.copyOf().also { flip(it, 1, 0x44) }
        val r = ReedSolomon.decode(one, 2)
        assertNotNull(r)
        assertArrayEquals(bytes(0x10, 0x20, 0x30), r!!.data)
        assertEquals(1, r.correctedErrors)

        // numErrors*2 > nsym guard: with nsym=1 even a single error is beyond t = 0.
        val tiny = ReedSolomon.encode(bytes(0x10, 0x20), 1)
        tiny[0] = (tiny[0].toInt() xor 1).toByte()
        assertNull(ReedSolomon.decode(tiny, 1))
    }

    @Test
    fun `randomized exact t errors always corrected with exact count and positions fixed`() {
        val random = Random(2024)
        repeat(80) {
            val k = random.nextInt(1, 33)
            val nsym = if (random.nextBoolean()) 8 else 16
            val t = nsym / 2
            val data = ByteArray(k) { random.nextInt(256).toByte() }
            val cw = ReedSolomon.encode(data, nsym)
            val positions = (0 until k + nsym).shuffled(random).take(minOf(t, k + nsym))
            for (p in positions) flip(cw, p, random.nextInt(1, 256))
            val r = ReedSolomon.decode(cw, nsym)
            assertNotNull("k=$k nsym=$nsym", r)
            assertArrayEquals(data, r!!.data)
            assertEquals(positions.size, r.correctedErrors)
        }
    }
}
