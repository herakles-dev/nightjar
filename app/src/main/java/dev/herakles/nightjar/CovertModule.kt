package dev.herakles.nightjar

/**
 * nightjar's shared covert-channel module contract.
 *
 * Four downstream implementations conform to the interfaces in this file, so the
 * module-picker UI, the debug probe, and the tests can treat every covert channel
 * uniformly regardless of whether the carrier is sound or an image:
 *
 *  - Module 3 acoustic modem  (task #5 Tx / #6 Rx) -> `CovertCarrier<PcmAudio>`
 *  - Module 5 acoustic detector (task #9)          -> `CovertDetector<PcmAudio>`
 *  - Module 1 image LSB codec  (task #11)          -> `CovertCarrier<android.graphics.Bitmap>`
 *  - Module 1 image steganalysis (task #12)        -> `CovertDetector<android.graphics.Bitmap>`
 *
 * Design split (two interfaces, one family):
 *  - A [CovertCarrier] is a *reversible codec*: `payload -> carrier -> payload`. It is the
 *    natural home for the round-trip lossless guarantee (spec INV-3).
 *  - A [CovertDetector] is a *one-way analyzer*: `sample -> score`. It never recovers a
 *    payload (that is exactly the passive/defensive posture of spec INV-4 and Module 5).
 *  Forcing both into a single interface would give the detectors a meaningless `encode`
 *  and the codecs a meaningless `analyze`, so they are kept as siblings under the shared
 *  [CovertModule] marker instead.
 *
 * Scope boundary — these are *pure codecs/analyzers over in-memory buffers*, NOT the audio
 * transport. `AudioTrack`/`AudioRecord` playback and capture (the streaming I/O described in
 * the spec's "streaming audio-capture/decode architecture") live in the UI/transport layer
 * that drives these interfaces. Keeping the contract at `ByteArray <-> PcmAudio` / `Bitmap`
 * is what makes the INV-3 loopback self-test (architecture.md §10) a pure unit test: feed an
 * encoder's output straight into the decoder, no speaker or mic required.
 *
 * This file deliberately has **no Android imports**: the contract is platform-neutral and the
 * carrier binding to `android.graphics.Bitmap` happens only in the Module 1 implementations.
 */

/**
 * 16-bit signed, single-channel PCM at [NightjarAcoustics.SAMPLE_RATE_HZ].
 * This is the on-the-wire buffer type shared by the acoustic modem (both directions) and the
 * acoustic detector — it matches Android `AudioTrack`/`AudioRecord` `ENCODING_PCM_16BIT` mono,
 * so no format conversion sits between the transport layer and these interfaces.
 */
typealias PcmAudio = ShortArray

/** The physical medium a module operates on. Lets the module-picker group modules by carrier. */
enum class CarrierDomain { AUDIO, IMAGE }

/** Whether a module hides/recovers data ([CovertCarrier]) or only scores a sample ([CovertDetector]). */
enum class ModuleRole { CARRIER, DETECTOR }

/**
 * Stable identity for each covert module, mirroring the spec's module numbering so the
 * module-picker home screen and the `COVERT_DEBUG` probe can name modules unambiguously.
 */
enum class ModuleId {
    /** Module 1 — image LSB steganography encode/decode (task #11). */
    IMAGE_LSB_CODEC,
    /** Module 1 — image chi-square/RS steganalysis (task #12). */
    IMAGE_STEGANALYSIS,
    /** Module 3 — acoustic FSK modem encode/decode (tasks #5/#6). */
    ACOUSTIC_MODEM,
    /** Module 5 — passive acoustic anomaly detector (task #9). */
    ACOUSTIC_DETECTOR,
    /** Module 2 — audio steganography encode/decode (phase-inversion / spectrogram-LSB / MFSK). */
    AUDIO_STEGO_CODEC,
    /** Module 2 — blind audio steganalysis (v5 addition, `AudioStegDetector`, spec.md INV-7). */
    AUDIO_STEGANALYSIS,
}

/**
 * Human- and machine-readable identity for a module. Emitted by the module-picker for display
 * and by the debug probe so a log line can be attributed to a specific module without guessing.
 */
data class ModuleDescriptor(
    val id: ModuleId,
    val displayName: String,
    val domain: CarrierDomain,
    val role: ModuleRole,
)

/** Marker supertype so carriers and detectors can be enumerated together by the module-picker. */
interface CovertModule {
    /** Static identity/metadata for this module. */
    val descriptor: ModuleDescriptor
}

