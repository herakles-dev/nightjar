package dev.herakles.nightjar

import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Module 3 acoustic FSK modem — Tx path (task #5).
 *
 * Implements the encoder half of `CovertCarrier<PcmAudio>` exactly per architecture.md
 * §"Acoustic Protocol" (§1-§6). Every DSP constant (sample rate, frame/FFT size, tone
 * bins, symbol rate, FEC block sizes, header/CRC layout, marker frames) is read from
 * [NightjarAcoustics] — nothing here is a re-derived literal.
 *
 * `decode()` (task #6) reverses that exact assembly: locate the START/END markers, FFT-demodulate
 * each symbol window back into coded bytes, RS-decode the header and payload blocks (correcting up
 * to the documented byte-error count per block via [ReedSolomon.decode]), then verify header CRC-8
 * and payload CRC-32 before returning a payload. See [decode]'s KDoc for the mirrored steps.
 *
 * @param protocol which tone band to use. Defaults to [NightjarAcoustics.Protocol.AUDIBLE],
 *   the architecture's default and the gate-2 phone-to-phone demo channel (architecture.md §2).
 * @param symbolRate which symbol-duration setting to use. Defaults to
 *   [NightjarAcoustics.SymbolRate.NORMAL] (architecture.md §3).
 */
class AcousticCarrier(
    private val protocol: NightjarAcoustics.Protocol = NightjarAcoustics.Protocol.AUDIBLE,
    private val symbolRate: NightjarAcoustics.SymbolRate = NightjarAcoustics.SymbolRate.NORMAL,
) : CovertCarrier<PcmAudio> {

    override val descriptor: ModuleDescriptor = ModuleDescriptor(
        id = ModuleId.ACOUSTIC_MODEM,
        displayName = "Acoustic FSK Modem",
        domain = CarrierDomain.AUDIO,
        role = ModuleRole.CARRIER,
    )

    /** Architect-defined INV-3 ceiling (architecture.md §5) — 1024 bytes. */
    override val maxPayloadBytes: Int = NightjarAcoustics.MAX_PAYLOAD_BYTES

    /**
     * Encode [payload] into a bin-exact multi-tone FSK tone sequence.
     *
     * Wire format produced (architecture.md §5-§6), in emission order:
     *  1. **START marker** — the protocol's two edge tones (bins `baseBin` and `topBin`)
     *     summed together, held for [NightjarAcoustics.MARKER_FRAMES] frames.
     *  2. **Coded byte stream** — the concatenation of:
     *     - the 6-byte header (magic, version, mode, length[2 BE], header_crc), RS(14,6)-coded
     *       to 14 bytes ([NightjarAcoustics.RS_HEADER_CODEWORD_BYTES]);
     *     - the payload bytes followed by a 4-byte big-endian CRC-32 (IEEE 802.3) trailer,
     *       split into [NightjarAcoustics.RS_PAYLOAD_DATA_BYTES]-byte blocks and RS(48,32)-coded
     *       (the final block is shortened — fewer data bytes, still 16 parity bytes).
     *     This single coded byte stream is chunked into symbols of `protocol.bytesPerSymbol`
     *     bytes (3 for AUDIBLE, 2 for NEAR_ULTRASONIC), zero-padded at the very end to a whole
     *     number of symbols. Byte boundaries are NOT re-aligned at the header/payload seam —
     *     a decoder demodulates one continuous byte stream and slices it using the `length`
     *     field recovered from the header, exactly as §5 describes.
     *     Each symbol's bytes map to simultaneous tones as: byte `i` of the symbol contributes
     *     its high nibble to group `2*i` and its low nibble to group `2*i+1` (group/nibble ->
     *     frequency per [NightjarAcoustics.Protocol.toneFrequencyHz]). Each symbol is held for
     *     `symbolRate.framesPerTx` frames.
     *  3. **END marker** — the protocol's two center-adjacent tones (bins `baseBin+31` and
     *     `baseBin+32`) summed together, held for [NightjarAcoustics.MARKER_FRAMES] frames.
     *
     * @throws IllegalArgumentException if `payload.size > maxPayloadBytes`.
     */
    override fun encode(payload: ByteArray): PcmAudio {
        require(payload.size <= maxPayloadBytes) {
            "payload size ${payload.size} exceeds maxPayloadBytes=$maxPayloadBytes"
        }

        val headerCoded = encodeHeader(payload.size)
        val payloadCoded = encodePayloadBlocks(payload)
        val codedBytes = headerCoded + payloadCoded

        val bytesPerSymbol = protocol.bytesPerSymbol
        val paddedLength = ((codedBytes.size + bytesPerSymbol - 1) / bytesPerSymbol) * bytesPerSymbol
        val padded = codedBytes.copyOf(paddedLength) // copyOf zero-fills the new tail

        val baseFreq = NightjarAcoustics.binFrequencyHz(protocol.baseBin)
        val topFreq = NightjarAcoustics.binFrequencyHz(protocol.topBin)
        val endFreqA = NightjarAcoustics.binFrequencyHz(protocol.baseBin + 31)
        val endFreqB = NightjarAcoustics.binFrequencyHz(protocol.baseBin + 32)

        val segments = mutableListOf<ShortArray>()
        var sampleCursor = 0L

        // 1. START marker
        segments += generateTone(listOf(baseFreq, topFreq), NightjarAcoustics.MARKER_FRAMES, sampleCursor)
        sampleCursor += NightjarAcoustics.MARKER_FRAMES.toLong() * NightjarAcoustics.FRAME_SAMPLES

        // 2. Coded byte stream, one symbol at a time
        var offset = 0
        while (offset < padded.size) {
            val symbolBytes = padded.copyOfRange(offset, offset + bytesPerSymbol)
            val freqs = symbolFrequencies(symbolBytes)
            segments += generateTone(freqs, symbolRate.framesPerTx, sampleCursor)
            sampleCursor += symbolRate.framesPerTx.toLong() * NightjarAcoustics.FRAME_SAMPLES
            offset += bytesPerSymbol
        }

        // 3. END marker
        segments += generateTone(listOf(endFreqA, endFreqB), NightjarAcoustics.MARKER_FRAMES, sampleCursor)

        return concatenate(segments)
    }

    /**
     * Recover a payload from [carrier] by reversing [encode] step by step (architecture.md §5-§6):
     *  1. **Locate the START marker**, in two stages:
     *     1a. *Coarse, frame-quantized candidate* — bounded sliding-window search (task #25).
     *         Unlike the pure codec round-trip (where [encode]'s exact output is fed straight back
     *         into [decode], START always at frame 0), a real `AudioRecord` capture
     *         ([dev.herakles.nightjar.modules.acoustic.AcousticModemController]) has arbitrary
     *         leading silence/noise before the transmitted signal starts. Scan forward one frame at
     *         a time from frame 0 (a 1-frame stride is required for correctness — the marker
     *         occupies exactly [NightjarAcoustics.MARKER_FRAMES] frames with zero slack for a
     *         coarser grid to reliably straddle — and is cheap regardless, one FFT per candidate
     *         frame), requiring the protocol's two edge tones (bins `baseBin` and `topBin`) to be
     *         simultaneously present, [NightjarAcoustics.DETECTOR_TONE_MARGIN_DB] dB above the
     *         median of the rest of the tone-grid band, for [NightjarAcoustics.MARKER_FRAMES]
     *         consecutive frames starting at the candidate. Bounded by the carrier buffer's own
     *         length (leaving room for the END marker), not a fixed time constant (codec-M03: an
     *         earlier fixed `MAX_START_SEARCH_SECONDS = 8.0` cap silently fell out of sync with
     *         the transport's actual up-to-20s listen window, rejecting a genuine transmission
     *         that started later than the cap even though it was still within the buffer). No
     *         match anywhere in that range -> [DecodeFailure.NO_PAYLOAD_FOUND].
     *     1b. *Sample-accurate refinement* (task #29) — [refineMarkerOnsetSample] takes that
     *         frame-quantized candidate (which is only guaranteed accurate to within one
     *         [NightjarAcoustics.FRAME_SAMPLES], since the receiver's frame grid has no relationship
     *         to the transmitter's true sample clock) and locates the exact sample where the
     *         marker's bin-exact, phase-independent tone plateau begins. Every downstream window
     *         (data start, each symbol, the END marker) is anchored to THIS sample, not the coarse
     *         frame boundary — see [refineMarkerOnsetSample]'s KDoc for why frame-quantization alone
     *         corrupts every symbol even though the marker itself was located successfully.
     *  2. **Demodulate + RS-decode the header** using a small, FIXED symbol count derived purely
     *     from [NightjarAcoustics.RS_HEADER_CODEWORD_BYTES] — NOT from how much of the carrier
     *     buffer happens to be left (task #29; see step 3 for why that distinction matters).
     *     Uncorrectable -> [DecodeFailure.UNRECOVERABLE_FEC]. Then check `header_crc` (CRC-8) and
     *     the magic/version/mode bytes -> [DecodeFailure.HEADER_INVALID] on mismatch, and the
     *     declared `length` against [maxPayloadBytes] -> [DecodeFailure.PAYLOAD_TOO_LARGE] if it
     *     doesn't fit.
     *  3. **Locate the END marker and the data window** — from the now-known declared `length`,
     *     compute exactly how many symbols the whole coded stream occupies by mirroring [encode]'s
     *     own RS(48,32) blocking math, then verify the protocol's two center-adjacent tones
     *     (`baseBin+31`, `baseBin+32`) at the sample position that count implies. This is
     *     deliberately NOT derived from `carrier.size - startSample` (task #29): just as a real
     *     `AudioRecord` capture has arbitrary leading content before the transmission starts, it
     *     also has arbitrary trailing content after the transmission ends (the mic keeps recording
     *     until the listen window elapses or the caller stops it) — assuming the END marker sits at
     *     the literal last sample of the buffer breaks the moment any trailing content exists.
     *     Carrier too short for the declared length, or marker content mismatch ->
     *     [DecodeFailure.NO_PAYLOAD_FOUND].
     *  4. **Demodulate** every symbol window (one FFT per symbol — tone assembly is bin-exact and
     *     phase-continuous, so any single frame within a symbol's held duration suffices): for each
     *     of `protocol.simultaneousTones` groups, the loudest of its 16 candidate bins recovers a
     *     nibble; nibbles reassemble into bytes exactly reversing [symbolFrequencies]. This yields
     *     one continuous coded byte stream, mirroring [encode]'s "header+payload as one stream"
     *     framing — byte boundaries are NOT re-aligned at the header/payload seam. (The header's own
     *     symbols, already read in step 2, are simply re-demodulated here as part of one uniform
     *     pass — cheap, and avoids stitching together two partial byte streams.)
     *  5. **RS-decode the payload+trailer blocks** (architecture.md §4's 32-data/16-parity blocking,
     *     final block shortened per the recovered `length`) via [ReedSolomon.decode]. Uncorrectable ->
     *     [DecodeFailure.UNRECOVERABLE_FEC].
     *  6. **Verify CRC-32** over the recovered payload against the 4-byte trailer -> deliver
     *     [DecodeResult.Success] on match, [DecodeFailure.INTEGRITY_MISMATCH] otherwise.
     *
     * `correctedByteErrors` on a [DecodeResult.Success] sums the RS-corrected byte count across the
     * header block and every payload block.
     */
    override fun decode(carrier: PcmAudio): DecodeResult {
        val frameSamplesCount = NightjarAcoustics.FRAME_SAMPLES
        val markerFrames = NightjarAcoustics.MARKER_FRAMES
        val markerSamplesCount = markerFrames * frameSamplesCount
        val framesPerSymbol = symbolRate.framesPerTx
        val symbolSamplesCount = framesPerSymbol * frameSamplesCount
        val bytesPerSymbol = protocol.bytesPerSymbol

        if (carrier.isEmpty() || carrier.size % frameSamplesCount != 0) {
            return DecodeResult.Failure(DecodeFailure.NO_PAYLOAD_FOUND, "carrier length is not a whole number of frames")
        }
        val totalFrames = carrier.size / frameSamplesCount
        if (totalFrames < 2 * markerFrames + 1) {
            return DecodeResult.Failure(DecodeFailure.NO_PAYLOAD_FOUND, "carrier too short to hold both markers plus data")
        }

        // 1a. Locate a coarse, frame-quantized START marker candidate via a bounded sliding-window
        // search (task #25). See this method's KDoc step 1a for why: a real capture has arbitrary
        // leading silence/offset that encode()'s own frame-0-aligned round-trip tests never exercise.
        // searchLimit is derived purely from the carrier buffer's own length (codec-M03) -- not a
        // fixed time constant, which drifted out of sync with the transport's actual capture window
        // (AcousticModemController's listen window can run up to MAX_LISTEN_SECONDS = 20s, but this
        // search used to give up after a hardcoded 8s regardless of how much buffer was left). It
        // leaves room for at least the END marker after any candidate; the loop itself takes the
        // first (i.e. earliest) matching frame, exactly mirroring the old "assume frame 0" behavior
        // when the marker genuinely is at frame 0.
        val searchLimit = totalFrames - 2 * markerFrames
        var startFrame = -1
        for (candidate in 0..searchLimit) {
            val isStart = (candidate until candidate + markerFrames).all { frame ->
                frameHasMarkerSignature(carrier, frame, protocol.baseBin, protocol.topBin)
            }
            if (isStart) {
                startFrame = candidate
                break
            }
        }
        if (startFrame < 0) {
            return DecodeResult.Failure(DecodeFailure.NO_PAYLOAD_FOUND, "no START marker detected")
        }

        // 1b. Refine to the TRUE, sample-accurate marker onset (task #29). See decode()'s KDoc
        // step 1b and refineMarkerOnsetSample()'s own KDoc for the full rationale: every downstream
        // window is anchored to this sample-accurate value, not the coarse frame boundary above.
        val startSample = refineMarkerOnsetSample(carrier, startFrame * frameSamplesCount, protocol.baseBin, protocol.topBin)

        val dataStartSample = startSample + markerSamplesCount

        // 2. Demodulate just enough symbols to cover the header codeword (a small, FIXED count --
        // task #29: NOT derived from carrier.size, so it works identically whether or not the
        // carrier has trailing content after the transmission ends), then RS(14,6)-decode +
        // validate it.
        val headerSymbols = ceilDiv(NightjarAcoustics.RS_HEADER_CODEWORD_BYTES, bytesPerSymbol)
        val headerAreaSamples = headerSymbols * symbolSamplesCount
        if (dataStartSample + headerAreaSamples + markerSamplesCount > carrier.size) {
            return DecodeResult.Failure(DecodeFailure.NO_PAYLOAD_FOUND, "carrier too short to hold the header symbols plus an END marker")
        }
        val headerAreaBytes = ByteArray(headerSymbols * bytesPerSymbol)
        for (symbolIndex in 0 until headerSymbols) {
            val symbolStartSample = dataStartSample + symbolIndex * symbolSamplesCount
            val magnitudes = fftMagnitudes(frameSamplesAt(carrier, symbolStartSample))
            demodulateSymbol(magnitudes).copyInto(headerAreaBytes, symbolIndex * bytesPerSymbol)
        }
        val headerCoded = headerAreaBytes.copyOfRange(0, NightjarAcoustics.RS_HEADER_CODEWORD_BYTES)
        val headerDecoded = ReedSolomon.decode(headerCoded, NightjarAcoustics.RS_HEADER_PARITY_BYTES)
            ?: return DecodeResult.Failure(DecodeFailure.UNRECOVERABLE_FEC, "header RS(14,6) block unrecoverable")
        val header = headerDecoded.data
        var correctedByteErrors = headerDecoded.correctedErrors

        if (crc8(header, 0, 5) != (header[5].toInt() and 0xFF)) {
            return DecodeResult.Failure(DecodeFailure.HEADER_INVALID, "header CRC-8 mismatch")
        }
        val magic = header[0].toInt() and 0xFF
        val version = header[1].toInt() and 0xFF
        val mode = header[2].toInt() and 0xFF
        if (magic != NightjarAcoustics.HEADER_MAGIC || version != NightjarAcoustics.PROTOCOL_VERSION) {
            return DecodeResult.Failure(DecodeFailure.HEADER_INVALID, "unexpected magic/version byte")
        }
        if (mode != protocol.modeByte) {
            return DecodeResult.Failure(DecodeFailure.HEADER_INVALID, "header mode byte does not match this carrier's protocol")
        }
        val length = ((header[3].toInt() and 0xFF) shl 8) or (header[4].toInt() and 0xFF)
        if (length > maxPayloadBytes) {
            return DecodeResult.Failure(
                DecodeFailure.PAYLOAD_TOO_LARGE,
                "declared length $length exceeds maxPayloadBytes=$maxPayloadBytes",
            )
        }

        // 3. From the now-known declared length, compute exactly how many symbols the whole coded
        // stream occupies by mirroring encode()'s own RS(48,32) blocking (architecture.md §4) --
        // task #29: deliberately NOT `carrier.size - dataStartSample`, since a real capture can have
        // arbitrary trailing content after the transmission ends (see decode()'s KDoc step 3).
        val protectedLength = length + NightjarAcoustics.TRAILER_BYTES
        val numPayloadBlocks = ceilDiv(protectedLength, NightjarAcoustics.RS_PAYLOAD_DATA_BYTES)
        val payloadCodedBytes = protectedLength + numPayloadBlocks * NightjarAcoustics.RS_PAYLOAD_PARITY_BYTES
        val totalCodedBytes = NightjarAcoustics.RS_HEADER_CODEWORD_BYTES + payloadCodedBytes
        val numSymbols = ceilDiv(totalCodedBytes, bytesPerSymbol)
        val dataSampleCount = numSymbols * symbolSamplesCount
        val endMarkerStartSample = dataStartSample + dataSampleCount
        if (endMarkerStartSample + markerSamplesCount > carrier.size) {
            return DecodeResult.Failure(DecodeFailure.NO_PAYLOAD_FOUND, "carrier too short to hold the declared payload length")
        }
        val hasEnd = (0 until markerFrames).all { i ->
            frameHasMarkerSignatureAtSample(
                carrier,
                endMarkerStartSample + i * frameSamplesCount,
                protocol.baseBin + 31,
                protocol.baseBin + 32,
            )
        }
        if (!hasEnd) {
            return DecodeResult.Failure(DecodeFailure.NO_PAYLOAD_FOUND, "no END marker detected")
        }

        // 4. Demodulate every symbol into the continuous coded byte stream. Each symbol window
        // reads the FIRST frameSamplesCount samples of that symbol's symbolSamplesCount-sample
        // sustain (symbolSamplesCount > frameSamplesCount for both SymbolRate settings), anchored
        // to the sample-accurate dataStartSample rather than a frame-quantized position. This
        // re-demodulates the header's own symbols (already read in step 2) as part of one uniform
        // pass -- cheap, and avoids stitching together two partial byte streams.
        val codedBytes = ByteArray(numSymbols * bytesPerSymbol)
        for (symbolIndex in 0 until numSymbols) {
            val symbolStartSample = dataStartSample + symbolIndex * symbolSamplesCount
            val magnitudes = fftMagnitudes(frameSamplesAt(carrier, symbolStartSample))
            demodulateSymbol(magnitudes).copyInto(codedBytes, symbolIndex * bytesPerSymbol)
        }

        // 5. Payload+trailer RS(48,32) block decode. protectedLength was already computed in step 3.
        val protectedData = ByteArray(protectedLength)
        var payloadOffset = 0
        var codedOffset = NightjarAcoustics.RS_HEADER_CODEWORD_BYTES
        while (payloadOffset < protectedLength) {
            val blockDataLen = minOf(NightjarAcoustics.RS_PAYLOAD_DATA_BYTES, protectedLength - payloadOffset)
            val codewordLen = blockDataLen + NightjarAcoustics.RS_PAYLOAD_PARITY_BYTES
            if (codedOffset + codewordLen > codedBytes.size) {
                return DecodeResult.Failure(DecodeFailure.UNRECOVERABLE_FEC, "coded stream shorter than declared payload length")
            }
            val blockCoded = codedBytes.copyOfRange(codedOffset, codedOffset + codewordLen)
            val blockDecoded = ReedSolomon.decode(blockCoded, NightjarAcoustics.RS_PAYLOAD_PARITY_BYTES)
                ?: return DecodeResult.Failure(
                    DecodeFailure.UNRECOVERABLE_FEC,
                    "payload RS(48,32) block unrecoverable at payload offset $payloadOffset",
                )
            blockDecoded.data.copyInto(protectedData, payloadOffset)
            correctedByteErrors += blockDecoded.correctedErrors
            payloadOffset += blockDataLen
            codedOffset += codewordLen
        }

        // 6. CRC-32 integrity check.
        val payload = protectedData.copyOfRange(0, length)
        val trailer = protectedData.copyOfRange(length, protectedLength)
        val expectedCrc32 = ((trailer[0].toLong() and 0xFF) shl 24) or
            ((trailer[1].toLong() and 0xFF) shl 16) or
            ((trailer[2].toLong() and 0xFF) shl 8) or
            (trailer[3].toLong() and 0xFF)
        val actualCrc32 = CRC32().apply { update(payload) }.value
        if (actualCrc32 != expectedCrc32) {
            return DecodeResult.Failure(
                DecodeFailure.INTEGRITY_MISMATCH,
                "CRC-32 mismatch: expected $expectedCrc32, got $actualCrc32",
            )
        }

        return DecodeResult.Success(payload, correctedByteErrors)
    }

    // ---------------------------------------------------------------------
    // Header framing (architecture.md §5) + RS(14,6)
    // ---------------------------------------------------------------------

    private fun encodeHeader(payloadLength: Int): ByteArray {
        val header = ByteArray(NightjarAcoustics.HEADER_BYTES)
        header[0] = NightjarAcoustics.HEADER_MAGIC.toByte()
        header[1] = NightjarAcoustics.PROTOCOL_VERSION.toByte()
        header[2] = protocol.modeByte.toByte()
        header[3] = ((payloadLength ushr 8) and 0xFF).toByte()
        header[4] = (payloadLength and 0xFF).toByte()
        header[5] = crc8(header, 0, 5).toByte()
        return ReedSolomon.encode(header, NightjarAcoustics.RS_HEADER_PARITY_BYTES)
    }

    // ---------------------------------------------------------------------
    // Payload + trailer framing (architecture.md §4-§5) + RS(48,32) blocking
    // ---------------------------------------------------------------------

    private fun encodePayloadBlocks(payload: ByteArray): ByteArray {
        val crc32 = CRC32().apply { update(payload) }.value
        val trailer = byteArrayOf(
            ((crc32 ushr 24) and 0xFF).toByte(),
            ((crc32 ushr 16) and 0xFF).toByte(),
            ((crc32 ushr 8) and 0xFF).toByte(),
            (crc32 and 0xFF).toByte(),
        )
        val protectedData = payload + trailer

        val blockCodewords = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < protectedData.size) {
            val end = minOf(offset + NightjarAcoustics.RS_PAYLOAD_DATA_BYTES, protectedData.size)
            val blockData = protectedData.copyOfRange(offset, end) // shortened for the final block
            blockCodewords += ReedSolomon.encode(blockData, NightjarAcoustics.RS_PAYLOAD_PARITY_BYTES)
            offset = end
        }
        return concatenateBytes(blockCodewords)
    }

    // ---------------------------------------------------------------------
    // Byte -> nibble -> tone mapping (architecture.md §1-§2)
    // ---------------------------------------------------------------------

    private fun symbolFrequencies(symbolBytes: ByteArray): List<Double> {
        val freqs = ArrayList<Double>(protocol.simultaneousTones)
        for (i in symbolBytes.indices) {
            val byteVal = symbolBytes[i].toInt() and 0xFF
            val highNibble = (byteVal ushr 4) and 0xF
            val lowNibble = byteVal and 0xF
            freqs += protocol.toneFrequencyHz(2 * i, highNibble)
            freqs += protocol.toneFrequencyHz(2 * i + 1, lowNibble)
        }
        return freqs
    }

    // ---------------------------------------------------------------------
    // Tone -> nibble -> byte demodulation (task #6, reverses symbolFrequencies)
    // ---------------------------------------------------------------------

    /** Reverses [symbolFrequencies]: the loudest bin in each 16-bin group recovers that group's nibble. */
    private fun demodulateSymbol(magnitudes: DoubleArray): ByteArray {
        val bytesPerSymbol = protocol.bytesPerSymbol
        val out = ByteArray(bytesPerSymbol)
        for (i in 0 until bytesPerSymbol) {
            val highNibble = strongestNibble(magnitudes, group = 2 * i)
            val lowNibble = strongestNibble(magnitudes, group = 2 * i + 1)
            out[i] = (((highNibble shl 4) or lowNibble) and 0xFF).toByte()
        }
        return out
    }

    /** Index (0-15) of the loudest bin within tone group [group]'s 16-bin candidate range. */
    private fun strongestNibble(magnitudes: DoubleArray, group: Int): Int {
        val groupBase = protocol.baseBin + NightjarAcoustics.NIBBLE_FREQUENCIES * group
        var bestNibble = 0
        var bestMagnitude = -1.0
        for (v in 0 until NightjarAcoustics.NIBBLE_FREQUENCIES) {
            val magnitude = magnitudes[groupBase + v]
            if (magnitude > bestMagnitude) {
                bestMagnitude = magnitude
                bestNibble = v
            }
        }
        return bestNibble
    }

    // ---------------------------------------------------------------------
    // Marker detection (task #6)
    // ---------------------------------------------------------------------

    /**
     * True when the frame-aligned window at frame [frameIndex] shows [binA] and [binB] both at
     * least [NightjarAcoustics.DETECTOR_TONE_MARGIN_DB] dB above the median of the rest of the
     * protocol's tone-grid band — the decode-side counterpart of [generateTone]'s two-tone
     * marker construction. Thin wrapper over [frameHasMarkerSignatureAtSample] for the coarse,
     * frame-quantized search (task #25); [refineMarkerOnsetSample] (task #29) calls the
     * sample-offset version directly to check arbitrary, non-frame-aligned positions.
     */
    private fun frameHasMarkerSignature(carrier: PcmAudio, frameIndex: Int, binA: Int, binB: Int): Boolean =
        frameHasMarkerSignatureAtSample(carrier, frameIndex * NightjarAcoustics.FRAME_SAMPLES, binA, binB)

    /**
     * True when the [NightjarAcoustics.FRAME_SAMPLES]-length window starting at the arbitrary
     * sample offset [sampleStart] shows [binA] and [binB] both at least
     * [NightjarAcoustics.DETECTOR_TONE_MARGIN_DB] dB above the median of the rest of the
     * protocol's tone-grid band. During an actual marker window every other grid bin is silent
     * (bin-exact tones -> zero FFT leakage, architecture.md §1), so this comfortably separates
     * a real marker from noise/silence, which never produces a comparable simultaneous margin.
     */
    private fun frameHasMarkerSignatureAtSample(carrier: PcmAudio, sampleStart: Int, binA: Int, binB: Int): Boolean =
        markerToneScore(carrier, sampleStart, binA, binB) >= NightjarAcoustics.DETECTOR_TONE_MARGIN_DB

    /**
     * Combined marker-tone margin (dB) at sample offset [sampleStart]: the WORSE of [binA]'s and
     * [binB]'s margin over the median of the rest of the protocol's tone-grid band. This is the
     * same quantity [frameHasMarkerSignatureAtSample] thresholds against
     * [NightjarAcoustics.DETECTOR_TONE_MARGIN_DB], factored out as a continuous score so
     * [refineMarkerOnsetSample] (task #29) can compare magnitudes across candidate offsets instead
     * of just testing a single boolean.
     */
    private fun markerToneScore(carrier: PcmAudio, sampleStart: Int, binA: Int, binB: Int): Double {
        val magnitudes = fftMagnitudes(frameSamplesAt(carrier, sampleStart))
        val referenceMagnitudes = (protocol.baseBin..protocol.topBin)
            .filter { it != binA && it != binB }
            .map { magnitudes[it] }
        val floorDb = magnitudeDb(medianOf(referenceMagnitudes))
        val aDb = magnitudeDb(magnitudes[binA])
        val bDb = magnitudeDb(magnitudes[binB])
        return minOf(aDb - floorDb, bDb - floorDb)
    }

    /**
     * Refines a coarse, frame-quantized START-marker candidate ([coarseCandidateSample], always a
     * multiple of [NightjarAcoustics.FRAME_SAMPLES]) to the transmitter's TRUE sample-accurate
     * onset (task #29 — see decode()'s KDoc step 1b for the field-test symptom this fixes).
     *
     * The root cause: the coarse search only ever tests candidate windows at multiples of
     * [NightjarAcoustics.FRAME_SAMPLES] relative to the RECEIVER's arbitrary buffer origin, which
     * has no relationship to the TRANSMITTER's true sample clock — a real `AudioRecord` capture's
     * transmission-start offset is essentially random relative to that grid. The marker tone is
     * bin-exact and held continuously for `MARKER_FRAMES * FRAME_SAMPLES` samples, so any
     * FRAME_SAMPLES-length analysis window fully INSIDE that span reads the identical, maximal
     * magnitude regardless of its exact starting sample (a bin-exact tone's DFT magnitude does not
     * depend on window phase) — a flat "plateau" spanning `markerSamples - FRAME_SAMPLES` samples.
     * A window straddling the marker's true edge instead reads a strictly lower, leakage-reduced
     * magnitude (a partial-duration tone burst spreads energy across neighboring bins instead of
     * concentrating it in [binA]/[binB]). This function locates the plateau's magnitude ceiling and
     * then the EARLIEST sample that reaches it — the marker's true onset — first at
     * [MARKER_REFINEMENT_SUBDIVISIONS] resolution (cheap, coarse) and then sample-exact within one
     * coarse step of that point.
     *
     * [coarseCandidateSample] is provably within one [NightjarAcoustics.FRAME_SAMPLES] of the true
     * onset in either direction (the coarse search's `markerFrames`-in-a-row match can only ever
     * land on the frame straddling the true edge, or the first fully-clean frame after it), so a
     * [MARKER_REFINEMENT_SEARCH_FRAMES]-frame search radius comfortably brackets it. Falls back to
     * [coarseCandidateSample] unrefined if the carrier is too short around the candidate to search
     * (shouldn't happen given decode()'s earlier length checks, but fails closed rather than
     * crashing).
     */
    private fun refineMarkerOnsetSample(
        carrier: PcmAudio,
        coarseCandidateSample: Int,
        binA: Int,
        binB: Int,
    ): Int {
        val frameSamplesCount = NightjarAcoustics.FRAME_SAMPLES
        val searchRadius = MARKER_REFINEMENT_SEARCH_FRAMES * frameSamplesCount
        val lo = maxOf(0, coarseCandidateSample - searchRadius)
        val hi = minOf(carrier.size - frameSamplesCount, coarseCandidateSample + searchRadius)
        if (lo > hi) return coarseCandidateSample

        // Stage 1 (coarse stride): find the plateau's magnitude ceiling and the earliest
        // coarse-grid sample that reaches it.
        val coarseStride = maxOf(1, frameSamplesCount / MARKER_REFINEMENT_SUBDIVISIONS)
        val coarseSamples = (lo..hi step coarseStride).toList()
        val coarseScores = coarseSamples.map { markerToneScore(carrier, it, binA, binB) }
        val plateauScore = coarseScores.maxOrNull() ?: return coarseCandidateSample
        val coarseOnsetIndex = coarseScores.indexOfFirst { plateauScore - it <= PLATEAU_EPSILON_DB }
        if (coarseOnsetIndex < 0) return coarseCandidateSample // unreachable: plateauScore is itself a coarseScores element
        val coarseOnset = coarseSamples[coarseOnsetIndex]

        // Stage 2 (sample-exact): scan backwards from the coarse hit, one sample at a time, for
        // the true left edge of the plateau.
        val fineLo = maxOf(lo, coarseOnset - coarseStride)
        for (sample in fineLo until coarseOnset) {
            if (plateauScore - markerToneScore(carrier, sample, binA, binB) <= PLATEAU_EPSILON_DB) {
                return sample
            }
        }
        return coarseOnset
    }

    /** [NightjarAcoustics.FRAME_SAMPLES]-length window starting at the arbitrary sample offset [sampleStart]. */
    private fun frameSamplesAt(carrier: PcmAudio, sampleStart: Int): ShortArray =
        carrier.copyOfRange(sampleStart, sampleStart + NightjarAcoustics.FRAME_SAMPLES)

    private fun medianOf(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    private fun magnitudeDb(magnitude: Double): Double = 20.0 * log10(max(magnitude, MIN_MAGNITUDE))

    /**
     * Ceiling integer division. Used by [decode] (task #29) to independently recompute the coded
     * byte stream's symbol/block layout from the header-declared length, mirroring [encodePayloadBlocks]'s
     * own block-splitting arithmetic (`(offset until protectedData.size step RS_PAYLOAD_DATA_BYTES)`)
     * and [encode]'s symbol-padding arithmetic without re-deriving either by hand.
     */
    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

    // ---------------------------------------------------------------------
    // FFT (task #6) — magnitude spectrum of one analysis frame
    // ---------------------------------------------------------------------

    /**
     * Linear magnitude at bins `0..FRAME_SAMPLES/2` of a real 1024-point FFT, rectangular window
     * (no Hann taper): [encode]'s tones are bin-exact and frame-aligned, so a rectangular window on
     * a full symbol/marker frame has zero spectral leakage — the "one tone = one bin" property
     * architecture.md §1 calls out. Adding a taper here would only spread each peak across
     * neighboring bins for no benefit on this clean, synthetic round-trip.
     */
    private fun fftMagnitudes(frame: ShortArray): DoubleArray {
        val n = frame.size
        val re = DoubleArray(n) { frame[it].toDouble() / Short.MAX_VALUE }
        val im = DoubleArray(n)
        fft(re, im)
        val half = n / 2
        return DoubleArray(half + 1) { bin -> sqrt(re[bin] * re[bin] + im[bin] * im[bin]) }
    }

    // ---------------------------------------------------------------------
    // Tone synthesis
    // ---------------------------------------------------------------------

    /**
     * Sum of equal-amplitude sinusoids at [frequenciesHz], held for [frames] frames of
     * [NightjarAcoustics.FRAME_SAMPLES] samples each, starting at global sample index
     * [startSampleIndex] (kept continuous across segments so bin-exact tones never phase-jump
     * at a frame boundary — see architecture.md §1's "one tone = one bin" alignment property).
     */
    private fun generateTone(frequenciesHz: List<Double>, frames: Int, startSampleIndex: Long): ShortArray {
        val numSamples = frames * NightjarAcoustics.FRAME_SAMPLES
        val samples = ShortArray(numSamples)
        val perToneAmplitude = TONE_AMPLITUDE / frequenciesHz.size
        for (n in 0 until numSamples) {
            val t = (startSampleIndex + n).toDouble() / NightjarAcoustics.SAMPLE_RATE_HZ
            var sum = 0.0
            for (freq in frequenciesHz) {
                sum += sin(2.0 * PI * freq * t)
            }
            val value = (sum * perToneAmplitude).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            samples[n] = value.toShort()
        }
        return samples
    }

    private fun concatenate(segments: List<ShortArray>): PcmAudio {
        val total = segments.sumOf { it.size }
        val out = ShortArray(total)
        var pos = 0
        for (segment in segments) {
            segment.copyInto(out, pos)
            pos += segment.size
        }
        return out
    }

    private fun concatenateBytes(chunks: List<ByteArray>): ByteArray {
        val total = chunks.sumOf { it.size }
        val out = ByteArray(total)
        var pos = 0
        for (chunk in chunks) {
            chunk.copyInto(out, pos)
            pos += chunk.size
        }
        return out
    }

    companion object {
        /** Peak amplitude budget shared across all simultaneous tones (headroom against clipping). */
        private const val TONE_AMPLITUDE: Double = Short.MAX_VALUE * 0.85

        /** Floor under log10 to avoid -Infinity dB for a silent/near-silent bin (task #6 demod). */
        private const val MIN_MAGNITUDE: Double = 1e-9

        /**
         * Search radius, in frames either side of the coarse frame-quantized candidate, for
         * [refineMarkerOnsetSample] (task #29). The coarse candidate is provably within one
         * [NightjarAcoustics.FRAME_SAMPLES] of the true onset (see that function's KDoc) — 2 frames
         * of radius is a comfortable safety margin over that bound.
         */
        private const val MARKER_REFINEMENT_SEARCH_FRAMES: Int = 2

        /**
         * Coarse-stage stride divisor for [refineMarkerOnsetSample]: FRAME_SAMPLES / 16 = 64
         * samples at the current [NightjarAcoustics.FRAME_SAMPLES] = 1024. Fine enough to land
         * comfortably inside the marker's tone plateau without the cost of a sample-by-sample scan
         * across the full search radius.
         */
        private const val MARKER_REFINEMENT_SUBDIVISIONS: Int = 16

        /**
         * Tolerance (dB) for "at the plateau" in [refineMarkerOnsetSample]. The plateau is
         * analytically flat — a bin-exact tone's DFT magnitude does not depend on the window's
         * phase alignment — so this only needs to absorb floating-point summation noise between two
         * windows of the identical tone at different sample offsets, comfortably below the gap to
         * any genuinely partial-duration (leakage-reduced) window.
         */
        private const val PLATEAU_EPSILON_DB: Double = 0.5

        /**
         * CRC-8 (poly [NightjarAcoustics.HEADER_CRC8_POLY], MSB-first, init 0) over
         * `data[offset, offset+length)`. Protects the header per architecture.md §5.
         */
        internal fun crc8(data: ByteArray, offset: Int, length: Int): Int {
            var crc = 0
            for (i in offset until offset + length) {
                crc = crc xor (data[i].toInt() and 0xFF)
                repeat(8) {
                    crc = if (crc and 0x80 != 0) {
                        (crc shl 1) xor NightjarAcoustics.HEADER_CRC8_POLY
                    } else {
                        crc shl 1
                    }
                    crc = crc and 0xFF
                }
            }
            return crc
        }
    }
}

/**
 * Reed-Solomon systematic codec over GF(2^8), primitive polynomial
 * [NightjarAcoustics.RS_PRIMITIVE_POLY] (architecture.md §4). `internal` (not `private`) so
 * [AcousticCarrier]'s decoder and this module's tests can reuse the same GF(2^8) arithmetic
 * instead of re-deriving it — the exact drift hazard architecture.md §"Module Interface" §5 warns
 * about, generalized from DSP constants to the FEC math built on top of them.
 *
 * [decode] (task #6) uses roots `alpha^0 .. alpha^(nsym-1)` (fcr=0, matching [generatorPoly]):
 * syndromes, Berlekamp-Massey error-locator search, Chien-search root finding, and Forney error
 * magnitudes are all derived for that root convention rather than the more commonly documented
 * `alpha^1..alpha^(2t)` (fcr=1) case, so the exponent arithmetic below intentionally differs from
 * a naively-copied textbook RS decoder.
 *
 * Standard systematic RS encoding via polynomial long division (the classic "message shifted
 * left by nsym, remainder replaces the zero low-order coefficients" construction): the message
 * and the generator polynomial `g(x) = product(x - alpha^i)` for `i` in `0 until nsym` are both
 * represented **descending-degree-first** (`poly[0]` = highest-degree coefficient), which is what
 * makes `poly[0]` the generator's leading coefficient (always 1) and is required for the LFSR-style
 * division loop below to correctly zero each processed coefficient. `codeword = data ++ parity`
 * with `data` in its original (transmission) order — the descending-degree framing is an internal
 * computation detail, not a byte-order change to the wire format. Works for any data length with
 * `data.size + nsym <= 255`, which is what lets the same code handle both the fixed 6-byte header
 * (RS(14,6)) and the fixed/shortened 32-or-fewer-byte payload blocks (RS(48,32)) architecture.md §4
 * calls for.
 */
internal object ReedSolomon {

    /** `codeword = data ++ parity`, `parity.size == nsym`. */
    fun encode(data: ByteArray, nsym: Int): ByteArray {
        val gen = generatorPoly(nsym)
        val msgOut = IntArray(data.size + nsym)
        for (i in data.indices) {
            msgOut[i] = data[i].toInt() and 0xFF
        }
        for (i in data.indices) {
            val coef = msgOut[i]
            if (coef != 0) {
                for (j in gen.indices) {
                    msgOut[i + j] = msgOut[i + j] xor GF256.mul(gen[j], coef)
                }
            }
        }
        val out = ByteArray(data.size + nsym)
        data.copyInto(out, 0)
        for (k in 0 until nsym) {
            out[data.size + k] = msgOut[data.size + k].toByte()
        }
        return out
    }

    /** `g(x) = product_{i=0}^{nsym-1} (x - alpha^i)`, descending-degree coefficients, `g[0] == 1`. */
    private fun generatorPoly(nsym: Int): IntArray {
        var g = intArrayOf(1)
        for (i in 0 until nsym) {
            g = polyMul(g, intArrayOf(1, GF256.exp(i))) // multiply by (x - alpha^i) == (x + alpha^i)
        }
        return g
    }

    /** Multiply two polynomials given in descending-degree coefficient order. */
    private fun polyMul(p: IntArray, q: IntArray): IntArray {
        val result = IntArray(p.size + q.size - 1)
        for (j in q.indices) {
            for (i in p.indices) {
                result[i + j] = result[i + j] xor GF256.mul(p[i], q[j])
            }
        }
        return result
    }

    // ---------------------------------------------------------------------
    // Decode (task #6): syndrome check -> Berlekamp-Massey -> Chien search -> Forney
    // ---------------------------------------------------------------------

    /**
     * Decode a systematic `codeword = data ++ parity` (descending-degree order, matching
     * [encode]'s output), correcting up to `nsym/2` byte errors. Returns `null` if the codeword
     * is uncorrectable — callers MUST treat `null` as [DecodeFailure.UNRECOVERABLE_FEC] and never
     * synthesize a payload from a partially-corrected buffer.
     *
     * Every corrected codeword is re-verified against all `nsym` roots before being returned
     * (mirroring the zero-syndrome fast path below); this is the safety net that keeps a subtle
     * Berlekamp-Massey/Forney bug from ever surfacing as a silently-wrong "successful" decode —
     * it fails closed (`null`) instead.
     */
    fun decode(codeword: ByteArray, nsym: Int): RsDecodeResult? {
        val n = codeword.size
        val k = n - nsym
        if (k <= 0 || nsym <= 0) return null
        val received = IntArray(n) { codeword[it].toInt() and 0xFF }

        val syndromes = computeSyndromes(received, nsym)
        if (syndromes.all { it == 0 }) {
            return RsDecodeResult(ByteArray(k) { received[it].toByte() }, 0)
        }

        val errorLocator = berlekampMassey(syndromes) ?: return null
        val numErrors = errorLocator.size - 1
        if (numErrors == 0 || numErrors * 2 > nsym) return null

        val errorPositions = findErrorPositions(errorLocator, n) ?: return null

        // Omega(z) = [S(z) * Lambda(z)] mod z^nsym (error evaluator polynomial, ascending order).
        val omega = polyMulAscendingTruncated(syndromes, errorLocator, nsym)
        val lambdaDeriv = formalDerivativeAscending(errorLocator)

        for (pos in errorPositions) {
            val degree = n - 1 - pos
            val xVal = GF256.exp(degree % 255) // X_l = alpha^{d(pos)}
            val xInv = GF256.inv(xVal)
            val numerator = polyEvalAscending(omega, xInv)
            val denominator = polyEvalAscending(lambdaDeriv, xInv)
            if (denominator == 0) return null
            // Forney (fcr=0): e_l = X_l * Omega(X_l^-1) / Lambda'(X_l^-1).
            val errorMagnitude = GF256.mul(xVal, GF256.mul(numerator, GF256.inv(denominator)))
            received[pos] = received[pos] xor errorMagnitude
        }

        // Safety net: a genuine correction must zero every syndrome again.
        if (!computeSyndromes(received, nsym).all { it == 0 }) return null

        return RsDecodeResult(ByteArray(k) { received[it].toByte() }, errorPositions.size)
    }

    /** `S_j = r(alpha^j)` for `j` in `0 until nsym`, evaluated the same way [encode]'s roots are checked. */
    private fun computeSyndromes(received: IntArray, nsym: Int): IntArray {
        val n = received.size
        return IntArray(nsym) { j ->
            var total = 0
            for (i in 0 until n) {
                val c = received[i]
                if (c == 0) continue
                val degree = n - 1 - i
                total = total xor GF256.mul(c, GF256.exp((j * degree) % 255))
            }
            total
        }
    }

    /**
     * Berlekamp-Massey: shortest-LFSR error-locator polynomial `Lambda(z)` (ascending order,
     * `Lambda[0] == 1`) generating the syndrome sequence [syndromes]. Returns `null` only when the
     * final degree exceeds `nsym/2` (an internal consistency guard — [decode]'s own `numErrors*2 >
     * nsym` check after calling this is the primary "too many errors" gate).
     */
    private fun berlekampMassey(syndromes: IntArray): IntArray? {
        val nsyms = syndromes.size
        var c = IntArray(nsyms + 1).also { it[0] = 1 }
        var b = IntArray(nsyms + 1).also { it[0] = 1 }
        var l = 0
        var m = 1
        var bCoef = 1
        for (i in 0 until nsyms) {
            var delta = syndromes[i]
            for (j in 1..l) {
                delta = delta xor GF256.mul(c[j], syndromes[i - j])
            }
            if (delta == 0) {
                m += 1
            } else if (2 * l <= i) {
                val t = c.copyOf()
                val coef = GF256.mul(delta, GF256.inv(bCoef))
                for (j in b.indices) {
                    if (b[j] != 0 && j + m < c.size) {
                        c[j + m] = c[j + m] xor GF256.mul(coef, b[j])
                    }
                }
                l = i + 1 - l
                b = t
                bCoef = delta
                m = 1
            } else {
                val coef = GF256.mul(delta, GF256.inv(bCoef))
                for (j in b.indices) {
                    if (b[j] != 0 && j + m < c.size) {
                        c[j + m] = c[j + m] xor GF256.mul(coef, b[j])
                    }
                }
                m += 1
            }
        }
        if (l < 0 || 2 * l > nsyms) return null
        return c.copyOfRange(0, l + 1)
    }

    /**
     * Chien search: for every `m` in `0 until 255`, `alpha^m` is a root of [errorLocator] exactly
     * when `alpha^{-m}` is an error location `X_l`; maps each root back to a codeword index. Returns
     * `null` if the roots found don't exactly match `errorLocator`'s degree, or land outside the
     * codeword — both mean the codeword has more errors than this `nsym` can correct.
     */
    private fun findErrorPositions(errorLocator: IntArray, n: Int): IntArray? {
        val numErrors = errorLocator.size - 1
        val positions = ArrayList<Int>(numErrors)
        for (m in 0 until 255) {
            if (polyEvalAscending(errorLocator, GF256.exp(m)) == 0) {
                val degree = (255 - m) % 255
                if (degree !in 0 until n) return null
                if (positions.size >= numErrors) return null
                positions.add(n - 1 - degree)
            }
        }
        if (positions.size != numErrors) return null
        return positions.toIntArray()
    }

    /** Horner evaluation of an ascending-order polynomial (`poly[i]` is the coefficient of `z^i`). */
    private fun polyEvalAscending(poly: IntArray, x: Int): Int {
        var result = 0
        for (i in poly.indices.reversed()) {
            result = GF256.mul(result, x) xor poly[i]
        }
        return result
    }

    /** `(a * b) mod z^truncLen`, both operands ascending-order. */
    private fun polyMulAscendingTruncated(a: IntArray, b: IntArray, truncLen: Int): IntArray {
        val result = IntArray(truncLen)
        for (i in a.indices) {
            if (a[i] == 0) continue
            for (j in b.indices) {
                val idx = i + j
                if (idx >= truncLen) continue
                if (b[j] == 0) continue
                result[idx] = result[idx] xor GF256.mul(a[i], b[j])
            }
        }
        return result
    }

    /** Formal derivative in GF(2) characteristic: even-degree terms vanish, odd-degree terms survive shifted down. */
    private fun formalDerivativeAscending(poly: IntArray): IntArray {
        if (poly.size <= 1) return IntArray(1)
        val out = IntArray(poly.size - 1)
        for (i in 1 until poly.size) {
            if (i % 2 == 1) out[i - 1] = poly[i]
        }
        return out
    }
}

/** Result of a successful [ReedSolomon.decode]: the corrected `k` data bytes plus corrected-byte count. */
internal data class RsDecodeResult(val data: ByteArray, val correctedErrors: Int)

/** GF(2^8) arithmetic tables built from [NightjarAcoustics.RS_PRIMITIVE_POLY], primitive element 2. */
internal object GF256 {
    private val expTable = IntArray(512)
    private val logTable = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            expTable[i] = x
            logTable[x] = i
            x = x shl 1
            if (x and 0x100 != 0) {
                x = x xor NightjarAcoustics.RS_PRIMITIVE_POLY
            }
        }
        for (i in 255 until 512) {
            expTable[i] = expTable[i - 255]
        }
    }

    fun exp(power: Int): Int = expTable[power % 255]

    fun mul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return expTable[logTable[a] + logTable[b]]
    }

    /** Multiplicative inverse in GF(2^8) (task #6 RS decode: Forney algorithm, Berlekamp-Massey). */
    fun inv(a: Int): Int {
        require(a != 0) { "0 has no multiplicative inverse in GF(2^8)" }
        return expTable[(255 - logTable[a]) % 255]
    }
}
