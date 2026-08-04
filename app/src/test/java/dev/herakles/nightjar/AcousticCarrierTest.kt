package dev.herakles.nightjar

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural verification of [AcousticCarrier.encode] (task #5), plus the [AcousticCarrier.decode]
 * (task #6) round-trip and failure-path tests. The structural tests independently recompute the
 * expected wire-format shape from [NightjarAcoustics] constants (frame count, START/END marker
 * placement and frequencies) and check the encoder's output against that, plus the
 * `maxPayloadBytes` boundary contract from CovertModule.kt. The round-trip tests feed [encode]'s
 * exact output straight into [decode] with no speaker/mic in the loop (CovertModule.kt's
 * INV-3 loopback self-test).
 */
class AcousticCarrierTest {

    private val protocol = NightjarAcoustics.Protocol.AUDIBLE
    private val symbolRate = NightjarAcoustics.SymbolRate.NORMAL
    private val markerSamples = NightjarAcoustics.MARKER_FRAMES * NightjarAcoustics.FRAME_SAMPLES

    // ---------------------------------------------------------------------
    // maxPayloadBytes boundary (CovertModule.kt contract)
    // ---------------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun `encode throws when payload exceeds maxPayloadBytes`() {
        val carrier = AcousticCarrier(protocol, symbolRate)
        carrier.encode(ByteArray(carrier.maxPayloadBytes + 1))
    }

    @Test
    fun `encode accepts a payload exactly at maxPayloadBytes`() {
        val carrier = AcousticCarrier(protocol, symbolRate)
        val pcm = carrier.encode(ByteArray(carrier.maxPayloadBytes))
        assertEquals(expectedTotalSamples(carrier.maxPayloadBytes), pcm.size)
    }

    @Test
    fun `encode accepts an empty payload`() {
        val carrier = AcousticCarrier(protocol, symbolRate)
        val pcm = carrier.encode(ByteArray(0))
        assertEquals(expectedTotalSamples(0), pcm.size)
    }

    // ---------------------------------------------------------------------
    // Known short benign text payload — frame count + marker structure
    // ---------------------------------------------------------------------

    @Test
    fun `encode of known short text payload has correct frame count, START and END markers`() {
        val carrier = AcousticCarrier(protocol, symbolRate)
        val payload = "hello nightjar".toByteArray(Charsets.US_ASCII) // 14 benign bytes

        val pcm = carrier.encode(payload)

        val expectedSamples = expectedTotalSamples(payload.size)
        assertEquals("total sample count", expectedSamples, pcm.size)
        assertEquals("PCM length must be a whole number of frames", 0, pcm.size % NightjarAcoustics.FRAME_SAMPLES)
        assertTrue("payload must produce more than just the two markers", pcm.size > 2 * markerSamples)

        // START marker: protocol's two edge tones (baseBin, topBin), held at the head.
        val baseFreq = NightjarAcoustics.binFrequencyHz(protocol.baseBin)
        val topFreq = NightjarAcoustics.binFrequencyHz(protocol.topBin)
        val head = pcm.copyOfRange(0, markerSamples)
        val headReference = referenceSinSum(listOf(baseFreq, topFreq), markerSamples, startSampleIndex = 0L)
        val startCorrelation = pearsonCorrelation(head, headReference)
        assertTrue("START marker should correlate strongly with edge tones (got $startCorrelation)", startCorrelation > 0.999)

        // END marker: protocol's two center-adjacent tones (baseBin+31, baseBin+32), held at the tail.
        val endFreqA = NightjarAcoustics.binFrequencyHz(protocol.baseBin + 31)
        val endFreqB = NightjarAcoustics.binFrequencyHz(protocol.baseBin + 32)
        val tailStart = pcm.size - markerSamples
        val tail = pcm.copyOfRange(tailStart, pcm.size)
        val tailReference = referenceSinSum(listOf(endFreqA, endFreqB), markerSamples, startSampleIndex = tailStart.toLong())
        val endCorrelation = pearsonCorrelation(tail, tailReference)
        assertTrue("END marker should correlate strongly with center-adjacent tones (got $endCorrelation)", endCorrelation > 0.999)

        // Sanity: the tail is the END marker, not a repeat of the START marker's frequencies.
        val tailVsStartFreqs = pearsonCorrelation(
            tail,
            referenceSinSum(listOf(baseFreq, topFreq), markerSamples, startSampleIndex = tailStart.toLong()),
        )
        assertTrue(
            "tail should NOT correlate with START-marker frequencies (got $tailVsStartFreqs)",
            tailVsStartFreqs < 0.9,
        )
    }

    // ---------------------------------------------------------------------
    // Round trip (task #6): encode(payload) -> decode(carrier) == payload
    // ---------------------------------------------------------------------

    @Test
    fun `round trip recovers an empty payload`() {
        val carrier = AcousticCarrier(protocol, symbolRate)
        val original = ByteArray(0)
        val pcm = carrier.encode(original)

        val success = assertSuccess(carrier.decode(pcm))
        assertTrue("recovered payload must equal original (empty)", success.payload.contentEquals(original))
        assertEquals(0, success.correctedByteErrors)
    }

    @Test
    fun `round trip recovers a short benign text payload`() {
        val carrier = AcousticCarrier(protocol, symbolRate)
        val original = "hello nightjar".toByteArray(Charsets.US_ASCII)
        val pcm = carrier.encode(original)

        // Fresh instance, same config, per the task's "same instance or a fresh one" contract.
        val success = assertSuccess(AcousticCarrier(protocol, symbolRate).decode(pcm))
        assertTrue("recovered payload must equal original text", success.payload.contentEquals(original))
        assertEquals(0, success.correctedByteErrors)
    }

    @Test
    fun `round trip recovers a payload at exactly maxPayloadBytes`() {
        val carrier = AcousticCarrier(protocol, symbolRate)
        val original = ByteArray(carrier.maxPayloadBytes) { (it * 31 + 7).toByte() } // deterministic, non-trivial bytes
        val pcm = carrier.encode(original)

        val success = assertSuccess(carrier.decode(pcm))
        assertTrue("recovered payload must equal original at maxPayloadBytes", success.payload.contentEquals(original))
        assertEquals(0, success.correctedByteErrors)
    }

    @Test
    fun `round trip works for the NEAR_ULTRASONIC protocol and FAST symbol rate too`() {
        val carrier = AcousticCarrier(NightjarAcoustics.Protocol.NEAR_ULTRASONIC, NightjarAcoustics.SymbolRate.FAST)
        val original = "ultrasonic fast path".toByteArray(Charsets.US_ASCII)
        val pcm = carrier.encode(original)

        val success = assertSuccess(carrier.decode(pcm))
        assertTrue(success.payload.contentEquals(original))
    }

    // ---------------------------------------------------------------------
    // Round trip with leading capture offset (task #25): a real AudioRecord capture
    // (AcousticModemController) has arbitrary leading silence/noise before the sender's
    // transmission actually starts, unlike these frame-0-aligned pure-codec round trips.
    // decode() must locate the START marker via a bounded sliding-window search instead of
    // assuming it's at frame 0.
    // ---------------------------------------------------------------------

    @Test
    fun `round trip recovers payload when carrier is prepended with silence, offset A`() {
        val carrier = AcousticCarrier(protocol, symbolRate)
        val original = "leading silence offset A".toByteArray(Charsets.US_ASCII)
        val pcm = carrier.encode(original)
        val leadingSilence = PcmAudio(NightjarAcoustics.FRAME_SAMPLES * 30) // 30 frames (~0.64s)
        val captured = leadingSilence + pcm

        val success = assertSuccess(carrier.decode(captured))
        assertTrue("recovered payload must equal original despite leading silence", success.payload.contentEquals(original))
        assertEquals(0, success.correctedByteErrors)
    }

    @Test
    fun `round trip recovers payload when carrier is prepended with low-level noise, offset B`() {
        val carrier = AcousticCarrier(protocol, symbolRate)
        val original = "leading noise offset B".toByteArray(Charsets.US_ASCII)
        val pcm = carrier.encode(original)
        val random = Random(99)
        // 120 frames (~2.56s) of quiet room-noise-scale audio — a different, larger offset than A.
        val leadingNoise = PcmAudio(NightjarAcoustics.FRAME_SAMPLES * 120) { random.nextInt(-500, 500).toShort() }
        val captured = leadingNoise + pcm

        val success = assertSuccess(carrier.decode(captured))
        assertTrue("recovered payload must equal original despite leading noise", success.payload.contentEquals(original))
        assertEquals(0, success.correctedByteErrors)
    }

    @Test
    fun `round trip recovers payload with a combined silence-then-noise leading offset`() {
        val carrier = AcousticCarrier(protocol, symbolRate)
        val original = "combined offset".toByteArray(Charsets.US_ASCII)
        val pcm = carrier.encode(original)
        val random = Random(7)
        val leadingSilence = PcmAudio(NightjarAcoustics.FRAME_SAMPLES * 10)
        val leadingNoise = PcmAudio(NightjarAcoustics.FRAME_SAMPLES * 15) { random.nextInt(-800, 800).toShort() }
        val captured = leadingSilence + leadingNoise + pcm

        val success = assertSuccess(carrier.decode(captured))
        assertTrue("recovered payload must equal original despite a mixed silence+noise offset", success.payload.contentEquals(original))
    }

    @Test
    fun `decode returns NO_PAYLOAD_FOUND when leading silence exceeds the bounded search range`() {
        val carrier = AcousticCarrier(protocol, symbolRate)
        val pcm = carrier.encode("too far to find".toByteArray(Charsets.US_ASCII))
        // Comfortably past AcousticCarrier's bounded START search range (a few seconds' worth of
        // frames) — the search must stay bounded (never scan the whole buffer) and fail cleanly.
        val leadingSilence = PcmAudio(NightjarAcoustics.FRAME_SAMPLES * 500)
        val captured = leadingSilence + pcm

        val failure = assertFailure(carrier.decode(captured))
        assertEquals(DecodeFailure.NO_PAYLOAD_FOUND, failure.reason)
    }

    // ---------------------------------------------------------------------
    // Round trip with a NON-frame-aligned leading offset (task #29). The offset-A/B/combined tests
    // above (task #25) all happen to use leading lengths that are themselves exact multiples of
    // NightjarAcoustics.FRAME_SAMPLES (30, 120, and 10+15 frames) — so they never exercised the
    // actual field bug: a real AudioRecord capture's transmission-start sample offset is
    // essentially random relative to the FRAME_SAMPLES=1024 grid, not a clean multiple of it.
    //
    // Because encode()'s output length and decode()'s carrier-length precondition are both always
    // an exact multiple of FRAME_SAMPLES, a `leading + pcm` construction with a non-1024-multiple
    // leading length can never itself satisfy that precondition (1024 divides pcm.size exactly, so
    // it must also divide the leading length for their sum to). [withNonFrameAlignedOffset] resolves
    // this the same way a real capture would: pad the *tail* with a little extra silence — modeling
    // a mic that keeps recording briefly after the transmission ends — which is exactly the case
    // task #29's header-declared-length END-marker location (decode()'s KDoc step 3) is for.
    // ---------------------------------------------------------------------

    /**
     * [leadingOffsetSamples] samples of [leadingContent] immediately followed by [pcm], then padded
     * with trailing silence up to the next whole [NightjarAcoustics.FRAME_SAMPLES] — satisfying
     * decode()'s whole-frame-count precondition without forcing [leadingOffsetSamples] itself to be
     * frame-aligned.
     */
    private fun withNonFrameAlignedOffset(
        leadingOffsetSamples: Int,
        pcm: PcmAudio,
        leadingContent: (Int) -> Short,
    ): PcmAudio {
        val leading = PcmAudio(leadingOffsetSamples, leadingContent)
        val rawLength = leading.size + pcm.size
        val trailingPad =
            (NightjarAcoustics.FRAME_SAMPLES - rawLength % NightjarAcoustics.FRAME_SAMPLES) % NightjarAcoustics.FRAME_SAMPLES
        return leading + pcm + PcmAudio(trailingPad)
    }

    @Test
    fun `round trip recovers payload with a non-frame-aligned leading silence offset of 500 samples`() {
        require(500 % NightjarAcoustics.FRAME_SAMPLES != 0) { "test fixture offset must not be frame-aligned" }
        val carrier = AcousticCarrier(protocol, symbolRate)
        val original = "non-frame-aligned offset 500".toByteArray(Charsets.US_ASCII)
        val pcm = carrier.encode(original)
        val captured = withNonFrameAlignedOffset(500, pcm) { 0 }

        val success = assertSuccess(carrier.decode(captured))
        assertTrue(
            "recovered payload must equal original despite a non-frame-aligned offset of 500 samples",
            success.payload.contentEquals(original),
        )
    }

    @Test
    fun `round trip recovers payload with a non-frame-aligned leading noise offset of 777 samples`() {
        require(777 % NightjarAcoustics.FRAME_SAMPLES != 0) { "test fixture offset must not be frame-aligned" }
        val carrier = AcousticCarrier(protocol, symbolRate)
        val original = "non-frame-aligned offset 777".toByteArray(Charsets.US_ASCII)
        val pcm = carrier.encode(original)
        val random = Random(2029)
        val captured = withNonFrameAlignedOffset(777, pcm) { random.nextInt(-500, 500).toShort() }

        val success = assertSuccess(carrier.decode(captured))
        assertTrue(
            "recovered payload must equal original despite a non-frame-aligned offset of 777 samples",
            success.payload.contentEquals(original),
        )
    }

    @Test
    fun `round trip recovers payload with a non-frame-aligned leading offset of 1500 samples`() {
        require(1500 % NightjarAcoustics.FRAME_SAMPLES != 0) { "test fixture offset must not be frame-aligned" }
        val carrier = AcousticCarrier(protocol, symbolRate)
        val original = "non-frame-aligned offset 1500".toByteArray(Charsets.US_ASCII)
        val pcm = carrier.encode(original)
        val random = Random(4104)
        val captured = withNonFrameAlignedOffset(1500, pcm) { random.nextInt(-800, 800).toShort() }

        val success = assertSuccess(carrier.decode(captured))
        assertTrue(
            "recovered payload must equal original despite a non-frame-aligned offset of 1500 samples",
            success.payload.contentEquals(original),
        )
    }

    // ---------------------------------------------------------------------
    // decode() failure paths: never a crash, never a false Success
    // ---------------------------------------------------------------------

    @Test
    fun `decode returns Failure NO_PAYLOAD_FOUND on silence`() {
        val carrier = AcousticCarrier(protocol, symbolRate)
        val silence = PcmAudio(NightjarAcoustics.FRAME_SAMPLES * 40) // 40 frames of digital silence

        val failure = assertFailure(carrier.decode(silence))
        assertEquals(DecodeFailure.NO_PAYLOAD_FOUND, failure.reason)
    }

    @Test
    fun `decode returns Failure NO_PAYLOAD_FOUND on white noise`() {
        val carrier = AcousticCarrier(protocol, symbolRate)
        val random = Random(42)
        val noise = PcmAudio(NightjarAcoustics.FRAME_SAMPLES * 40) { random.nextInt(-4000, 4000).toShort() }

        val failure = assertFailure(carrier.decode(noise))
        assertEquals(DecodeFailure.NO_PAYLOAD_FOUND, failure.reason)
    }

    @Test
    fun `decode returns Failure on an empty carrier without crashing`() {
        val carrier = AcousticCarrier(protocol, symbolRate)

        val result = carrier.decode(PcmAudio(0))

        assertTrue("empty carrier must never be a Success", result is DecodeResult.Failure)
        assertNull("Success branch must be unreachable for an empty carrier", result as? DecodeResult.Success)
    }

    // ---------------------------------------------------------------------
    // decode() failure paths: truncated / corrupted captures (task #14)
    // ---------------------------------------------------------------------

    @Test
    fun `decode returns Failure when carrier is truncated partway through the payload frames`() {
        val carrier = AcousticCarrier(protocol, symbolRate)
        // A payload sizable enough to span multiple data frames well past the START marker.
        val original = ByteArray(120) { (it * 3 + 5).toByte() }
        val pcm = carrier.encode(original)

        // Cut off roughly halfway through the transmission, frame-aligned so the "whole number
        // of frames" precondition still holds — this exercises the "data window inconsistent /
        // END marker never found" paths rather than the earlier frame-count guard.
        val halfFrames = (pcm.size / NightjarAcoustics.FRAME_SAMPLES) / 2
        val truncatedLength = halfFrames * NightjarAcoustics.FRAME_SAMPLES
        require(truncatedLength > markerSamples) { "test fixture too short to exercise mid-payload truncation" }
        val truncated = pcm.copyOfRange(0, truncatedLength)

        val result = carrier.decode(truncated)

        assertTrue(
            "truncated mid-payload capture must never crash or return Success, got $result",
            result is DecodeResult.Failure,
        )
    }

    @Test
    fun `decode returns Failure when carrier is truncated mid-marker`() {
        val carrier = AcousticCarrier(protocol, symbolRate)
        val pcm = carrier.encode("truncated mid marker".toByteArray(Charsets.US_ASCII))

        // Cut off partway through the START marker itself (half of MARKER_FRAMES worth of
        // samples) — not even one complete marker survives in the capture.
        val cutPoint = markerSamples / 2
        val truncated = pcm.copyOfRange(0, cutPoint)

        val result = carrier.decode(truncated)

        assertTrue(
            "capture truncated mid-marker must never crash or return Success, got $result",
            result is DecodeResult.Failure,
        )
    }

    // ---------------------------------------------------------------------
    // decode() failure/success under payload corruption beyond/within the RS(48,32) ceiling
    // (task #14). Corruption is injected by silencing the single analysis frame decode() reads
    // for a chosen symbol (see decode()'s KDoc step 3: exactly one FFT per symbol, at frame
    // `dataFrameStart + symbolIndex * framesPerTx`). With every candidate bin reading zero
    // magnitude, `strongestNibble`'s strict `>` comparison deterministically resolves every tie
    // to nibble 0 — so a silenced symbol always demodulates to byte 0x00, a reliable, fully
    // deterministic corruption primitive that needs no knowledge of the original coded byte
    // value (RS parity bytes are opaque from the test's side).
    // ---------------------------------------------------------------------

    /**
     * Silences the exact PCM analysis frame [decode] reads for data symbol [symbolIndex] of a
     * carrier produced by a frame-0-aligned `encode()` call (no leading offset, so `startFrame ==
     * 0` and `dataFrameStart == MARKER_FRAMES`), forcing that symbol to demodulate to 0x00 bytes.
     */
    private fun silenceSymbol(pcm: PcmAudio, symbolIndex: Int): PcmAudio {
        val dataFrameStart = NightjarAcoustics.MARKER_FRAMES
        val frameIndex = dataFrameStart + symbolIndex * symbolRate.framesPerTx
        val start = frameIndex * NightjarAcoustics.FRAME_SAMPLES
        val end = start + NightjarAcoustics.FRAME_SAMPLES
        for (i in start until end) pcm[i] = 0
        return pcm
    }

    @Test
    fun `decode fails closed when payload corruption exceeds the RS(48,32) correction ceiling`() {
        val carrier = AcousticCarrier(protocol, symbolRate)
        // 28-byte payload + 4-byte CRC32 trailer == exactly 32 protected bytes == one full,
        // unshortened RS(48,32) block (architecture.md §4), so every corrupted byte below lands
        // in that single block instead of spreading harmlessly across two.
        val original = ByteArray(28) { (it * 5 + 11).toByte() }
        val pcm = carrier.encode(original)

        // Header coded bytes occupy codedBytes[0,14); the sole payload block occupies
        // codedBytes[14,62) (32 data + 16 parity). At 3 bytes/symbol (AUDIBLE), symbols 5..14
        // cover bytes[15,45) — comfortably inside the payload block, clear of the header/payload
        // seam at symbol 4 (bytes[12,15)). Silencing 10 symbols corrupts up to 30 of the block's
        // 48 bytes, far beyond its floor(16/2)=8-byte correction ceiling.
        for (symbolIndex in 5..14) {
            silenceSymbol(pcm, symbolIndex)
        }

        val result = carrier.decode(pcm)

        assertTrue("over-ceiling corruption must never return Success, got $result", result is DecodeResult.Failure)
        val failure = result as DecodeResult.Failure
        assertTrue(
            "expected UNRECOVERABLE_FEC or INTEGRITY_MISMATCH, got ${failure.reason}",
            failure.reason == DecodeFailure.UNRECOVERABLE_FEC || failure.reason == DecodeFailure.INTEGRITY_MISMATCH,
        )
    }

    @Test
    fun `decode corrects payload corruption within the RS(48,32) correction ceiling and reports it`() {
        val carrier = AcousticCarrier(protocol, symbolRate)
        val original = ByteArray(28) { (it * 5 + 11).toByte() }
        val pcm = carrier.encode(original)

        // Only 2 symbols (up to 6 bytes) inside the same single payload block — comfortably under
        // its 8-byte correction ceiling.
        for (symbolIndex in 5..6) {
            silenceSymbol(pcm, symbolIndex)
        }

        val result = carrier.decode(pcm)

        val success = assertSuccess(result)
        assertTrue("recovered payload must equal the original despite corruption", success.payload.contentEquals(original))
        assertTrue(
            "corrected byte errors should be reported (got ${success.correctedByteErrors})",
            success.correctedByteErrors > 0,
        )
    }

    // ---------------------------------------------------------------------
    // Helpers: type-safe DecodeResult assertions (avoid an unsafe `as` on a
    // JUnit fail() call, which returns Unit from Kotlin's perspective, not Nothing)
    // ---------------------------------------------------------------------

    private fun assertSuccess(result: DecodeResult): DecodeResult.Success {
        if (result !is DecodeResult.Success) {
            throw AssertionError("expected DecodeResult.Success, got $result")
        }
        return result
    }

    private fun assertFailure(result: DecodeResult): DecodeResult.Failure {
        if (result !is DecodeResult.Failure) {
            throw AssertionError("expected DecodeResult.Failure, got $result")
        }
        return result
    }

    // ---------------------------------------------------------------------
    // Helpers: independent recomputation of the expected wire shape
    // ---------------------------------------------------------------------

    /**
     * Recomputes the expected total PCM sample count from [NightjarAcoustics] constants,
     * independent of [AcousticCarrier]'s internals: header RS(14,6) + payload+CRC32 trailer
     * split into RS(48,32) blocks (final block shortened) + start/end markers.
     */
    private fun expectedTotalSamples(payloadSize: Int): Int {
        val headerCodedBytes = NightjarAcoustics.HEADER_BYTES + NightjarAcoustics.RS_HEADER_PARITY_BYTES
        val protectedPayloadBytes = payloadSize + NightjarAcoustics.TRAILER_BYTES
        val numBlocks = ceilDiv(protectedPayloadBytes, NightjarAcoustics.RS_PAYLOAD_DATA_BYTES)
        val payloadCodedBytes = protectedPayloadBytes + numBlocks * NightjarAcoustics.RS_PAYLOAD_PARITY_BYTES
        val totalCodedBytes = headerCodedBytes + payloadCodedBytes

        val numSymbols = ceilDiv(totalCodedBytes, protocol.bytesPerSymbol)
        val dataFrames = numSymbols * symbolRate.framesPerTx
        val totalFrames = 2 * NightjarAcoustics.MARKER_FRAMES + dataFrames
        return totalFrames * NightjarAcoustics.FRAME_SAMPLES
    }

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

    private fun referenceSinSum(frequenciesHz: List<Double>, numSamples: Int, startSampleIndex: Long): DoubleArray {
        val out = DoubleArray(numSamples)
        for (n in 0 until numSamples) {
            val t = (startSampleIndex + n).toDouble() / NightjarAcoustics.SAMPLE_RATE_HZ
            var sum = 0.0
            for (freq in frequenciesHz) {
                sum += sin(2.0 * PI * freq * t)
            }
            out[n] = sum
        }
        return out
    }

    private fun pearsonCorrelation(actual: ShortArray, reference: DoubleArray): Double {
        require(actual.size == reference.size)
        val n = actual.size
        var sumA = 0.0
        var sumB = 0.0
        for (i in 0 until n) {
            sumA += actual[i]
            sumB += reference[i]
        }
        val meanA = sumA / n
        val meanB = sumB / n
        var num = 0.0
        var denA = 0.0
        var denB = 0.0
        for (i in 0 until n) {
            val da = actual[i] - meanA
            val db = reference[i] - meanB
            num += da * db
            denA += da * da
            denB += db * db
        }
        return num / sqrt(denA * denB)
    }
}
