package dev.herakles.nightjar

/**
 * Single source of truth for the Module 3 / Module 5 acoustic DSP parameters.
 *
 * Every value here is a single source of truth (§1 DSP constants, §2 tone plan,
 * §3 symbol rate, §4 RS FEC, §5 framing, §6 markers, §9 detector). The acoustic encoder
 * (#5), decoder (#6), and detector (#9) MUST read their constants from this object rather
 * than hardcoding their own — otherwise the three drift apart on FFT size or band layout and
 * the channel silently breaks. This is a configuration template, not an implementation:
 * it declares the numbers the three implementations share, and nothing else.
 */
object NightjarAcoustics {

    // --- §1 Fixed DSP constants (identical for both protocols) ---

    /** Capture/playback sample rate. Android AudioTrack/AudioRecord universally support 48 kHz. */
    const val SAMPLE_RATE_HZ: Int = 48_000

    /** Samples per frame, and the (real) FFT size. */
    const val FRAME_SAMPLES: Int = 1024

    /** FFT size == frame size, so one tone lands on exactly one bin. */
    const val FFT_SIZE: Int = 1024

    /** Bin width == tone spacing `dF` = SAMPLE_RATE_HZ / FRAME_SAMPLES = 46.875 Hz. */
    const val BIN_WIDTH_HZ: Double = SAMPLE_RATE_HZ.toDouble() / FRAME_SAMPLES

    /** Rx analysis window overlap (Hann, 50%) — improves symbol-edge tolerance (§1). */
    const val RX_OVERLAP_RATIO: Double = 0.5

    /** Center frequency of an FFT bin. Shared helper so #5 and #9 never disagree on a tone's Hz. */
    fun binFrequencyHz(bin: Int): Double = bin * BIN_WIDTH_HZ

    // --- §2 Tone plan: the two protocols ---

    /**
     * An acoustic protocol == a placement of the multi-tone FSK grid on the bin lattice.
     * Both protocols share the one modulation engine and differ only in base bin + tone count.
     */
    enum class Protocol(
        /** Header `mode` byte value (§5). */
        val modeByte: Int,
        /** Base FFT bin of the tone grid (`F0` = baseBin * dF). */
        val baseBin: Int,
        /** Number of simultaneous tones (== nibbles per symbol; bytes/symbol == this / 2). */
        val simultaneousTones: Int,
    ) {
        /** Robust, hardware-independent default; the phone-to-phone demo runs here (§2). */
        AUDIBLE(modeByte = 0x00, baseBin = 40, simultaneousTones = 6),

        /** Quieter near-ultrasonic variant; optional, hardware-dependent (§2, §8). */
        NEAR_ULTRASONIC(modeByte = 0x01, baseBin = 342, simultaneousTones = 4);

        /** 16 frequencies per nibble group. */
        val frequenciesPerGroup: Int get() = NIBBLE_FREQUENCIES

        /** Total tones in the grid = groups * 16. */
        val gridToneCount: Int get() = simultaneousTones * NIBBLE_FREQUENCIES

        /** Payload bytes carried per transmitted symbol. */
        val bytesPerSymbol: Int get() = simultaneousTones / 2

        /** Highest bin occupied by the grid: baseBin + gridToneCount - 1. */
        val topBin: Int get() = baseBin + gridToneCount - 1

        /** Frequency (Hz) for group `g` (0-based), nibble value `v` (0..15): F0 + (16*g + v)*dF. */
        fun toneFrequencyHz(group: Int, nibbleValue: Int): Double =
            binFrequencyHz(baseBin + NIBBLE_FREQUENCIES * group + nibbleValue)
    }

    /** Frequencies per nibble group (one per 4-bit value). */
    const val NIBBLE_FREQUENCIES: Int = 16

    // --- §3 Symbol rate ---

    /** Symbol-rate switch: trades data rate for robustness. `NORMAL` is the default. */
    enum class SymbolRate(val framesPerTx: Int) {
        NORMAL(framesPerTx = 9),
        FAST(framesPerTx = 6);

        /** Symbol duration in seconds = framesPerTx * FRAME_SAMPLES / SAMPLE_RATE_HZ. */
        val symbolDurationSeconds: Double
            get() = framesPerTx.toDouble() * FRAME_SAMPLES / SAMPLE_RATE_HZ
    }

    // --- §4 Reed-Solomon FEC ---

    /** RS field is GF(2^8) with primitive polynomial 0x11D (x^8+x^4+x^3+x^2+1). */
    const val RS_PRIMITIVE_POLY: Int = 0x11D

    /** Payload RS block: RS(48,32) — 32 data + 16 parity, corrects up to 8 byte-errors/codeword. */
    const val RS_PAYLOAD_DATA_BYTES: Int = 32
    const val RS_PAYLOAD_PARITY_BYTES: Int = 16
    const val RS_PAYLOAD_CODEWORD_BYTES: Int = RS_PAYLOAD_DATA_BYTES + RS_PAYLOAD_PARITY_BYTES

    /** Header RS block: RS(14,6) — 6 data + 8 parity, corrects up to 4 byte-errors. */
    const val RS_HEADER_DATA_BYTES: Int = 6
    const val RS_HEADER_PARITY_BYTES: Int = 8
    const val RS_HEADER_CODEWORD_BYTES: Int = RS_HEADER_DATA_BYTES + RS_HEADER_PARITY_BYTES

    // --- §5 Payload framing ---

    /** Header magic byte 'N'. */
    const val HEADER_MAGIC: Int = 0x4E

    /** Protocol/frame version. */
    const val PROTOCOL_VERSION: Int = 0x01

    /** Logical header size in bytes (magic, version, mode, length[2], header_crc). */
    const val HEADER_BYTES: Int = 6

    /** CRC-8 polynomial (0x07) protecting the 5 header bytes preceding header_crc. */
    const val HEADER_CRC8_POLY: Int = 0x07

    /** CRC-32 polynomial (IEEE 802.3) over the raw pre-FEC payload (frame trailer). */
    const val PAYLOAD_CRC32_POLY: Long = 0x04C11DB7L

    /** Trailer size in bytes (payload_crc32). */
    const val TRAILER_BYTES: Int = 4

    /** Architect-defined max payload for the lossless guarantee. */
    const val MAX_PAYLOAD_BYTES: Int = 1024

    /** Recommended demo payload ceiling (short text string). */
    const val RECOMMENDED_DEMO_PAYLOAD_BYTES: Int = 64

    // --- §6 Acoustic markers ---

    /** START/END markers are held for this many frames (5 frames == 106.7 ms). */
    const val MARKER_FRAMES: Int = 5

    // --- §9 Detector thresholds ---

    /** Minimum simultaneous on-grid tones over the noise floor to flag (§9). */
    const val DETECTOR_MIN_TONES: Int = 3

    /** Per-tone level over the running noise floor required to count, in dB (§9). */
    const val DETECTOR_TONE_MARGIN_DB: Double = 15.0

    /** Sustain requirement to flag: ~100 ms == 5 frames (§9). */
    const val DETECTOR_SUSTAIN_FRAMES: Int = 5

    /** Confidence is the fraction of the last N analysis frames meeting the flag condition (§9). */
    const val DETECTOR_CONFIDENCE_WINDOW_FRAMES: Int = 20
}
