package dev.herakles.nightjar.fuzz

import dev.herakles.nightjar.ReedSolomon

/**
 * Reed-Solomon over GF(256) protects the modem payload. Two properties:
 *  1. decode() of arbitrary (corrupt) codewords must return a result or null, never throw;
 *  2. encode -> decode of a clean codeword must return the original data.
 */
object ReedSolomonFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) {
        if (data.size < 2) return
        val nsym = 2 + (data[0].toInt() and 0x1F) // 2..33 parity symbols
        val body = data.copyOfRange(1, data.size)

        // (1) arbitrary bytes as a received codeword (RS(255) limit: n <= 255)
        if (body.size in (nsym + 1)..255) {
            ReedSolomon.decode(body, nsym)
        }

        // (2) round trip on clean data
        val payload = body.copyOf(minOf(body.size, 255 - nsym))
        if (payload.isNotEmpty()) {
            val coded = ReedSolomon.encode(payload, nsym)
            val back = ReedSolomon.decode(coded, nsym)
            check(back != null && back.data.contentEquals(payload) && back.correctedErrors == 0) {
                "clean RS round trip failed (nsym=$nsym, k=${payload.size})"
            }
        }
    }
}
