package dev.herakles.nightjar

import dev.herakles.nightjar.modules.audiostego.AudioSampleCover
import dev.herakles.nightjar.modules.audiostego.synthesizeSampleCover
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
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
    fun spectrogramLsbMaxPayloadBytesMatchesTheCapacityFormulaOnALoudCover() {
        // v2 (near-silent-frame skip): capacity is content-dependent, not a pure formula -- but
        // for a cover where every frame is loud (real noise, not silence), no frame is ever
        // skipped, so this reduces to: the header costs exactly HEADER_BYTES*8 bits (not
        // headerFrameCount*binsPerFrame -- any leftover bin-slots in the header's own last frame
        // are wasted, never available to payload+trailer either, see spectrogramLsbCapacityBytesV2's
        // own KDoc), plus every frame from headerFrameCount onward at binsPerFrame bits each. Uses
        // a loud cover specifically to exercise the "no frame skipped" case -- see
        // spectrogramLsbMaxPayloadBytesIsReducedOnACoverWithSilentPassages below for the case
        // where frames genuinely do get skipped.
        val numFrames = 100
        val cover = spectrogramNoiseCover(numFrames, seed = 314)
        val stegoStrength = 3
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB, stegoStrength)

        val binsPerFrame = stegoStrength * SPECTROGRAM_BINS_PER_STRENGTH_LEVEL
        val headerFrames = (HEADER_BYTES * 8 + binsPerFrame - 1) / binsPerFrame
        val totalCapacityBits = HEADER_BYTES * 8 + (numFrames - headerFrames) * binsPerFrame
        val expectedMaxPayload = (totalCapacityBits / 8 - HEADER_TRAILER_OVERHEAD_BYTES).coerceAtLeast(0)

        assertEquals(expectedMaxPayload, carrier.maxPayloadBytes)
    }

    // --- Near-silent-frame skip (v2 format, deferred v6 follow-up) ---

    @Test
    fun spectrogramLsbMaxPayloadBytesIsReducedOnACoverWithSilentPassages() {
        // Half loud frames, half genuinely silent (all-zero) frames -- v2 must report noticeably
        // less capacity than the old dense formula would have claimed for the same frame count,
        // since the silent half is skipped rather than embedded into.
        val numFrames = 200
        val loud = spectrogramNoiseCover(numFrames, seed = 99)
        val cover = ShortArray(loud.size)
        for (frameIndex in 0 until numFrames) {
            if (frameIndex % 2 == 0) {
                val start = frameIndex * SPECTROGRAM_FRAME_SIZE
                loud.copyInto(cover, start, start, start + SPECTROGRAM_FRAME_SIZE)
            }
            // odd frames stay zero-initialized -- genuinely silent
        }
        val stegoStrength = 2
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB, stegoStrength)

        val binsPerFrame = stegoStrength * SPECTROGRAM_BINS_PER_STRENGTH_LEVEL
        val denseFormulaCapacityBytes = (numFrames * binsPerFrame) / 8

        assertTrue(
            "expected v2 capacity (${carrier.maxPayloadBytes}B) to be noticeably less than the " +
                "old dense formula ($denseFormulaCapacityBytes B) once half the frames are silent",
            carrier.maxPayloadBytes < denseFormulaCapacityBytes - HEADER_TRAILER_OVERHEAD_BYTES,
        )
    }

    @Test
    fun spectrogramLsbRoundTripsExactPayloadBytesOnACoverWithSilentPassages() {
        // The actual fix's real-world shape: a cover with genuine quiet gaps (like
        // AudioSampleCover.SPOKEN_WORD's burst/gap timing), round-tripping a real payload through
        // the silence-skip-aware encode/decode walk end to end.
        val numFrames = 300
        val loud = spectrogramNoiseCover(numFrames, seed = 7001)
        val cover = ShortArray(loud.size)
        for (frameIndex in 0 until numFrames) {
            // A silent gap every 5th frame (roughly SPOKEN_WORD's burst/gap shape), skipping the
            // header's own leading frames so this test isn't sensitive to header/body boundary
            // edge cases -- those are covered by the dedicated header-frame tests instead.
            if (frameIndex < 10 || frameIndex % 5 != 0) {
                val start = frameIndex * SPECTROGRAM_FRAME_SIZE
                loud.copyInto(cover, start, start, start + SPECTROGRAM_FRAME_SIZE)
            }
        }
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB, stegoStrength = 2)
        val payload = "nightjar SLSB v2 near-silent-frame-skip round trip".toByteArray(Charsets.US_ASCII)
        assertTrue(
            "test payload (${payload.size}B) exceeds this cover's v2 capacity (${carrier.maxPayloadBytes}B)",
            payload.size <= carrier.maxPayloadBytes,
        )

        val stego = carrier.encode(payload)
        assertEquals(cover.size, stego.size)

        // Every silent gap frame must stay byte-identical -- never touched by encode.
        for (frameIndex in 10 until numFrames step 5) {
            val start = frameIndex * SPECTROGRAM_FRAME_SIZE
            for (i in start until start + SPECTROGRAM_FRAME_SIZE) {
                assertEquals("silent frame $frameIndex must be untouched at sample $i", 0.toShort(), stego[i])
            }
        }

        val result = carrier.decode(stego)
        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
        assertEquals(0, result.correctedByteErrors)
    }

    @Test
    fun spectrogramLsbV1CarrierStillDecodesByteForByteUnchanged() {
        // INV-9: a firefly caught before this fix (v1, dense) must keep decoding exactly as
        // before. There is no public v1-encode path left to call (encode always writes v2 now),
        // so this test builds a v1 frame by hand using the same dense bit-to-(frame,bin) layout
        // and QIM embedding the pre-fix algorithm used, mirroring how this file's own
        // capacity-formula tests already recompute the carrier's private layout inline rather
        // than reaching into its implementation.
        val stegoStrength = 2
        val binsPerFrame = stegoStrength * SPECTROGRAM_BINS_PER_STRENGTH_LEVEL
        val numFrames = 200
        val cover = spectrogramNoiseCover(numFrames, seed = 55555)
        val payload = "pre-fix v1 spectrogram-LSB firefly".toByteArray(Charsets.US_ASCII)

        val frame = buildFrameV1ForTest(payload)
        val stego = cover.copyOf()
        var bitIndex = 0
        var frameIndex = 0
        val totalBits = frame.size * 8
        while (frameIndex < numFrames && bitIndex < totalBits) {
            val start = frameIndex * SPECTROGRAM_FRAME_SIZE
            val re = DoubleArray(SPECTROGRAM_FRAME_SIZE) { i -> cover[start + i].toDouble() }
            val im = DoubleArray(SPECTROGRAM_FRAME_SIZE)
            fft(re, im) // internal, same package -- Fft.kt
            var binSlot = 0
            while (binSlot < binsPerFrame && bitIndex < totalBits) {
                embedBitInBinForTest(re, im, SPECTROGRAM_ELIGIBLE_BIN_START + binSlot, bitAtForTest(frame, bitIndex))
                binSlot++
                bitIndex++
            }
            ifft(re, im) // internal, same package -- Fft.kt
            for (i in 0 until SPECTROGRAM_FRAME_SIZE) {
                stego[start + i] = Math.round(re[i]).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort()
            }
            frameIndex++
        }

        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB, stegoStrength)
        val result = carrier.decode(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
    }

    @Test
    fun spectrogramLsbCapacityScalesWithStegoStrength() {
        // Same cover length, only stegoStrength differs -- higher strength must yield strictly
        // higher capacity (more bins nudged per frame). Needs a loud (non-silent) cover: v2's
        // near-silent-frame skip would otherwise report ~0 capacity at every strength once no
        // frame clears the eligibility threshold, which is a valid but useless case for this
        // specific comparison.
        val cover = spectrogramNoiseCover(numFrames = 100, seed = 2468)
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

    // --- Phase-coding: capacity, round trip, capacity enforcement, non-stego decode ---
    //
    // [PHASE_BASE_BIN] (4), [PHASE_BITS_PER_GROUP] (4), and [PHASE_SEGMENTS_PER_GROUP] (4) below
    // mirror AudioStegoCarrier's documented phase-coding constants exactly, same convention as
    // this file's other technique sections.

    @Test
    fun phaseCodingMaxPayloadBytesMatchesTheCapacityFormula() {
        val numSegments = 240 // 60 whole groups of 4
        val cover = ShortArray(SPECTROGRAM_FRAME_SIZE * numSegments)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_CODING)

        val numGroups = numSegments / PHASE_SEGMENTS_PER_GROUP
        val totalCapacityBytes = (numGroups * PHASE_BITS_PER_GROUP) / 8
        val expectedMaxPayload = (totalCapacityBytes - HEADER_TRAILER_OVERHEAD_BYTES).coerceAtLeast(0)

        assertEquals(expectedMaxPayload, carrier.maxPayloadBytes)
    }

    @Test
    fun phaseCodingTrailingPartialGroupHoldsNoCapacity() {
        // 61 segments = 15 whole groups (60 segments) + 1 leftover segment that can never form a
        // full group -- capacity must come from exactly 15 groups, not be inflated by the leftover.
        val numSegments = 61
        val cover = ShortArray(SPECTROGRAM_FRAME_SIZE * numSegments)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_CODING)

        val fullGroups = numSegments / PHASE_SEGMENTS_PER_GROUP // 15
        val expectedTotalCapacityBytes = (fullGroups * PHASE_BITS_PER_GROUP) / 8
        val expectedMaxPayload = (expectedTotalCapacityBytes - HEADER_TRAILER_OVERHEAD_BYTES).coerceAtLeast(0)

        assertEquals(expectedMaxPayload, carrier.maxPayloadBytes)
    }

    @Test
    fun phaseCodingRoundTripsExactPayloadBytes() {
        val cover = spectrogramNoiseCover(numFrames = 400, seed = 2026)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_CODING)
        val payload = "nightjar phase-coding round trip".toByteArray(Charsets.US_ASCII)
        assertTrue(
            "test payload (${payload.size}B) exceeds this cover's phase-coding capacity " +
                "(${carrier.maxPayloadBytes}B)",
            payload.size <= carrier.maxPayloadBytes,
        )

        val stego = carrier.encode(payload)
        assertEquals(cover.size, stego.size) // mono, cover-length, like SPECTROGRAM_LSB
        val result = carrier.decode(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
        assertEquals(0, result.correctedByteErrors)
    }

    @Test
    fun phaseCodingRoundTripsAnEmptyPayload() {
        val cover = spectrogramNoiseCover(numFrames = 100, seed = 2027)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_CODING)

        val stego = carrier.encode(ByteArray(0))
        val result = carrier.decode(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertEquals(0, (result as DecodeResult.Success).payload.size)
    }

    @Test
    fun phaseCodingRoundTripsAPayloadNearMaxCapacity() {
        val cover = spectrogramNoiseCover(numFrames = 234, seed = 2028) // one bundled-cover-sized clip
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_CODING)
        val payload = ByteArray(carrier.maxPayloadBytes) { (it % 256).toByte() }

        val stego = carrier.encode(payload)
        val result = carrier.decode(stego)

        assertTrue("expected Success but got $result", result is DecodeResult.Success)
        assertTrue(payload.contentEquals((result as DecodeResult.Success).payload))
    }

    @Test
    fun phaseCodingEncodeThrowsWhenPayloadExceedsCapacity() {
        val cover = ShortArray(SPECTROGRAM_FRAME_SIZE * 240)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_CODING)
        val tooBig = ByteArray(carrier.maxPayloadBytes + 1)

        try {
            carrier.encode(tooBig)
            fail("expected encode() to throw IllegalArgumentException for an over-capacity payload")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun phaseCodingDecodeReturnsFailureForANonStegoCoverInsteadOfThrowing() {
        val cover = spectrogramNoiseCover(numFrames = 240, seed = 2029)
        val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_CODING)

        val result = carrier.decode(cover) // never encoded

        assertTrue("expected Failure but got $result", result is DecodeResult.Failure)
    }

    @Test
    fun phaseCodingRoundTripsAtSeveralPayloadSizesIncludingOnesThatLeaveAPartialFinalGroup() {
        // buildFrame's own header+trailer overhead (11 bytes = 88 bits) is not a multiple of
        // PHASE_BITS_PER_GROUP (4), so even a 0-byte payload already leaves some group with
        // fewer than 4 substituted bins -- but a range of payload sizes exercises this more
        // thoroughly, across different offsets into a group.
        val cover = spectrogramNoiseCover(numFrames = 240, seed = 2030)
        for (len in intArrayOf(0, 1, 2, 3, 5, 9)) {
            val carrier = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_CODING)
            val payload = ByteArray(len) { (it % 256).toByte() }
            val stego = carrier.encode(payload)
            val result = carrier.decode(stego)
            assertTrue("len=$len: expected Success but got $result", result is DecodeResult.Success)
            assertTrue(
                "len=$len: payload mismatch",
                payload.contentEquals((result as DecodeResult.Success).payload),
            )
        }
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
    fun canEmbedIsTrueForOrdinaryCoversOnAllFourTechniques() {
        assertTrue(AudioStegoCarrier(noiseCover(SEGMENT_SAMPLES * 800, 1), AudioStegoTechnique.PHASE_INVERSION).canEmbed)
        assertTrue(
            AudioStegoCarrier(
                spectrogramNoiseCover(100, 2),
                AudioStegoTechnique.SPECTROGRAM_LSB,
                stegoStrength = 2,
            ).canEmbed,
        )
        assertTrue(AudioStegoCarrier(mfskNoiseCover(3), AudioStegoTechnique.MFSK).canEmbed)
        assertTrue(AudioStegoCarrier(spectrogramNoiseCover(240, 4), AudioStegoTechnique.PHASE_CODING).canEmbed)
    }

    @Test
    fun phaseCodingCanEmbedIsFalseForATinyCoverBelowFrameOverhead() {
        // 20 segments / 4 per group = 5 groups * 4 bits/group = 20 bits = 2 bytes, under 11.
        val tinyCover = ShortArray(SPECTROGRAM_FRAME_SIZE * 20)
        val carrier = AudioStegoCarrier(tinyCover, AudioStegoTechnique.PHASE_CODING)

        assertEquals(0, carrier.maxPayloadBytes)
        assertTrue("a 20-segment cover (2-byte capacity) should not report canEmbed", !carrier.canEmbed)
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

    // --- MFSK clipping fix (design-v5.md §12.2, revised after rev-2 MAJOR): TONE_AMPLITUDE * up
    // to 8 simultaneous tones measurably exceeded int16 range pre-fix (96/141 saturated samples
    // per stego on the two bundled covers). Uses the app's own real bundled covers
    // (AudioStegoSampleCovers.kt), not synthetic noise, since that's exactly what was measured as
    // clipping, and a real ~34-byte payload -- the same shape the fix was actually measured
    // against. [MFSK_BASE_BIN]/[MFSK_TONE_COUNT]/[MFSK_TONE_AMPLITUDE] below mirror
    // AudioStegoCarrier's documented MFSK tone-synthesis constants exactly, the same way every
    // other technique's tests recompute their own constants inline.
    //
    // The fix applies ONE constant gain to the entire clip (encodeMfsk's `gain`, AudioStegoCarrier
    // .kt), not a per-block one -- a per-block version was tried first and rejected (rev-2 MAJOR):
    // it ducked the cover to a different, sometimes-zero gain every ~21ms, an audible click/
    // dropout pattern worse than the clipping it fixed. A uniform gain also leaves decode()'s
    // detection margin *exactly* unchanged (not just "close enough"): its verdict is a ratio --
    // tone-bin magnitude vs. guard-bin median magnitude, both in dB -- and scaling every sample
    // (so every FFT bin, by linearity) by the same constant cancels out of that ratio entirely.

    @Test
    fun mfskEncodeOnBothBundledCoversNeverSaturatesASample() {
        for (cover in AudioSampleCover.entries) {
            val (stego, result) = encodeAndDecodeMfskProbePayload(cover)

            val saturatedCount = stego.count { it == Short.MAX_VALUE || it == Short.MIN_VALUE }
            assertEquals(
                "expected zero int16-saturated samples encoding MFSK onto ${cover.name}, found $saturatedCount",
                0,
                saturatedCount,
            )

            assertTrue("expected Success but got $result for ${cover.name}", result is DecodeResult.Success)
            // A uniform whole-clip gain cancels out of decode()'s tone-vs-guard-bin-median dB
            // ratio exactly (see this section's header comment) -- unlike the rejected per-block
            // approach (which needed a "< 8, the RS correction bound" allowance because it
            // measurably degraded some blocks' margin), a clean encode should need no correction
            // at all.
            assertEquals(
                "expected zero RS-corrected byte errors on a clean encode of ${cover.name} -- the " +
                    "whole-clip gain should leave decode()'s margin untouched",
                0,
                (result as DecodeResult.Success).correctedByteErrors,
            )
        }
    }

    /**
     * codec rev-2 MAJOR: the per-block gain version of the clipping fix ducked the cover to a
     * different (sometimes zero) gain every ~21ms symbol block, producing block-boundary sample
     * jumps 21-150x the cover's own typical jump -- audible clicks/dropouts, and worse than the
     * clipping it fixed. The whole-clip-gain replacement applies one constant `g` to every
     * sample, cover and tones alike, so there is no seam left at a block boundary to click at:
     * empirically, on both bundled covers, the largest sample-to-sample jump AT a block boundary
     * is smaller than the largest jump found anywhere else in the touched span (tone content
     * near ~19.7kHz, close to the Nyquist bin, already produces large interior jumps on its own —
     * this test only asserts boundaries aren't *additionally* anomalous on top of that).
     */
    @Test
    fun mfskHasNoAnomalousBlockBoundaryJumpOnEitherBundledCover() {
        for (cover in AudioSampleCover.entries) {
            val (stego, _) = encodeAndDecodeMfskProbePayload(cover)
            val neededSamples = MFSK_CODEWORD_BYTES * MFSK_FRAME_SIZE

            var maxBoundaryDelta = 0
            var maxInteriorDelta = 0
            for (i in 1 until neededSamples) {
                val delta = abs(stego[i].toInt() - stego[i - 1].toInt())
                if (i % MFSK_FRAME_SIZE == 0) {
                    if (delta > maxBoundaryDelta) maxBoundaryDelta = delta
                } else {
                    if (delta > maxInteriorDelta) maxInteriorDelta = delta
                }
            }

            assertTrue(
                "expected no block-boundary sample jump larger than the largest interior jump on " +
                    "${cover.name} (maxBoundaryDelta=$maxBoundaryDelta, maxInteriorDelta=$maxInteriorDelta) " +
                    "-- a larger boundary jump would mean the gain isn't actually uniform across blocks",
                maxBoundaryDelta <= maxInteriorDelta,
            )
        }
    }

    /**
     * The mathematical guarantee a single whole-clip gain gives for free: for ANY two adjacent
     * samples, `stego[i] = round(g * (cover[i] + tone[i]))`, so `|Δstego| <= g*(|Δcover| +
     * |Δtone|) + 1` (the `+1` covers up to ±0.5 rounding error on each of the two roundings).
     * [g] is measured empirically from the largest-magnitude sample in the untouched tail (past
     * the MFSK codeword span, where `tone == 0` and `stego[i] == round(g * cover[i])` exactly) --
     * picking the largest magnitude keeps the rounding-derived relative error on that estimate
     * negligible (well under 0.01%). [maxDeltaTone] is a safe analytic upper bound (not the
     * actual per-codeword value, which depends on the real Reed-Solomon-encoded byte values this
     * test doesn't reconstruct): `|Δ(A*sin(θ))| <= 2*A*|sin(halfStep)|` per active tone, summed
     * across all [MFSK_TONE_COUNT] tone bins.
     */
    @Test
    fun mfskWholeClipGainSatisfiesTheNoDiscontinuityBound() {
        for (cover in AudioSampleCover.entries) {
            val pcm = synthesizeSampleCover(cover)
            val (stego, _) = encodeAndDecodeMfskProbePayload(cover, pcm)
            val neededSamples = MFSK_CODEWORD_BYTES * MFSK_FRAME_SIZE

            var maxDeltaCover = 0
            for (i in 1 until pcm.size) {
                val d = abs(pcm[i].toInt() - pcm[i - 1].toInt())
                if (d > maxDeltaCover) maxDeltaCover = d
            }

            var bestTailIndex = -1
            var bestTailAbs = 0
            for (i in neededSamples until pcm.size) {
                val a = abs(pcm[i].toInt())
                if (a > bestTailAbs) {
                    bestTailAbs = a
                    bestTailIndex = i
                }
            }
            assertTrue("expected an untouched tail sample to measure gain from for ${cover.name}", bestTailIndex >= 0)
            val gain = stego[bestTailIndex].toDouble() / pcm[bestTailIndex].toDouble()

            var maxDeltaTone = 0.0
            for (bit in 0 until MFSK_TONE_COUNT) {
                val freqHz = (MFSK_BASE_BIN + bit) * NightjarAcoustics.SAMPLE_RATE_HZ.toDouble() / MFSK_FRAME_SIZE
                val halfStep = PI * freqHz / NightjarAcoustics.SAMPLE_RATE_HZ
                maxDeltaTone += 2.0 * MFSK_TONE_AMPLITUDE * abs(sin(halfStep))
            }

            var maxDeltaStego = 0
            for (i in 1 until neededSamples) {
                val d = abs(stego[i].toInt() - stego[i - 1].toInt())
                if (d > maxDeltaStego) maxDeltaStego = d
            }

            val bound = gain * (maxDeltaCover + maxDeltaTone) + 1
            assertTrue(
                "expected max|Δstego| ($maxDeltaStego) <= gain*(maxDeltaCover+maxDeltaTone)+1 " +
                    "($bound) on ${cover.name} (gain=$gain, maxDeltaCover=$maxDeltaCover, " +
                    "maxDeltaTone=$maxDeltaTone)",
                maxDeltaStego <= bound,
            )
        }
    }

    // --- MFSK click fix (Gate 8 round 4): owner-reported "crackle and pops in the first few
    // seconds" of real-hardware MFSK playback, traced (offline JVM measurement against this
    // class's own encode()/decode(), not a device capture) to encodeMfsk's per-block tone
    // synthesis gating each tone fully on/off at every MFSK_FRAME_SIZE block boundary -- a
    // rectangular edge, not the near-ultrasonic tones simply being audible (mid-symbol audible-
    // band energy is unaffected by the fix below, ~-86 to -88 dBFS throughout) and not residual
    // clipping (already zero, see mfskEncodeOnBothBundledCoversNeverSaturatesASample above).
    // AudioStegoCarrier.kt's mfskToneEnvelope now applies a short raised-cosine (Hann) ramp at a
    // tone's actual on/off transitions; decodeMfsk is untouched.

    /**
     * Measures the < 16 kHz ("audible-band") part of `stego - gain*cover`, low-pass-filtered
     * ([lowPassBelow16kHz]) and pooled across all 63 internal MFSK_FRAME_SIZE block boundaries
     * (±32 samples each), on both bundled covers. Measured before/after this fix, same payload
     * this test uses ([MFSK_CLIP_FIX_TEST_PAYLOAD]):
     *
     * | cover        | before (rectangular gating) | after (this fix) |
     * |--------------|------------------------------|-------------------|
     * | SOFT_SYNTH   | -42.00 dBFS                   | -74.48 dBFS       |
     * | SPOKEN_WORD  | -41.45 dBFS                   | -73.75 dBFS       |
     *
     * A 32+ dB reduction on both covers, comfortably past the -70 dBFS bound
     * [MFSK_CLICK_FIX_TARGET_DBFS] asserts (the same bound `MFSK_RAMP_SAMPLES`'s KDoc in
     * AudioStegoCarrier.kt cites as the tuning target). Mid-symbol audible-band energy (not
     * asserted here -- see that KDoc's sweep instead) stays essentially unchanged by this fix,
     * which is what confirms the reduction is specifically at the boundary, i.e. this technique's
     * clicks, and not a change to the tones' own audibility.
     */
    @Test
    fun mfskClickFixReducesBoundaryLockedAudibleBandResidualBelowTarget() {
        for (cover in AudioSampleCover.entries) {
            val pcm = synthesizeSampleCover(cover)
            val (stego, result) = encodeAndDecodeMfskProbePayload(cover, pcm)
            assertTrue("expected Success but got $result for ${cover.name}", result is DecodeResult.Success)
            val neededSamples = MFSK_CODEWORD_BYTES * MFSK_FRAME_SIZE

            // Same tail-sample gain estimate as mfskWholeClipGainSatisfiesTheNoDiscontinuityBound.
            var bestTailIndex = -1
            var bestTailAbs = 0
            for (i in neededSamples until pcm.size) {
                val a = abs(pcm[i].toInt())
                if (a > bestTailAbs) {
                    bestTailAbs = a
                    bestTailIndex = i
                }
            }
            assertTrue("expected an untouched tail sample to measure gain from for ${cover.name}", bestTailIndex >= 0)
            val gain = stego[bestTailIndex].toDouble() / pcm[bestTailIndex].toDouble()

            val residual = DoubleArray(neededSamples) { i -> stego[i].toDouble() - gain * pcm[i].toDouble() }
            val filtered = lowPassBelow16kHz(residual)

            val boundaryValues = mutableListOf<Double>()
            val filterEdge = FIR_LOWPASS_TAPS / 2
            for (block in 1 until MFSK_CODEWORD_BYTES) {
                val boundary = block * MFSK_FRAME_SIZE
                if (boundary - 32 >= filterEdge && boundary + 32 < filtered.size - filterEdge) {
                    for (i in (boundary - 32)..(boundary + 32)) boundaryValues.add(filtered[i])
                }
            }
            assertTrue("expected boundary windows to be collected for ${cover.name}", boundaryValues.isNotEmpty())
            val meanSquare = boundaryValues.sumOf { it * it } / boundaryValues.size
            val boundaryRmsDbfs = 20.0 * log10(sqrt(meanSquare) / 32768.0 + 1e-300)

            assertTrue(
                "expected boundary-locked audible-band RMS on ${cover.name} to be below " +
                    "$MFSK_CLICK_FIX_TARGET_DBFS dBFS (measured ${"%.2f".format(boundaryRmsDbfs)} dBFS) -- " +
                    "see this test's KDoc for the before/after numbers this fix was tuned against",
                boundaryRmsDbfs < MFSK_CLICK_FIX_TARGET_DBFS,
            )
        }
    }

    /**
     * Windowed-sinc (Hamming) FIR low-pass filter, [FIR_LOWPASS_TAPS] taps, 16 kHz cutoff at
     * [NightjarAcoustics.SAMPLE_RATE_HZ] -- used only to isolate the audible part of a residual
     * signal for [mfskClickFixReducesBoundaryLockedAudibleBandResidualBelowTarget]'s measurement;
     * MFSK's tones themselves live at [MFSK_BASE_BIN]..+7 (~19.7-20 kHz), comfortably above this
     * cutoff, so what survives filtering is spectral splatter, not the tones' own energy.
     */
    private fun lowPassBelow16kHz(x: DoubleArray): DoubleArray {
        val cutoffHz = 16000.0
        val sampleRateHz = NightjarAcoustics.SAMPLE_RATE_HZ.toDouble()
        val fc = cutoffHz / sampleRateHz
        val m = FIR_LOWPASS_TAPS - 1
        val taps = DoubleArray(FIR_LOWPASS_TAPS) { n ->
            val k = n - m / 2.0
            val sincValue = if (k == 0.0) 2 * fc else sin(2 * PI * fc * k) / (PI * k)
            val window = 0.54 - 0.46 * cos(2 * PI * n / m) // Hamming
            sincValue * window
        }
        val tapSum = taps.sum()
        for (n in taps.indices) taps[n] /= tapSum

        val half = taps.size / 2
        return DoubleArray(x.size) { i ->
            var acc = 0.0
            for (j in taps.indices) {
                val idx = i + j - half
                if (idx in x.indices) acc += taps[j] * x[idx]
            }
            acc
        }
    }

    /** Shared MFSK clipping-fix test fixture: encodes [MFSK_CLIP_FIX_TEST_PAYLOAD] onto [cover]
     *  (or the already-synthesized [pcm], if the caller needs it too) and decodes the result --
     *  every clipping-fix test above needs this same encode/decode pair. */
    private fun encodeAndDecodeMfskProbePayload(
        cover: AudioSampleCover,
        pcm: PcmAudio = synthesizeSampleCover(cover),
    ): Pair<PcmAudio, DecodeResult> {
        val carrier = AudioStegoCarrier(pcm, AudioStegoTechnique.MFSK)
        assertTrue(
            "test payload (${MFSK_CLIP_FIX_TEST_PAYLOAD.size}B) exceeds MFSK's fixed capacity " +
                "(${carrier.maxPayloadBytes}B)",
            MFSK_CLIP_FIX_TEST_PAYLOAD.size <= carrier.maxPayloadBytes,
        )
        val stego = carrier.encode(MFSK_CLIP_FIX_TEST_PAYLOAD)
        return stego to carrier.decode(stego)
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

    // --- v1-frame construction (spectrogramLsbV1CarrierStillDecodesByteForByteUnchanged only) ---
    //
    // Mirrors AudioStegoCarrier's private buildFrame/embedBitInBin/bitAt/crc8 exactly, at
    // VERSION=0x01 specifically (SPECTROGRAM_LSB_VERSION_2 has no public encode path to
    // reconstruct from since AudioStegoCarrier.encode always writes v2 now) -- same "recompute
    // the carrier's private layout inline" convention this file's capacity-formula tests already
    // establish, just applied to frame assembly instead of pure capacity arithmetic.

    private fun buildFrameV1ForTest(payload: ByteArray): ByteArray {
        val header = ByteArray(HEADER_BYTES)
        header[0] = SPECTROGRAM_MAGIC.toByte()
        header[1] = SPECTROGRAM_VERSION_1.toByte()
        header[2] = (payload.size ushr 24).toByte()
        header[3] = (payload.size ushr 16).toByte()
        header[4] = (payload.size ushr 8).toByte()
        header[5] = payload.size.toByte()
        header[6] = crc8ForTest(header, 0, 6).toByte()

        val crc32 = java.util.zip.CRC32().apply { update(payload) }.value
        val trailer = byteArrayOf(
            (crc32 ushr 24).toByte(),
            (crc32 ushr 16).toByte(),
            (crc32 ushr 8).toByte(),
            crc32.toByte(),
        )
        return header + payload + trailer
    }

    private fun crc8ForTest(bytes: ByteArray, offset: Int, length: Int): Int {
        var crc = 0
        for (i in offset until offset + length) {
            crc = crc xor (bytes[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor SPECTROGRAM_HEADER_CRC8_POLY) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc
    }

    private fun bitAtForTest(bytes: ByteArray, bitIndex: Int): Int {
        val byteIndex = bitIndex / 8
        val bitInByte = 7 - (bitIndex % 8)
        return (bytes[byteIndex].toInt() ushr bitInByte) and 1
    }

    private fun embedBitInBinForTest(re: DoubleArray, im: DoubleArray, bin: Int, bit: Int) {
        val mirror = re.size - bin
        val xRe = re[bin]
        val xIm = im[bin]
        val magnitude = sqrt(xRe * xRe + xIm * xIm)
        val logMagnitude = ln(maxOf(magnitude, SPECTROGRAM_LOG_MAGNITUDE_FLOOR))
        var idx = Math.round(logMagnitude / SPECTROGRAM_QUANTIZATION_STEP)
        if (Math.floorMod(idx, 2L).toInt() != bit) idx += 1
        val newMagnitude = kotlin.math.exp(idx * SPECTROGRAM_QUANTIZATION_STEP)
        val newRe: Double
        val newIm: Double
        if (magnitude > 0.0) {
            newRe = (xRe / magnitude) * newMagnitude
            newIm = (xIm / magnitude) * newMagnitude
        } else {
            newRe = newMagnitude
            newIm = 0.0
        }
        re[bin] = newRe
        im[bin] = newIm
        re[mirror] = newRe
        im[mirror] = -newIm
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

        /** Mirrors AudioStegoCarrier's documented phase-coding first dedicated bin. */
        private const val PHASE_BASE_BIN = 4

        /** Mirrors AudioStegoCarrier's documented phase-coding dedicated-bin count per group. */
        private const val PHASE_BITS_PER_GROUP = 4

        /** Mirrors AudioStegoCarrier's documented phase-coding segments-per-group. */
        private const val PHASE_SEGMENTS_PER_GROUP = 4

        /** Mirrors AudioStegoCarrier's documented spectrogram-LSB first eligible bin (bin 32). */
        private const val SPECTROGRAM_ELIGIBLE_BIN_START = 32

        /** Mirrors AudioStegoCarrier's documented shared frame magic byte ('N', 0x4E). */
        private const val SPECTROGRAM_MAGIC = 0x4E

        /** Mirrors AudioStegoCarrier's documented VERSION (0x01) -- pre-v2 spectrogram-LSB. */
        private const val SPECTROGRAM_VERSION_1 = 0x01

        /** Mirrors AudioStegoCarrier's documented header_crc polynomial (0x07). */
        private const val SPECTROGRAM_HEADER_CRC8_POLY = 0x07

        /** Mirrors AudioStegoCarrier's documented log-magnitude QIM quantization step. */
        private const val SPECTROGRAM_QUANTIZATION_STEP = 0.12

        /** Mirrors AudioStegoCarrier's documented log-magnitude QIM floor. */
        private const val SPECTROGRAM_LOG_MAGNITUDE_FLOOR = 1000.0

        /** Mirrors AudioStegoCarrier's documented MFSK symbol-block size (same 1024 as spectrogram-LSB). */
        private const val MFSK_FRAME_SIZE = 1024

        /** Mirrors AudioStegoCarrier's documented MFSK Reed-Solomon data-block size. */
        private const val MFSK_RS_DATA_BYTES = 48

        /** Mirrors AudioStegoCarrier's documented MFSK Reed-Solomon parity-byte count. */
        private const val MFSK_RS_PARITY_BYTES = 16

        /** Mirrors AudioStegoCarrier's documented MFSK codeword size (data + parity bytes = symbol blocks). */
        private const val MFSK_CODEWORD_BYTES = MFSK_RS_DATA_BYTES + MFSK_RS_PARITY_BYTES

        /** Mirrors AudioStegoCarrier's documented first MFSK tone bin. */
        private const val MFSK_BASE_BIN = 420

        /** Mirrors AudioStegoCarrier's documented MFSK simultaneous-tone-channel count. */
        private const val MFSK_TONE_COUNT = 8

        /** Mirrors AudioStegoCarrier's documented MFSK per-tone amplitude ceiling. */
        private const val MFSK_TONE_AMPLITUDE = 6000.0

        /** 34 bytes, comfortably under MFSK's fixed ~37-byte capacity -- the same payload shape
         *  (a real ASCII string, not synthetic noise) the clipping fix was measured against. */
        private val MFSK_CLIP_FIX_TEST_PAYLOAD = "nightjar MFSK gain probe payload!!".toByteArray(Charsets.US_ASCII)

        /**
         * Taps for [lowPassBelow16kHz]'s FIR filter. 129 gives a transition band narrow enough
         * (relative to the ~3.7 kHz gap between the 16 kHz cutoff and [MFSK_BASE_BIN]'s ~19.7 kHz)
         * to cleanly separate audible-band splatter from the tones' own near-ultrasonic energy.
         */
        private const val FIR_LOWPASS_TAPS = 129

        /**
         * Bound [mfskClickFixReducesBoundaryLockedAudibleBandResidualBelowTarget] asserts:
         * boundary-locked audible-band RMS must land below this. Matches the -70 dBFS target
         * `MFSK_RAMP_SAMPLES`'s KDoc in AudioStegoCarrier.kt cites as the ramp-length tuning
         * target; the actual measured values (see that test's KDoc) clear it by several dB on
         * both bundled covers.
         */
        private const val MFSK_CLICK_FIX_TARGET_DBFS = -70.0
    }
}