/**
 * A reversible covert-data codec: hides [ByteArray] payloads inside a carrier of type [C] and
 * recovers them again. Implemented by the acoustic modem ([C] = [PcmAudio]) and the image LSB
 * codec ([C] = `android.graphics.Bitmap`).
 *
 * Contract for implementers:
 *  - [encode] MUST throw [IllegalArgumentException] if `payload.size > maxPayloadBytes`.
 *  - [encode] accepts only operator-supplied benign payloads (spec INV-1); the contract cannot
 *    enforce benignness by type, so no bundled payload constant may exist in any implementation.
 *  - The pair MUST satisfy the round-trip guarantee within the architect-defined envelope:
 *    for `payload.size <= maxPayloadBytes`, `decode(encode(payload))` is a [DecodeResult.Success]
 *    whose `payload` equals the input (spec INV-3; envelope in architecture.md §7).
 *  - [decode] MUST NEVER return corrupt data as success — any integrity failure is surfaced as
 *    [DecodeResult.Failure] (architecture.md §5).
 */
interface CovertCarrier<C> : CovertModule {
    /**
     * Largest payload this carrier guarantees a lossless round-trip for.
     * Acoustic modem: 1024 (architecture.md §5). Image codec: capacity-derived from the bitmap.
     */
    val maxPayloadBytes: Int

    /**
     * Hide [payload] and return the carrier holding it.
     * @throws IllegalArgumentException if `payload.size > maxPayloadBytes`.
     */
    fun encode(payload: ByteArray): C

    /** Attempt to recover a hidden payload from [carrier]; never returns corrupt data as success. */
    fun decode(carrier: C): DecodeResult
}

/** Outcome of a [CovertCarrier.decode] attempt. */
sealed interface DecodeResult {
    /**
     * A payload was recovered and passed every integrity check.
     * @param payload the recovered bytes (equals the original within the INV-3 envelope).
     * @param correctedByteErrors FEC-corrected byte count (acoustic RS, architecture.md §4);
     *        0 for carriers without error correction (e.g. lossless image LSB).
     */
    data class Success(
        val payload: ByteArray,
        val correctedByteErrors: Int = 0,
    ) : DecodeResult

    /** No trustworthy payload could be recovered. Reason is surfaced for the `COVERT_DEBUG` probe. */
    data class Failure(
        val reason: DecodeFailure,
        val detail: String? = null,
    ) : DecodeResult
}

/**
 * Why a [DecodeResult.Failure] occurred. Generic across carriers; the acoustic mapping is noted,
 * and the same categories apply to the image codec's header/length/checksum path.
 */
enum class DecodeFailure {
    /** Carrier held no detectable payload (acoustic: no START marker; image: no embedded frame). */
    NO_PAYLOAD_FOUND,
    /** Header failed magic/version/header-CRC validation (acoustic: architecture.md §5 header_crc). */
    HEADER_INVALID,
    /** Declared payload length exceeds `maxPayloadBytes`, so the frame is rejected before recovery. */
    PAYLOAD_TOO_LARGE,
    /** Forward-error-correction could not recover the payload (acoustic: RS block unrecoverable). */
    UNRECOVERABLE_FEC,
    /** Payload recovered but its integrity checksum did not match (acoustic: CRC-32 mismatch, §5). */
    INTEGRITY_MISMATCH,
}

/**
 * A passive covert-channel analyzer: scores a captured [S] sample for the likelihood that a covert
 * channel is present, WITHOUT decoding it. Implemented by the acoustic detector ([S] = [PcmAudio],
 * self-detecting the app's own Module 3 transmission per spec INV-4) and the image steganalysis
 * detector ([S] = `android.graphics.Bitmap`, chi-square/RS analysis per architecture library §06).
 *
 * The acoustic detector is stateful across successive windows (it maintains a running noise floor,
 * architecture.md §9); that state is an implementation detail held inside the instance. Each
 * [analyze] call returns the confidence *as of* the sample it was given.
 */
interface CovertDetector<S> : CovertModule {
    /**
     * Score at/above which [analyze] sets [DetectionResult.flagged] = true. Exposed as an explicit,
     * tunable false-positive/false-negative knob (acoustic threshold, architecture.md §9;
     * StegExpose's tunable detection threshold, library §06).
     */
    val flagThreshold: Float

    /** Analyze [sample] and return a confidence score in [0,1] plus the flag decision. */
    fun analyze(sample: S): DetectionResult
}

/**
 * Result of a [CovertDetector.analyze] pass.
 * @param confidence covert-channel likelihood in [0,1] (acoustic: fraction of recent frames meeting
 *        the tone-grid flag condition, architecture.md §9).
 * @param flagged convenience decision; implementers set this to `confidence >= flagThreshold`.
 * @param estimatedPayloadBytes optional quantitative estimate of hidden payload size when the
 *        technique supports it (image steganalysis can estimate length, library §06); null otherwise.
 * @param detail optional human-readable note for the `COVERT_DEBUG` probe.
 */
data class DetectionResult(
    val confidence: Float,
    val flagged: Boolean,
    val estimatedPayloadBytes: Int? = null,
    val detail: String? = null,
)
