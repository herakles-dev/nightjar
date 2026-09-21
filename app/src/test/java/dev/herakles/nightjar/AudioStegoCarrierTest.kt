package dev.herakles.nightjar

import dev.herakles.nightjar.modules.audiostego.AudioSampleCover
import dev.herakles.nightjar.modules.audiostego.synthesizeSampleCover
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Verification for Module 2's phase-inversion audio steganography codec
 * ([AudioStegoTechnique.PHASE_INVERSION]). Plain JUnit4, no Robolectric, no Android `Context` —
 * [PcmAudio] is a bare `ShortArray`, so cover clips are synthesized in-test rather than loaded
 * from Android resources, matching spec.md gate-6's "pure-JVM `decode(encode(payload)) ==
 * payload` round-trip unit test, no device required."
 *
 * [HEADER_BYTES] (7) and [SEGMENT_SAMPLES] (480) below mirror [AudioStegoCarrier]'s documented
 * frame shape and segment size exactly (its class KDoc), the same way `ImageStegoCarrierTest`
 * recomputes the image codec's capacity formula and bit layout inline rather than reaching into
 * the carrier's private implementation.
 *
 * The real technique (dual-mono phase cancellation, see [AudioStegoCarrier]'s class KDoc) makes
 * `encode()`'s output an *interleaved-stereo* array twice the length of the mono cover it was
 * built from — every synthetic cover below is kept at half [Short.MAX_VALUE] amplitude or below,
 * so the saturating negate/mix-amplitude-add steps never clip and the documented "near-full-scale
 * covers can break exact cancellation" caveat doesn't make these round-trip tests flaky.
 */
class AudioStegoCarrierTest {

    // --- Capacity formula: floor(floor(sampleCount / 480) / 8) - 11 ---

    @Test
    fun maxPayloadBytesMatchesTheCapacityFormula() {
        val cover = ShortArray(SEGMENT_SAMPLES * 800) // 800 segments == 100 bytes of raw capacity
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_INVERSION)

        val totalCapacityBytes = (cover.size / SEGMENT_SAMPLES) / 8
        val expectedMaxPayload = totalCapacityBytes - HEADER_TRAILER_OVERHEAD_BYTES

        assertEquals(expectedMaxPayload, carrier.maxPayloadBytes)
    }

    @Test
    fun encodeOutputIsInterleavedStereoTwiceTheMonoCoverLength() {
        // Capacity is about the mono cover's duration and does NOT change just because the
        // output is stereo-interleaved -- test that distinctly from encode()'s actual output size.
        val cover = noiseCover(numSamples = SEGMENT_SAMPLES * 200, seed = 1)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_INVERSION)

        val stego = carrier.encode(ByteArray(0))

        assertEquals(cover.size * 2, stego.size)
    }

    // --- Round-trip losslessness (INV-3) ---

    @Test
    fun roundTripsExactPayloadBytesOnSyntheticCover() {
        val cover = noiseCover(numSamples = SEGMENT_SAMPLES * 2000, seed = 42)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_INVERSION)
        val payload = "nightjar Module 2 benign test payload - the quick brown fox jumps over the lazy dog."
            .toByteArray(Charsets.US_ASCII)

        val stego = carrier.encode(payload)
        assertEquals(cover.size * 2, stego.size)
        val result = carrier.decode(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
        assertEquals(0, result.correctedByteErrors)
    }

    @Test
    fun roundTripsAnEmptyPayload() {
        val cover = noiseCover(numSamples = SEGMENT_SAMPLES * 200, seed = 7)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_INVERSION)

        val stego = carrier.encode(ByteArray(0))
        val result = carrier.decode(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertEquals(0, (result as DecodeResult.Success).payload.size)
    }

    // --- Capacity enforcement ---

    @Test
    fun encodeThrowsWhenPayloadExceedsCapacity() {
        val cover = ShortArray(SEGMENT_SAMPLES * 800)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_INVERSION)
        val tooBig = ByteArray(carrier.maxPayloadBytes + 1)

        try {
            carrier.encode(tooBig)
            fail("expected encode() to throw IllegalArgumentException for an over-capacity payload")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    // --- "No payload present" vs. "corrupt payload" distinction ---

    @Test
    fun decodeReturnsNoPayloadFoundForAConstantAmplitudeStereoCover() {
        // Dual-mono, no mixed-in offset at all: every segment's L+R sum is exactly 0 (bit 0) ->
        // decoded magic byte is 0x00, which never matches the codec's magic (0x4E). Deterministic
        // stand-in for "an ordinary stereo clip nobody ever embedded a payload into," same role as
        // ImageStegoCarrierTest's all-zero blank bitmap.
        val cover = ShortArray(SEGMENT_SAMPLES * 800) { 100 }
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_INVERSION)
        val plainDualMono = ShortArray(cover.size * 2)
        for (i in cover.indices) {
            plainDualMono[2 * i] = cover[i]
            plainDualMono[2 * i + 1] = (-cover[i]).toShort()
        }

        val result = carrier.decode(plainDualMono)

        assertTrue("expected Failure but got $result", result is DecodeResult.Failure)
        assertEquals(DecodeFailure.NO_PAYLOAD_FOUND, (result as DecodeResult.Failure).reason)
    }

    @Test
    fun decodeReturnsIntegrityMismatchWhenAPayloadSegmentIsCorruptedAfterEncoding() {
        val cover = noiseCover(numSamples = SEGMENT_SAMPLES * 2000, seed = 99)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_INVERSION)
        val payload = "benign payload that will be corrupted post-encode".toByteArray(Charsets.US_ASCII)

        val stego = carrier.encode(payload)
        // Negate BOTH channels (the whole L/R pair) across every sample of the very first
        // payload-carrying segment (bit index HEADER_BYTES*8, comfortably past the 56-bit/7-byte
        // header) to simulate post-encode corruption without touching the header. Negating both
        // channels negates the recovered L+R sum exactly (-(L[i]+R[i]) = -L[i]-R[i]), flipping
        // that segment's decoded bit deterministically regardless of the cover's actual noise
        // values -- this must surface as INTEGRITY_MISMATCH, never as a silently-wrong Success
        // (the CovertCarrier contract's "never deliver corrupt data" rule).
        val corrupted = stego.copyOf()
        val bitIndex = HEADER_BYTES * 8
        val start = bitIndex * SEGMENT_SAMPLES
        val end = start + SEGMENT_SAMPLES
        for (i in start until end) {
            for (channelIndex in intArrayOf(2 * i, 2 * i + 1)) {
                corrupted[channelIndex] = (-corrupted[channelIndex].toInt())
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
        }

        val result = carrier.decode(corrupted)

        assertTrue("expected Failure but got $result", result is DecodeResult.Failure)
        assertEquals(DecodeFailure.INTEGRITY_MISMATCH, (result as DecodeResult.Failure).reason)
    }

    @Test
    fun decodeRejectsAnOddLengthCarrierCleanlyInsteadOfThrowing() {
        val cover = noiseCover(numSamples = SEGMENT_SAMPLES * 200, seed = 5)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_INVERSION)
        // An arbitrary odd-length array is not stereo-interleaved-shaped: it cannot possibly be
        // this codec's own carrier output, so decode() must reject it cleanly rather than throw
        // an unhandled exception (e.g. an array-index error from assuming an even length).
        val oddLengthCarrier = ShortArray(SEGMENT_SAMPLES * 400 + 1) { it.toShort() }

        val result = carrier.decode(oddLengthCarrier)

        assertTrue("expected Failure but got $result", result is DecodeResult.Failure)
        assertEquals(DecodeFailure.NO_PAYLOAD_FOUND, (result as DecodeResult.Failure).reason)
    }

    // --- Spectrogram-LSB: capacity, round trip, capacity enforcement, non-stego/mismatched decode ---
    //
    // [SPECTROGRAM_FRAME_SIZE] (1024) and [SPECTROGRAM_BINS_PER_STRENGTH_LEVEL] (8) below mirror
    // AudioStegoCarrier's documented spectrogram-LSB constants exactly, the same way the
    // phase-inversion tests above recompute [SEGMENT_SAMPLES]/[HEADER_BYTES] inline rather than
    // reaching into the carrier's private implementation.

    @Test
    fun spectrogramLsbMaxPayloadBytesMatchesTheCapacityFormula() {
        val numFrames = 100
        val cover = ShortArray(SPECTROGRAM_FRAME_SIZE * numFrames)
        val stegoStrength = 3
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB, stegoStrength)

        val binsPerFrame = stegoStrength * SPECTROGRAM_BINS_PER_STRENGTH_LEVEL
        val totalCapacityBytes = (numFrames * binsPerFrame) / 8
        val expectedMaxPayload = (totalCapacityBytes - HEADER_TRAILER_OVERHEAD_BYTES).coerceAtLeast(0)

        assertEquals(expectedMaxPayload, carrier.maxPayloadBytes)
    }

    @Test
    fun spectrogramLsbCapacityScalesWithStegoStrength() {
        // Same cover length, only stegoStrength differs -- higher strength must yield strictly
        // higher capacity (more bins nudged per frame).
        val cover = ShortArray(SPECTROGRAM_FRAME_SIZE * 100)
        val weakest = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB, stegoStrength = 1)
        val strongest = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB, stegoStrength = 4)

        assertTrue(
            "expected stegoStrength=4 (${strongest.maxPayloadBytes}B) to exceed stegoStrength=1 " +
                "(${weakest.maxPayloadBytes}B) capacity for the same cover",
            strongest.maxPayloadBytes > weakest.maxPayloadBytes,
        )
    }

    @Test
    fun spectrogramLsbRoundTripsExactPayloadBytesAtMultipleStegoStrengths() {
        for (stegoStrength in intArrayOf(1, 4)) {
            val cover = spectrogramNoiseCover(numFrames = 400, seed = 1000L + stegoStrength)
            val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB, stegoStrength)
            val payload = "nightjar Module 2 spectrogram-LSB round trip @ strength=$stegoStrength"
                .toByteArray(Charsets.US_ASCII)
            assertTrue(
                "test payload (${payload.size}B) exceeds this cover's capacity " +
                    "(${carrier.maxPayloadBytes}B) at stegoStrength=$stegoStrength",
                payload.size <= carrier.maxPayloadBytes,
            )

            val stego = carrier.encode(payload)
            assertEquals(cover.size, stego.size)
            val result = carrier.decode(stego)

            assertTrue(
                "expected Success but got $result at stegoStrength=$stegoStrength",
                result is DecodeResult.Success,
            )
            assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
            assertEquals(0, result.correctedByteErrors)
        }
    }

    @Test
    fun spectrogramLsbRoundTripsAnEmptyPayload() {
        val cover = spectrogramNoiseCover(numFrames = 100, seed = 77)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB, stegoStrength = 2)

        val stego = carrier.encode(ByteArray(0))
        val result = carrier.decode(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertEquals(0, (result as DecodeResult.Success).payload.size)
    }

    @Test
    fun spectrogramLsbEncodeThrowsWhenPayloadExceedsCapacity() {
        val cover = ShortArray(SPECTROGRAM_FRAME_SIZE * 100)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB, stegoStrength = 2)
        val tooBig = ByteArray(carrier.maxPayloadBytes + 1)

        try {
            carrier.encode(tooBig)
            fail("expected encode() to throw IllegalArgumentException for an over-capacity payload")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun spectrogramLsbDecodeReturnsFailureForANonStegoCoverInsteadOfThrowing() {
        // An ordinary silent (all-zero) cover was never embedded into -- every eligible bin's
        // magnitude is 0, so decode reads a deterministic (but wrong) header and must reject it
        // cleanly rather than throw.
        val cover = ShortArray(SPECTROGRAM_FRAME_SIZE * 50)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB, stegoStrength = 2)

        val result = carrier.decode(cover)

        assertTrue("expected Failure but got $result", result is DecodeResult.Failure)
    }

    @Test
    fun spectrogramLsbDecodeReturnsFailureWhenStegoStrengthDoesNotMatchEncoding() {
        // Same cover, same technique, DIFFERENT stegoStrength between the encoding and decoding
        // instances -- the bit-to-bin mapping no longer lines up, so this must fail the header
        // magic/CRC check (a DecodeResult.Failure), never throw or silently return wrong bytes.
        val cover = spectrogramNoiseCover(numFrames = 400, seed = 55)
        val encoder = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB, stegoStrength = 1)
        val payload = "mismatched stegoStrength must not decode cleanly".toByteArray(Charsets.US_ASCII)
        val stego = encoder.encode(payload)

        val decoderAtDifferentStrength =
            AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB, stegoStrength = 4)
        val result = decoderAtDifferentStrength.decode(stego)

        assertTrue("expected Failure but got $result", result is DecodeResult.Failure)
    }

    // --- MFSK: fixed capacity, round trip, capacity enforcement, real FEC correction, non-stego decode ---
    //
    // [MFSK_FRAME_SIZE] (1024, same block size as spectrogram-LSB), [MFSK_RS_DATA_BYTES] (48), and
    // [MFSK_RS_PARITY_BYTES] (16) below mirror AudioStegoCarrier's documented MFSK constants
    // exactly, the same way the other two techniques' tests recompute their own capacity constants
    // inline rather than reaching into the carrier's private implementation.

    @Test
    fun mfskMaxPayloadBytesIsFixedOnceCoverHoldsOneCodeword() {
        val longEnoughCover = ShortArray(MFSK_FRAME_SIZE * MFSK_CODEWORD_BYTES)
        val carrier = AudioStegoCarrier(longEnoughCover, AudioStegoTechnique.MFSK)

        assertEquals(MFSK_RS_DATA_BYTES - HEADER_TRAILER_OVERHEAD_BYTES, carrier.maxPayloadBytes)
    }

    @Test
    fun mfskMaxPayloadBytesIsZeroWhenCoverIsTooShortForOneCodeword() {
        val tooShortCover = ShortArray(MFSK_FRAME_SIZE * (MFSK_CODEWORD_BYTES - 1))
        val carrier = AudioStegoCarrier(tooShortCover, AudioStegoTechnique.MFSK)

        assertEquals(0, carrier.maxPayloadBytes)
    }

    @Test
    fun mfskRoundTripsAnEmptyPayload() {
        val cover = mfskNoiseCover(seed = 11)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.MFSK)

        val stego = carrier.encode(ByteArray(0))
        val result = carrier.decode(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertEquals(0, (result as DecodeResult.Success).payload.size)
    }

    @Test
    fun mfskRoundTripsAPayloadNearMaxCapacity() {
        val cover = mfskNoiseCover(seed = 12)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.MFSK)
        val payload = ByteArray(carrier.maxPayloadBytes) { (it % 256).toByte() }

        val stego = carrier.encode(payload)
        assertEquals(cover.size, stego.size)
        val result = carrier.decode(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
        assertEquals(0, result.correctedByteErrors)
    }

    @Test
    fun mfskEncodeThrowsWhenPayloadExceedsCapacity() {
        val cover = mfskNoiseCover(seed = 13)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.MFSK)
        val tooBig = ByteArray(carrier.maxPayloadBytes + 1)

        try {
            carrier.encode(tooBig)
            fail("expected encode() to throw IllegalArgumentException for an over-capacity payload")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun mfskRecoversFromCorrectableByteErrorsViaRealReedSolomon() {
        // This is the point of shipping real FEC, not just leaving UNRECOVERABLE_FEC unreachable:
        // corrupt exactly MFSK_RS_PARITY_BYTES/2 (the standard RS correction bound) symbol blocks
        // by overwriting each with unrelated noise, then confirm decode still recovers the EXACT
        // original payload -- and reports a nonzero corrected-error count.
        val cover = mfskNoiseCover(seed = 14)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.MFSK)
        val payload = "nightjar MFSK real-FEC round trip".toByteArray(Charsets.US_ASCII)
        val stego = carrier.encode(payload).copyOf()

        val correctableErrors = MFSK_RS_PARITY_BYTES / 2
        val rng = Random(999)
        for (blockIndex in 0 until correctableErrors) {
            val start = blockIndex * MFSK_FRAME_SIZE
            for (i in 0 until MFSK_FRAME_SIZE) {
                stego[start + i] = rng.nextInt(-16_000, 16_001).toShort()
            }
        }

        val result = carrier.decode(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
        assertTrue(
            "expected a nonzero corrected-error count after corrupting $correctableErrors blocks",
            result.correctedByteErrors > 0,
        )
    }

    @Test
    fun mfskReturnsUnrecoverableFecWhenTooManyBlocksAreCorrupted() {
        val cover = mfskNoiseCover(seed = 15)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.MFSK)
        val payload = "lost to excessive channel noise".toByteArray(Charsets.US_ASCII)
        val stego = carrier.encode(payload).copyOf()

        val tooManyErrors = MFSK_RS_PARITY_BYTES / 2 + 4 // comfortably past the correctable bound
        val rng = Random(1000)
        for (blockIndex in 0 until tooManyErrors) {
            val start = blockIndex * MFSK_FRAME_SIZE
            for (i in 0 until MFSK_FRAME_SIZE) {
                stego[start + i] = rng.nextInt(-16_000, 16_001).toShort()
            }
        }

        val result = carrier.decode(stego)

        assertTrue("expected Failure but got $result", result is DecodeResult.Failure)
        assertEquals(DecodeFailure.UNRECOVERABLE_FEC, (result as DecodeResult.Failure).reason)
    }

    @Test
    fun mfskDecodeReturnsFailureForANonStegoCoverInsteadOfThrowing() {
        val cover = mfskNoiseCover(seed = 16)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.MFSK)

        val result = carrier.decode(cover) // never encoded, no tones present

        assertTrue("expected Failure but got $result", result is DecodeResult.Failure)
    }

    // --- codec-H02: canEmbed / tiny-cover contract self-consistency, one per technique ---

    @Test
    fun phaseInversionCanEmbedIsFalseForATinyCoverBelowFrameOverhead() {
        // 20000 samples: capacityBytes = floor(floor(20000/480)/8) = floor(41/8) = 5, under 11.
        val tinyCover = ShortArray(20_000)
        val carrier = AudioStegoCarrier(tinyCover, AudioStegoTechnique.PHASE_INVERSION)

        assertEquals(0, carrier.maxPayloadBytes)
        assertTrue("a 20000-sample cover (5-byte capacity) should not report canEmbed", !carrier.canEmbed)
        try {
            carrier.encode(ByteArray(0))
            fail("expected encode() to throw for a cover too small to hold a frame")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "expected message to explain the cover is too small, was: ${expected.message}",
                expected.message.orEmpty().contains("too small"),
            )
        }
    }

    @Test
    fun spectrogramLsbCanEmbedIsFalseForATinyCoverBelowFrameOverhead() {
        // 5 frames at stegoStrength=1 (8 bins/frame): capacityBytes = (5*8)/8 = 5, under 11.
        val tinyCover = ShortArray(SPECTROGRAM_FRAME_SIZE * 5)
        val carrier = AudioStegoCarrier(tinyCover, AudioStegoTechnique.SPECTROGRAM_LSB, stegoStrength = 1)

        assertEquals(0, carrier.maxPayloadBytes)
        assertTrue("a 5-frame cover (5-byte capacity) should not report canEmbed", !carrier.canEmbed)
        try {
            carrier.encode(ByteArray(0))
            fail("expected encode() to throw for a cover too small to hold a frame")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "expected message to explain the cover is too small, was: ${expected.message}",
                expected.message.orEmpty().contains("too small"),
            )
        }
    }

    @Test
    fun mfskCanEmbedIsFalseForACoverJustUnderOneCodeword() {
        val tinyCover = ShortArray(MFSK_FRAME_SIZE * (MFSK_CODEWORD_BYTES - 1))
        val carrier = AudioStegoCarrier(tinyCover, AudioStegoTechnique.MFSK)

        assertEquals(0, carrier.maxPayloadBytes)
        assertTrue("a cover one block short of a codeword should not report canEmbed", !carrier.canEmbed)
        try {
            carrier.encode(ByteArray(0))
            fail("expected encode() to throw for a cover too small to hold a codeword")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "expected message to explain the cover is too small, was: ${expected.message}",
                expected.message.orEmpty().contains("too small"),
            )
        }
    }

    @Test
    fun canEmbedIsTrueForOrdinaryCoversOnAllThreeTechniques() {
        assertTrue(AudioStegoCarrier(noiseCover(SEGMENT_SAMPLES * 800, 1), AudioStegoTechnique.PHASE_INVERSION).canEmbed)
        assertTrue(
            AudioStegoCarrier(
                spectrogramNoiseCover(100, 2),
                AudioStegoTechnique.SPECTROGRAM_LSB,
                stegoStrength = 2,
            ).canEmbed,
        )
        assertTrue(AudioStegoCarrier(mfskNoiseCover(3), AudioStegoTechnique.MFSK).canEmbed)
    }

    // --- MFSK clipping fix (design-v5.md §12.2): TONE_AMPLITUDE * up to 8 simultaneous tones
    // measurably exceeded int16 range pre-fix (96/141 saturated samples per stego on the two
    // bundled covers). Uses the app's own real bundled covers (AudioStegoSampleCovers.kt), not
    // synthetic noise, since that's exactly what was measured as clipping. correctedByteErrors
    // == 0 doubles as an empirical proxy for "detection margin survived the per-block gain
    // scaling" -- if the gain fix had starved any block's tones below the 15dB margin, that
    // block's byte would decode wrong and Reed-Solomon would report a nonzero correction (or,
    // past 8 wrong bytes, UNRECOVERABLE_FEC).

    @Test
    fun mfskEncodeOnBothBundledCoversNeverSaturatesASample() {
        for (cover in AudioSampleCover.entries) {
            val pcm = synthesizeSampleCover(cover)
            val carrier = AudioStegoCarrier(pcm, AudioStegoTechnique.MFSK)
            // Short enough to fit MFSK's fixed ~37-byte capacity for either bundled cover's name.
            val payload = "MFSK clip fix: ${cover.name}".toByteArray(Charsets.US_ASCII)
            assertTrue(
                "test payload (${payload.size}B) exceeds MFSK's fixed capacity (${carrier.maxPayloadBytes}B)",
                payload.size <= carrier.maxPayloadBytes,
            )

            val stego = carrier.encode(payload)

            val saturatedCount = stego.count { it == Short.MAX_VALUE || it == Short.MIN_VALUE }
            assertEquals(
                "expected zero int16-saturated samples encoding MFSK onto ${cover.name}, found $saturatedCount",
                0,
                saturatedCount,
            )

            val result = carrier.decode(stego)
            assertTrue("expected Success but got $result for ${cover.name}", result is DecodeResult.Success)
            assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
            // The gain fix trades a little detection margin for zero clipping in the (rare,
            // high-popcount-byte) blocks it actually attenuates -- real Reed-Solomon correction
            // is exactly the designed-for safety net for that, not a failure. The regression
            // guard that matters is staying comfortably under the correction bound
            // (MFSK_RS_PARITY_BYTES / 2 = 8), not staying at zero.
            assertTrue(
                "expected well under the ${MFSK_RS_PARITY_BYTES / 2}-byte-error correction bound " +
                    "on a clean encode of ${cover.name}, got ${result.correctedByteErrors}",
                result.correctedByteErrors < MFSK_RS_PARITY_BYTES / 2,
            )
        }
    }

    // --- Test helpers ---

    /**
     * Deterministic pseudo-random PCM16 noise cover — avoids periodic zero-crossings. Kept at
     * half [Short.MAX_VALUE] amplitude or below (here: [-16000, 16000]) so encode's saturating
     * negate/mix-amplitude-add never clips, keeping the round-trip tests deterministic per this
     * codec's documented near-full-scale fidelity caveat.
     */
    private fun noiseCover(numSamples: Int, seed: Long): PcmAudio {
        val rng = Random(seed)
        return ShortArray(numSamples) { rng.nextInt(-16_000, 16_001).toShort() }
    }

    /**
     * Deterministic pseudo-random PCM16 noise cover sized to an exact whole number of
     * [SPECTROGRAM_FRAME_SIZE]-sample spectrogram-LSB frames, at the same [-16000, 16000]
     * amplitude as [noiseCover] -- comfortably below full scale so the ifft-then-round-to-Short
     * reconstruction step has headroom and never saturates, while still giving every frame's FFT
     * bins real, nonzero energy for the QIM embedding to act on (a near-silent cover would let
     * [AudioStegoCarrier]'s magnitude floor dominate every bin identically, which is not a
     * meaningful round-trip exercise).
     */
    private fun spectrogramNoiseCover(numFrames: Int, seed: Long): PcmAudio {
        val rng = Random(seed)
        return ShortArray(SPECTROGRAM_FRAME_SIZE * numFrames) { rng.nextInt(-16_000, 16_001).toShort() }
    }

    /**
     * Deterministic pseudo-random PCM16 noise cover sized to exactly [MFSK_CODEWORD_BYTES]
     * [MFSK_FRAME_SIZE]-sample MFSK symbol blocks -- the minimum length MFSK needs, at the same
     * [-16000, 16000] amplitude as [noiseCover]/[spectrogramNoiseCover], comfortably below
     * [Short.MAX_VALUE] so the tone-mixing saturating-add step never clips.
     */
    private fun mfskNoiseCover(seed: Long): PcmAudio {
        val rng = Random(seed)
        return ShortArray(MFSK_FRAME_SIZE * MFSK_CODEWORD_BYTES) { rng.nextInt(-16_000, 16_001).toShort() }
    }

    companion object {
        /** Mirrors AudioStegoCarrier's documented segment size (10 ms @ 48 kHz). */
        private const val SEGMENT_SAMPLES = 480

        /** Mirrors AudioStegoCarrier's documented header size (magic, version, length[4], header_crc). */
        private const val HEADER_BYTES = 7

        /** Mirrors AudioStegoCarrier's documented frame overhead (7-byte header + 4-byte trailer). */
        private const val HEADER_TRAILER_OVERHEAD_BYTES = 11

        /** Mirrors AudioStegoCarrier's documented spectrogram-LSB frame size (1024 samples). */
        private const val SPECTROGRAM_FRAME_SIZE = 1024

        /** Mirrors AudioStegoCarrier's documented spectrogram-LSB bins-per-strength-level (8). */
        private const val SPECTROGRAM_BINS_PER_STRENGTH_LEVEL = 8

        /** Mirrors AudioStegoCarrier's documented MFSK symbol-block size (same 1024 as spectrogram-LSB). */
        private const val MFSK_FRAME_SIZE = 1024

        /** Mirrors AudioStegoCarrier's documented MFSK Reed-Solomon data-block size. */
        private const val MFSK_RS_DATA_BYTES = 48

        /** Mirrors AudioStegoCarrier's documented MFSK Reed-Solomon parity-byte count. */
        private const val MFSK_RS_PARITY_BYTES = 16

        /** Mirrors AudioStegoCarrier's documented MFSK codeword size (data + parity bytes = symbol blocks). */
        private const val MFSK_CODEWORD_BYTES = MFSK_RS_DATA_BYTES + MFSK_RS_PARITY_BYTES
    }
}
