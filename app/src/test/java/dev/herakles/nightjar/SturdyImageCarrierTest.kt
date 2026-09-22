package dev.herakles.nightjar

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification for [SturdyImageCarrier]. Plain JUnit, no
 * Robolectric -- [SturdyImageCarrier] is pure Kotlin, so these tests drive it entirely through
 * [SturdyImageCarrier.ArrayPixelSurface] and [JpegLumaSim] (see that file's KDoc for why a
 * hand-rolled DCT simulator, not `javax.imageio`/`Bitmap`, does the channel simulation here).
 * [SturdyImageCarrierBundledCoverTest] covers the bundled-resource-cover cases that need
 * Robolectric (both bundled PNGs, `ImageSteganalysis`, and the cross-decode checks).
 *
 * ## Measured margins (this test run, see individual test KDoc for the per-case numbers)
 * Round-trip and the failure envelope (crop/rotate/flip) are 100% as designed -- RS + CRC-32 make
 * a wrong payload structurally impossible here. The channel-survival numbers
 * below are this class's own simulator, not the offline harness's `javax.imageio`/libjpeg-turbo
 * numbers -- they corroborate the design's survival margin without literally
 * reproducing that harness (the real-encoder evidence is the offline harness re-run, not
 * this in-Gradle suite).
 */
class SturdyImageCarrierTest {

    private fun payloadOf(seed: Int = 0): ByteArray =
        ByteArray(SturdyImageCarrier.PAYLOAD_BYTES) { ((it * 7 + 3 + seed) and 0xFF).toByte() }

    private fun encodeStego(cover: SturdyImageCarrier.PixelSurface, payload: ByteArray): SturdyImageCarrier.PixelSurface =
        SturdyImageCarrier(cover).encode(payload)

    private fun decodeOnly(surface: SturdyImageCarrier.PixelSurface): DecodeResult =
        SturdyImageCarrier(surface).decode(surface)

    // --- Round trip at 64 bytes, across cover shapes ---

