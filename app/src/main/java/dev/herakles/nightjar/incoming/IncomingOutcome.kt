package dev.herakles.nightjar.incoming

import dev.herakles.nightjar.picker.Module

/**
 * Outcome of routing one incoming file through [IncomingRouter] (spec.md v6 receive-plumbing,
 * INV-12, gate-31/32). Exactly five cases -- INV-12: "every incoming file resolves to exactly one
 * of caught, squeezed, damaged, no firefly, or unsupported, and a message is shown only after its
 * checksum verifies." Rendered by [IncomingScreen] (task W2-1) with the real jar-voice copy from
 * design/screen-flow.md's "five outcomes" table -- replaces task W1-2's MINIMAL plain-text
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

    /** Not an image/audio nightjar can read, or over the size limits. */
    data class Unsupported(val reason: String? = null) : IncomingOutcome
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
    }

/** `COVERT_DEBUG`'s `technique` field -- only [IncomingOutcome.Caught] carries one. */
val IncomingOutcome.probeTechnique: String?
    get() = (this as? IncomingOutcome.Caught)?.technique
