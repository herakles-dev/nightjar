package dev.herakles.nightjar.share

import android.content.Context
import dev.herakles.nightjar.R
import dev.herakles.nightjar.STURDY_MIN_COVER_LONG_SIDE_PX
import dev.herakles.nightjar.incoming.SturdyImageFireflyDecoder

/**
 * v6 send-time copy/mapping (design/screen-flow.md's v6 "Two send flows"). Pure functions -- no
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
 * internal design docs by name in its KDoc-facing wording and isn't fit for display).
 */
fun sturdyRefusalMessage(context: Context, longSide: Int): String =
    context.getString(R.string.send_refusal_too_small, longSide, STURDY_MIN_COVER_LONG_SIDE_PX)

/**
 * Real outgoing MIME type + file extension for an EXISTING caught firefly's own stored carrier
 * (adversarial review finding #3, v6/review-fix). [outgoingKindFor] only ever resolves an
 * "AUDIO" `carrierKind` to [OutgoingKind.AUDIO], whose [OutgoingKind.mimeType]/
 * [OutgoingKind.fileExtension] are fixed at audio/wav -- correct for the acoustic modem's/
 * audio-stego's own genuinely-WAV catches and for both techniques' freshly-CREATED records
 * (this app's own new encodes are always exactly what [OutgoingKind] says by construction), but
 * wrong for a modem firefly caught from a compressed voice note
 * ([dev.herakles.nightjar.incoming.IncomingRouter.routeCompressedAudio]): that firefly's
 * ORIGINAL compressed bytes are persisted under their own real extension via
 * [dev.herakles.nightjar.modules.fireflyjar.FireflyMediaStore] -- exporting it as audio/wav
 * mislabels bytes that are still M4A/MP3/etc.
 *
 * Derives the real extension from [mediaPath]'s own suffix (the content-addressed filename
 * [dev.herakles.nightjar.modules.fireflyjar.FireflyMediaStore.write] returns) rather than
 * trusting [kind] alone -- this is the "share/keep an existing caught firefly" path only;
 * [kind]'s own role for a fresh encode is untouched (this function isn't called there).
 *
 * Only [OutgoingKind.AUDIO] is ever affected -- an image kind's carrier extension is always jpg/
 * png already by construction, so this returns [kind]'s own values unchanged for both image
 * kinds. Falls back to [kind]'s own (audio/wav, wav) when [mediaPath] is null/blank or its
 * extension isn't recognized in [AUDIO_EXTENSION_MIME_TYPES] -- which is every genuinely-WAV
 * audio firefly, so today's behavior is unchanged for every path that's actually WAV.
 */
internal fun outgoingMimeAndExtensionFor(kind: OutgoingKind, mediaPath: String?): Pair<String, String> {
    if (kind != OutgoingKind.AUDIO) return kind.mimeType to kind.fileExtension
    val extension = mediaPath?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() }
    val mime = extension?.let { AUDIO_EXTENSION_MIME_TYPES[it] }
    return if (mime != null && extension != null) mime to extension else kind.mimeType to kind.fileExtension
}

/** Extension -> MIME lookup backing [outgoingMimeAndExtensionFor] -- every compressed container
 *  the acoustic modem can catch a firefly from (`AudioStegoScreen.kt`'s own "m4a/ogg/opus/mp3/
 *  amr" KDoc, `IncomingRouter.routeCompressedAudio`'s matching list), plus "wav" itself so a
 *  genuinely-WAV audio firefly resolves identically whether it hits this map or falls through to
 *  [kind]'s own default. */
private val AUDIO_EXTENSION_MIME_TYPES: Map<String, String> = mapOf(
    "wav" to "audio/wav",
    "m4a" to "audio/mp4",
    "mp3" to "audio/mpeg",
    "ogg" to "audio/ogg",
    "opus" to "audio/opus",
    "amr" to "audio/amr",
)