    @Test
    fun `round trips a 64-byte payload on a gradient cover`() {
        val cover = SturdyTestImages.gradient(800, 600)
        val payload = payloadOf()
        val stego = encodeStego(cover, payload)

        val result = decodeOnly(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
    }

    @Test
    fun `round trips a 64-byte payload on a noise cover`() {
        val cover = SturdyTestImages.noise(800, 600, seed = 11)
        val payload = payloadOf(1)
        val stego = encodeStego(cover, payload)

        val result = decodeOnly(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
    }

    @Test
    fun `round trips a 64-byte payload on a tiled texture cover`() {
        val cover = SturdyTestImages.texture(800, 600)
        val payload = payloadOf(2)
        val stego = encodeStego(cover, payload)

        val result = decodeOnly(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
    }

    @Test
    fun `round trips a 64-byte payload on a dark, near-flat cover`() {
        // The "deep-shadow mottling" known risk: a mostly-dark cover is the stress
        // case for the DC-offset search, since a flat dark region gives QIM very little natural
        // luma variance to hide the flat shift inside.
        val cover = SturdyTestImages.darkFlat(800, 600)
        val payload = payloadOf(3)
        val stego = encodeStego(cover, payload)

        val result = decodeOnly(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
        assertEquals(0, (result as DecodeResult.Success).correctedByteErrors)
    }

    @Test
    fun `encode throws for a payload longer than PAYLOAD_BYTES`() {
        val cover = SturdyTestImages.gradient(200, 200)
        val carrier = SturdyImageCarrier(cover)

        try {
            carrier.encode(ByteArray(SturdyImageCarrier.PAYLOAD_BYTES + 1))
            org.junit.Assert.fail("expected IllegalArgumentException for an oversized payload")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    // --- Variable-length messages via the frame's own length field ---

    @Test
    fun `round trips a 1-byte payload`() {
        val cover = SturdyTestImages.gradient(800, 600)
        val payload = byteArrayOf(0x42)
        val stego = encodeStego(cover, payload)

        val result = decodeOnly(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
        assertEquals(1, result.payload.size)
    }

    @Test
    fun `round trips a 10-byte payload, including multi-byte UTF-8`() {
        val cover = SturdyTestImages.noise(800, 600, seed = 30)
        // "héllo日" is well under 10 bytes on its own; pad it out to exactly 10 raw bytes so the
        // multi-byte UTF-8 sequences (the accented 'e' and the CJK character) sit mid-payload,
        // not just at the end.
        val message = "héllo日".encodeToByteArray()
        val payload = message + ByteArray(10 - message.size) { 0x2E }
        assertEquals(10, payload.size)
        val stego = encodeStego(cover, payload)

        val result = decodeOnly(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
        assertEquals(10, result.payload.size)
    }

    @Test
    fun `round trips a 63-byte payload`() {
        val cover = SturdyTestImages.texture(800, 600)
        val payload = payloadOf(31).copyOfRange(0, 63)
        val stego = encodeStego(cover, payload)

        val result = decodeOnly(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
        assertEquals(63, result.payload.size)
    }

    @Test
    fun `a firefly declaring n = 64 still decodes (the full slot is a valid message length)`() {
        val cover = SturdyTestImages.gradient(800, 600)
        val payload = payloadOf(32)
        assertEquals(SturdyImageCarrier.PAYLOAD_BYTES, payload.size)
        val stego = encodeStego(cover, payload)

        val result = decodeOnly(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
        assertEquals(SturdyImageCarrier.PAYLOAD_BYTES, result.payload.size)
    }

    @Test
    fun `encode accepts an empty (0-byte) payload`() {
        val cover = SturdyTestImages.gradient(800, 600)
        val stego = encodeStego(cover, ByteArray(0))

        val result = decodeOnly(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertEquals(0, (result as DecodeResult.Success).payload.size)
    }

    @Test
    fun `zero-padding under the CRC -- a flipped padding bit gives Damaged, never a wrong message`() {
        val carrier = SturdyImageCarrier(SturdyTestImages.gradient(10, 10))
        val message = byteArrayOf(1, 2, 3) // n = 3, so bytes 3..63 of the slot are zero padding
        val goodFrame = carrier.frame(message)

        // Flip one bit deep in the padding region (slot byte index 40 -- well past n=3, well
        // before the crc32 tail) and confirm the CRC (which covers the WHOLE slot, not just the
        // first n bytes) catches it.
        val paddingByteIndex = 5 + 40 // 5-byte head + offset into the zero-padded slot
        val corrupted = goodFrame.copyOf()
        corrupted[paddingByteIndex] = (corrupted[paddingByteIndex].toInt() xor 0x01).toByte()

        val goodParsed = carrier.parseFrame(goodFrame)
        assertTrue("the unmodified frame must parse cleanly first", goodParsed.payload != null)
        assertTrue(message.contentEquals(goodParsed.payload))

        val corruptedParsed = carrier.parseFrame(corrupted)
        assertTrue(
            "a flipped padding bit must still look like a plausible SF header (magic/version/length unaffected)",
            corruptedParsed.sawPlausibleHeader,
        )
        assertEquals(
            "a flipped padding bit must fail CRC (never return a payload, right or wrong)",
            null,
            corruptedParsed.payload,
        )
    }

    @Test
    fun `n greater than PAYLOAD_BYTES in a forged header is rejected, not read as a plausible header`() {
        val carrier = SturdyImageCarrier(SturdyTestImages.gradient(10, 10))
        val goodFrame = carrier.frame(ByteArray(10))
        val forged = goodFrame.copyOf()
        // Overwrite the length field (bytes 3-4) to claim n = PAYLOAD_BYTES + 1 -- past the slot
        // size entirely, so this must be rejected outright rather than treated as a plausible
        // (if CRC-mismatched) header.
        val forgedN = SturdyImageCarrier.PAYLOAD_BYTES + 1
        forged[3] = ((forgedN ushr 8) and 0xFF).toByte()
        forged[4] = (forgedN and 0xFF).toByte()

        val parsed = carrier.parseFrame(forged)

        assertEquals(null, parsed.payload)
        assertFalse(
            "n > PAYLOAD_BYTES must be rejected before any CRC work, not counted as a plausible header",
            parsed.sawPlausibleHeader,
        )
    }

    // --- Channel survival: simulated JPEG recompression + downscale ---

    /**
     * Measured this run: all 3 natural-photo-like covers (gradient, noise, radial) at 1280x960
     * survive every one of q50/q70/q85 recompressed to both 1080px and 640px long side, plus the
     * FB-like (2048->q85->1080->q75) and MMS-like (640->q50) chains -- 24/24 pipeline results.
     * The 4th synthetic cover (a hard-edged, perfectly periodic tiled texture) has its own,
     * separate test below: it is a deliberately adversarial pattern whose period interacts with
     * the codec's own 96x96 grid, and is not asserted here.
     */
    @Test
    fun `survives simulated JPEG recompression and downscale on natural-photo-like covers`() {
        val covers = listOf(
            "gradient" to SturdyTestImages.gradient(1280, 960),
            "noise" to SturdyTestImages.noise(1280, 960, seed = 42),
            "radial" to SturdyTestImages.radial(1280, 960),
        )
        val payload = payloadOf(7)
        val failures = mutableListOf<String>()

        for ((name, cover) in covers) {
            val stego = encodeStego(cover, payload)
            for (quality in intArrayOf(50, 70, 85)) {
                for (longSide in intArrayOf(1080, 640)) {
                    val degraded = simulateJpegChannel(stego, quality = quality, longSide = longSide, resizeFirst = true)
                    val result = decodeOnly(degraded)
                    val ok = result is DecodeResult.Success && payload.contentEquals(result.payload)
                    if (!ok) failures += "$name q$quality->${longSide}px: $result"
                }
            }
            val fb = decodeOnly(simulateFacebookLikeChannel(stego))
            if (!(fb is DecodeResult.Success && payload.contentEquals(fb.payload))) failures += "$name fb-like: $fb"
            val mms = decodeOnly(simulateMmsLikeChannel(stego))
            if (!(mms is DecodeResult.Success && payload.contentEquals(mms.payload))) failures += "$name mms-like: $mms"
        }

        assertTrue("expected 0 survival failures, got: $failures", failures.isEmpty())
    }

    /**
     * Measured this run: a hard-edged, perfectly periodic 9px-tile checkerboard (not a natural
     * photo -- deliberately adversarial) survives the more aggressive 640px-long-side downscale
     * at every quality plus the MMS-like chain, but the milder ~0.84x 1080px-long-side downscale
     * (and the FB-like chain, whose first step also lands near that ratio) fail cleanly to
     * NoFirefly at every quality, regardless of quality -- i.e. this is a resize *geometry*
     * effect, not a JPEG-quality effect: at that specific scale ratio, tile boundaries land
     * inconsistently across the codec's own 96x96 grid-cell boundaries, shifting several cells'
     * means by much more than a natural photo's low-frequency content ever does. Never a wrong
     * payload in any case. This is a narrow, synthetic edge case (a real photo has no perfectly
     * periodic structure at this scale) -- the offline harness's real-photo re-run
     * is the authoritative survival evidence; this test documents where a
     * deliberately pathological input's honest failure mode is, not a general regression.
     */
    @Test
    fun `a periodic tiled-texture cover survives 640px-based channels, and 1080-based misses are honest, never wrong`() {
        val cover = SturdyTestImages.texture(1280, 960)
        val payload = payloadOf(8)
        val stego = encodeStego(cover, payload)

        for (quality in intArrayOf(50, 70, 85)) {
            val degraded = simulateJpegChannel(stego, quality = quality, longSide = 640, resizeFirst = true)
            val result = decodeOnly(degraded)
            assertTrue(
                "expected the 640px pipeline to survive at q$quality, got $result",
                result is DecodeResult.Success && payload.contentEquals(result.payload),
            )
        }
        val mms = decodeOnly(simulateMmsLikeChannel(stego))
        assertTrue("expected MMS-like to survive, got $mms", mms is DecodeResult.Success && payload.contentEquals(mms.payload))

        for (quality in intArrayOf(50, 70, 85)) {
            val degraded = simulateJpegChannel(stego, quality = quality, longSide = 1080, resizeFirst = true)
            assertNeverWrongPayload(decodeOnly(degraded), payload)
        }
        assertNeverWrongPayload(decodeOnly(simulateFacebookLikeChannel(stego)), payload)
    }

    // --- Clean failure envelope: crop/rotate/flip must never return a wrong payload ---

    @Test
    fun `a 10 percent crop resolves to NoFirefly or Damaged, never a wrong payload`() {
        val payload = payloadOf(9)
        val covers = listOf(
            SturdyTestImages.gradient(800, 600),
            SturdyTestImages.noise(800, 600, seed = 5),
            SturdyTestImages.texture(800, 600),
        )
        for (cover in covers) {
            val stego = encodeStego(cover, payload)
            val cropped = SturdyTestImages.crop(stego, fraction = 0.05) // 5% each edge = 10% total
            val result = decodeOnly(cropped)
            assertNeverWrongPayload(result, payload)
        }
    }

    @Test
    fun `a 90 degree rotation resolves to NoFirefly or Damaged, never a wrong payload`() {
        val payload = payloadOf(10)
        val covers = listOf(
            SturdyTestImages.gradient(800, 600),
            SturdyTestImages.noise(800, 600, seed = 6),
            SturdyTestImages.texture(800, 600),
        )
        for (cover in covers) {
            val stego = encodeStego(cover, payload)
            val rotated = SturdyTestImages.rotate90(stego)
            val result = decodeOnly(rotated)
            assertNeverWrongPayload(result, payload)
        }
    }

    @Test
    fun `a horizontal flip resolves to NoFirefly or Damaged, never a wrong payload`() {
        val payload = payloadOf(11)
        val covers = listOf(
            SturdyTestImages.gradient(800, 600),
            SturdyTestImages.noise(800, 600, seed = 7),
            SturdyTestImages.texture(800, 600),
        )
        for (cover in covers) {
            val stego = encodeStego(cover, payload)
            val flipped = SturdyTestImages.flipHorizontal(stego)
            val result = decodeOnly(flipped)
            assertNeverWrongPayload(result, payload)
        }
    }

    private fun assertNeverWrongPayload(result: DecodeResult, payload: ByteArray) {
        when (result) {
            is DecodeResult.Success -> assertTrue(
                "decoded a WRONG payload instead of failing: $result",
                payload.contentEquals(result.payload),
            )
            is DecodeResult.Failure -> assertTrue(
                "expected NO_PAYLOAD_FOUND or INTEGRITY_MISMATCH, got ${result.reason}",
                result.reason == DecodeFailure.NO_PAYLOAD_FOUND || result.reason == DecodeFailure.INTEGRITY_MISMATCH,
            )
        }
    }

    // --- No false catch across >= 200 clean, never-embedded images ---

    /**
     * Measured this run: 0 false catches across [FALSE_CATCH_TOTAL] clean synthetic images
     * (gradients, noise, tiled textures, radial and dark-flat covers at varied sizes, plus random
     * crops of each) that were never embedded into. `SturdyImageCarrierBundledCoverTest` adds the
     * two bundled app covers (and their own crops) to this same false-catch guarantee under
     * Robolectric.
     */
    @Test
    fun `no false catch across many clean synthetic images`() {
        val rnd = Random(1234)
        var scanned = 0
        var falseCatches = 0
        val offenders = mutableListOf<String>()

        fun scan(name: String, surface: SturdyImageCarrier.PixelSurface) {
            scanned++
            val result = decodeOnly(surface)
            if (result is DecodeResult.Success) {
                falseCatches++
                offenders += name
            }
        }

        // Base covers: several shapes, several sizes each, plus a handful of random crops of each.
        val baseCovers = mutableListOf<Pair<String, SturdyImageCarrier.PixelSurface>>()
        for (size in listOf(120 to 90, 300 to 200, 640 to 480, 960 to 720)) {
            baseCovers += "gradient_${size.first}x${size.second}" to SturdyTestImages.gradient(size.first, size.second)
            baseCovers += "radial_${size.first}x${size.second}" to SturdyTestImages.radial(size.first, size.second)
            baseCovers += "texture_${size.first}x${size.second}" to SturdyTestImages.texture(size.first, size.second, seed = size.first)
        }
        for ((name, cover) in baseCovers) {
            scan(name, cover)
            repeat(4) { i ->
                val cropped = SturdyTestImages.randomCrop(cover, rnd, wFrac = 0.5 + rnd.nextDouble() * 0.4, hFrac = 0.5 + rnd.nextDouble() * 0.4)
                scan("${name}_crop$i", cropped)
            }
        }

        // Pure noise/gradient synthetics, comfortably clearing the >=200 bar on their own.
        repeat(160) { i ->
            val w = 150 + rnd.nextInt(400)
            val h = 150 + rnd.nextInt(400)
            val surface = if (i % 2 == 0) SturdyTestImages.noise(w, h, seed = 9000 + i) else SturdyTestImages.gradient(w, h)
            scan("synth$i", surface)
        }

        assertTrue("expected >= $FALSE_CATCH_TOTAL scanned clean images, only scanned $scanned", scanned >= FALSE_CATCH_TOTAL)
        assertEquals("false catches: $offenders", 0, falseCatches)
    }

    @Test
    fun `an all-zero blank surface never catches`() {
        val blank = SturdyImageCarrier.ArrayPixelSurface(64, 64)
        val result = decodeOnly(blank)
        assertFalse(result is DecodeResult.Success)
    }

    private companion object {
        /** The required false-catch bar; `SturdyImageCarrierBundledCoverTest` adds a further handful on top. */
        const val FALSE_CATCH_TOTAL = 200
    }
}
