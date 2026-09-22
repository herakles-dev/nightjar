package dev.herakles.nightjar.incoming

import dev.herakles.nightjar.picker.Module

/**
 * Outcome of routing one incoming file through [IncomingRouter]. Exactly five cases -- "every
 * incoming file resolves to exactly one
 * of caught, squeezed, damaged, no firefly, or unsupported, and a message is shown only after its
 * checksum verifies." Rendered by [IncomingScreen] with the real jar-voice copy from
 * design/screen-flow.md's "five outcomes" table -- replaces the earlier MINIMAL plain-text
 * placeholder.
 */
sealed interface IncomingOutcome {

    /** A firefly was found, decoded, and its checksum (or FEC) verified. */
    data class Caught(
        val module: Module,
        val technique: String,
        val payload: ByteArray,
        val carrierBytes: ByteArray,
        val extension: String,
    ) : IncomingOutcome

    /**
     * The file arrived in a lossy image container or a compressed audio container, and nothing
     * decoded. NOT an assertion that a firefly was actually sent -- design/screen-flow.md's own
     * copy is deliberately hedged ("if one was sent, the app may have squeezed it").
     */
    data class Squeezed(val container: SqueezedContainer) : IncomingOutcome

    /** A frame header verified (magic + version + header-CRC) but the payload checksum or FEC
     *  failed -- a genuinely corrupted transfer, not a coincidental magic-byte match. */
    data class Damaged(val detail: String? = null) : IncomingOutcome

    /** A lossless container (PNG, WAV); nothing found. */
    data object NoFirefly : IncomingOutcome

    /** Not an image/audio nightjar can read. Distinct from [TooLarge]: before this split, an
     *  ordinary large photo was told "nightjar doesn't know
     *  this kind of file" -- an accurate-sounding but false reason, since the real one (the size
     *  cap) was computed and then discarded. */
    data class Unsupported(val reason: String? = null) : IncomingOutcome

    /** The file itself, or what it decoded to, was over one of the receive pipeline's size caps
     *  ([dev.herakles.nightjar.incoming.IncomingAndroidAdapters.MAX_INCOMING_FILE_BYTES] on the
     *  raw file, or its 24 MP image-pixel cap). */
    data object TooLarge : IncomingOutcome

    /** A firefly decoded and verified, but persisting it would have left the device below
     *  [dev.herakles.nightjar.modules.fireflyjar.FireflyMediaStore.MIN_FREE_SPACE_BYTES] free
     *  -- distinct from [TooLarge], which is about the
     *  file itself, not the device's remaining room. */
    data object OutOfSpace : IncomingOutcome
}

/** Which kind of lossy container produced an [IncomingOutcome.Squeezed]. */
enum class SqueezedContainer { LOSSY_IMAGE, COMPRESSED_AUDIO }

/** `COVERT_DEBUG`'s `outcome` field ([dev.herakles.nightjar.DebugProbe.reportIncoming]). */
val IncomingOutcome.probeOutcomeName: String
    get() = when (this) {
        is IncomingOutcome.Caught -> "caught"
        is IncomingOutcome.Squeezed -> "squeezed"
        is IncomingOutcome.Damaged -> "damaged"
        is IncomingOutcome.NoFirefly -> "no_firefly"
        is IncomingOutcome.Unsupported -> "unsupported"
        is IncomingOutcome.TooLarge -> "too_large"
        is IncomingOutcome.OutOfSpace -> "out_of_space"
    }

/** `COVERT_DEBUG`'s `technique` field -- only [IncomingOutcome.Caught] carries one. */
val IncomingOutcome.probeTechnique: String?
    get() = (this as? IncomingOutcome.Caught)?.technique
