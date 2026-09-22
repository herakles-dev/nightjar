package dev.herakles.nightjar.share

import android.content.Context
import dev.herakles.nightjar.R
import dev.herakles.nightjar.STURDY_MIN_COVER_LONG_SIDE_PX
import dev.herakles.nightjar.incoming.SturdyImageFireflyDecoder

/**
 * v6 send-time copy/mapping (task W2-2, design/screen-flow.md's v6 "Two send flows",
 * design/firefly-jar-identity.md's v6 addendum § Send/receive tone). Pure functions -- no
 * Compose, no [FireflyShare] I/O -- so both "send this firefly" (`JarDetailScreen.kt`) and
 * "hide one in a photo" (`HideInPhotoFlow.kt`) resolve the exact same technique -> [OutgoingKind]
 * -> advice mapping, rather than two independently hand-written copies of it -- the same
 * discipline [dev.herakles.nightjar.incoming.incomingOutcomeCopyFor] already established for the
 * receive side.
 */

/**
 * Which [OutgoingKind] a firefly's own stored carrier sends as, from its
 * [dev.herakles.nightjar.modules.fireflyjar.FireflyRecord.carrierKind] ("IMAGE"/"AUDIO", or null
 * for a pre-media-capture-era record with no stored carrier at all) and
 * [dev.herakles.nightjar.modules.fireflyjar.FireflyRecord.technique]
 * ([SturdyImageFireflyDecoder.STURDY_TECHNIQUE] for a sturdy image; null for a pre-v6/exact image
 * or the acoustic modem; an [dev.herakles.nightjar.modules.audiostego.AudioStegoTechnique] name
 * for the other two audio techniques).
 *
 * Null means there is nothing to send -- [carrierKind] null, i.e. no stored media at all
 * (`FireflyDetailScreen`'s "send this firefly" row is only ever shown when it isn't).
 */
fun outgoingKindFor(carrierKind: String?, technique: String?): OutgoingKind? = when (carrierKind) {
    "AUDIO" -> OutgoingKind.AUDIO
    "IMAGE" -> if (technique == SturdyImageFireflyDecoder.STURDY_TECHNIQUE) {
        OutgoingKind.IMAGE_STURDY
    } else {
        OutgoingKind.IMAGE_EXACT
    }
    else -> null
}

/**
 * One line of channel advice by [kind], in jar voice, shown above the share sheet
 * (design/screen-flow.md's v6 "Two send flows"): exact only survives as a file, sturdy usually
 * survives being sent as a photo but a very small picture can still lose it, and audio must
 * travel as a file or document, never a voice note.
 */
fun sendAdviceStringRes(kind: OutgoingKind): Int = when (kind) {
    OutgoingKind.IMAGE_EXACT -> R.string.send_advice_exact
    OutgoingKind.IMAGE_STURDY -> R.string.send_advice_sturdy
    OutgoingKind.AUDIO -> R.string.send_advice_audio
}

/**
 * Jar-voice refusal copy for a photo whose long side is [longSide] px, under
 * [STURDY_MIN_COVER_LONG_SIDE_PX] -- "hide one in a photo"'s own display text for
 * `SturdyCoverPrep.Rejected`, distinct from that class's own `detail` field (which cites
 * architecture.md by name in its KDoc-facing wording and isn't fit for display).
 */
fun sturdyRefusalMessage(context: Context, longSide: Int): String =
    context.getString(R.string.send_refusal_too_small, longSide, STURDY_MIN_COVER_LONG_SIDE_PX)
