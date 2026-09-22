package dev.herakles.nightjar

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Correctness check for the [ReedSolomon] encoder that [AcousticCarrier] builds its FEC
 * framing on. This is deliberately independent of [AcousticCarrierTest]'s
 * structural checks: frame-count/marker-position assertions would pass even if the RS math were
 * wrong, since they never inspect codeword content. A codeword `c(x)` (coefficients read
 * descending-degree, transmission order) produced by a valid RS(n,k) encoder over the roots
 * `alpha^0 .. alpha^(nsym-1)` must satisfy `c(alpha^i) == 0` for every root `i` — that is exactly
 * what makes it a multiple of the generator polynomial. This test verifies that property for the
 * two concrete shapes this format specifies (RS(14,6) header, RS(48,32) payload blocks,
 * including a shortened block) plus randomized shapes.
 *
 * The `decode` tests below are the dedicated, isolated check for
 * [ReedSolomon.decode]'s Berlekamp-Massey/Chien-search/Forney error-correction path — isolated
 * because [AcousticCarrierTest]'s round-trip tests only exercise `decode`'s zero-syndrome fast
 * path (a clean synthetic signal has no byte errors to correct), so they alone would never catch
 * a bug in the error-correcting branch.
 */
class ReedSolomonTest {

    @Test
    fun `RS(14,6) header codeword has roots at alpha^0 through alpha^7`() {
        val data = byteArrayOf(0x4E, 0x01, 0x00, 0x00, 0x0E, 0x12)
        val codeword = ReedSolomon.encode(data, nsym = 8)
        assertEquals(14, codeword.size)
        assertArrayEquals("systematic property: data bytes preserved verbatim", data, codeword.copyOfRange(0, 6))
        assertAllRootsZero(codeword, nsym = 8)
    }

    @Test
    fun `RS(48,32) full payload block codeword has roots at alpha^0 through alpha^15`() {
        val data = ByteArray(32) { (it * 7 + 3).toByte() }
        val codeword = ReedSolomon.encode(data, nsym = 16)
        assertEquals(48, codeword.size)
        assertAllRootsZero(codeword, nsym = 16)
    }

    @Test
    fun `RS(48,32) shortened final block still has roots at alpha^0 through alpha^15`() {
        // e.g. a 14-byte-of-payload+trailer tail block, per this format's shortening rule.
        val data = ByteArray(14) { (it * 11 + 1).toByte() }
        val codeword = ReedSolomon.encode(data, nsym = 16)
        assertEquals(30, codeword.size)
        assertAllRootsZero(codeword, nsym = 16)
    }

    @Test
    fun `randomized shapes and payloads always satisfy the codeword-root property`() {
        val random = Random(1234)
        repeat(200) {
            val k = random.nextInt(1, 33)
            val nsym = if (random.nextBoolean()) 8 else 16
            val data = ByteArray(k) { random.nextInt(0, 256).toByte() }
            val codeword = ReedSolomon.encode(data, nsym)
            assertEquals(k + nsym, codeword.size)
            assertAllRootsZero(codeword, nsym)
        }
    }

    // ---------------------------------------------------------------------
    // decode(): zero-error fast path, single/multi byte-error correction,
    // and the uncorrectable/never-lie-about-success safety net
    // ---------------------------------------------------------------------

    @Test
    fun `decode of an untouched RS(14,6) header codeword returns the data verbatim with zero corrections`() {
        val data = byteArrayOf(0x4E, 0x01, 0x00, 0x00, 0x0E, 0x12)
        val codeword = ReedSolomon.encode(data, nsym = 8)

        val result = ReedSolomon.decode(codeword, nsym = 8)

        assertNotNull(result)
        assertArrayEquals(data, result!!.data)
        assertEquals(0, result.correctedErrors)
    }

    @Test
    fun `decode corrects a single byte error in an RS(14,6) header codeword`() {
        val data = byteArrayOf(0x4E, 0x01, 0x00, 0x00, 0x0E, 0x12)
        val codeword = ReedSolomon.encode(data, nsym = 8)
        codeword[2] = (codeword[2].toInt() xor 0xFF).toByte() // corrupt one data byte

        val result = ReedSolomon.decode(codeword, nsym = 8)

        assertNotNull("RS(14,6) corrects up to 4 byte errors; 1 must succeed", result)
        assertArrayEquals(data, result!!.data)
        assertEquals(1, result.correctedErrors)
    }

    @Test
    fun `decode corrects up to 4 byte errors in an RS(14,6) header codeword`() {
        val data = byteArrayOf(0x4E, 0x01, 0x00, 0x00, 0x0E, 0x12)
        val codeword = ReedSolomon.encode(data, nsym = 8)
        // Flip 4 distinct byte positions (within the 14-byte codeword) — the documented ceiling.
        for (pos in intArrayOf(0, 3, 7, 13)) {
            codeword[pos] = (codeword[pos].toInt() xor 0x5A).toByte()
        }

        val result = ReedSolomon.decode(codeword, nsym = 8)

        assertNotNull("RS(14,6) must correct exactly 4 byte errors", result)
        assertArrayEquals(data, result!!.data)
        assertEquals(4, result.correctedErrors)
    }

    @Test
    fun `decode returns null (never a false success) when RS(14,6) errors exceed the correctable ceiling`() {
        val data = byteArrayOf(0x4E, 0x01, 0x00, 0x00, 0x0E, 0x12)
        val codeword = ReedSolomon.encode(data, nsym = 8)
        // 5 errors exceeds the RS(14,6) ceiling of floor(8/2)=4 — must fail closed, not lie.
        for (pos in intArrayOf(0, 2, 5, 9, 12)) {
            codeword[pos] = (codeword[pos].toInt() xor 0xA5).toByte()
        }

        val result = ReedSolomon.decode(codeword, nsym = 8)

        assertNull("uncorrectable codeword must return null, never a wrong 'success'", result)
    }

    @Test
    fun `decode corrects byte errors in a full RS(48,32) payload block`() {
        val data = ByteArray(32) { (it * 7 + 3).toByte() }
        val codeword = ReedSolomon.encode(data, nsym = 16)
        for (pos in intArrayOf(1, 10, 20, 33, 40, 47)) {
            codeword[pos] = (codeword[pos].toInt() xor 0x3C).toByte()
        }

        val result = ReedSolomon.decode(codeword, nsym = 16)

        assertNotNull("RS(48,32) corrects up to 8 byte errors; 6 must succeed", result)
        assertArrayEquals(data, result!!.data)
        assertEquals(6, result.correctedErrors)
    }

    @Test
    fun `decode corrects byte errors in a shortened RS(48,32) final block`() {
        val data = ByteArray(14) { (it * 11 + 1).toByte() }
        val codeword = ReedSolomon.encode(data, nsym = 16) // 30-byte shortened codeword
        codeword[0] = (codeword[0].toInt() xor 0xFF).toByte()
        codeword[29] = (codeword[29].toInt() xor 0x0F).toByte()

        val result = ReedSolomon.decode(codeword, nsym = 16)

        assertNotNull(result)
        assertArrayEquals(data, result!!.data)
        assertEquals(2, result.correctedErrors)
    }

    @Test
    fun `randomized decode round trips recover the original data through up to the correctable error ceiling`() {
        val random = Random(5678)
        repeat(100) {
            val k = random.nextInt(1, 33)
            val nsym = if (random.nextBoolean()) 8 else 16
            val maxCorrectable = nsym / 2
            val numErrors = random.nextInt(0, maxCorrectable + 1)
            val data = ByteArray(k) { random.nextInt(0, 256).toByte() }
            val codeword = ReedSolomon.encode(data, nsym)

            val corruptedPositions = (0 until (k + nsym)).shuffled(random).take(numErrors)
            for (pos in corruptedPositions) {
                var corrupted: Int
                do {
                    corrupted = random.nextInt(0, 256)
                } while (corrupted == (codeword[pos].toInt() and 0xFF))
                codeword[pos] = corrupted.toByte()
            }

            val result = ReedSolomon.decode(codeword, nsym)
            assertNotNull("decode must succeed with $numErrors <= $maxCorrectable errors (k=$k, nsym=$nsym)", result)
            assertArrayEquals(data, result!!.data)
            assertTrue(result.correctedErrors <= numErrors)
        }
    }

    /**
     * Evaluates [codeword] (descending-degree coefficients, i.e. `codeword[0]` is the
     * highest-degree term — see [ReedSolomon]'s KDoc) at `alpha^power` for every `power` in
     * `0 until nsym` and asserts the result is 0, i.e. every one of the generator's roots is
     * also a root of the codeword polynomial.
     */
    private fun assertAllRootsZero(codeword: ByteArray, nsym: Int) {
        val n = codeword.size
        for (power in 0 until nsym) {
            var total = 0
            for (i in codeword.indices) {
                val c = codeword[i].toInt() and 0xFF
                if (c == 0) continue
                val degree = n - 1 - i
                total = total xor GF256.mul(c, GF256.exp((power * degree) % 255))
            }
            assertEquals("codeword must vanish at alpha^$power (nsym=$nsym)", 0, total)
        }
    }
}
