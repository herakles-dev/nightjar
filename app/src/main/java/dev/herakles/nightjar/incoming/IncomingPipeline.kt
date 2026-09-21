package dev.herakles.nightjar.incoming

import android.content.Context
import android.net.Uri
import dev.herakles.nightjar.DebugProbe
import dev.herakles.nightjar.modules.fireflyjar.FireflyDatabase
import dev.herakles.nightjar.modules.fireflyjar.FireflyMediaStore
import dev.herakles.nightjar.modules.fireflyjar.FireflyRecord
import dev.herakles.nightjar.modules.fireflyjar.FireflyRepository
import dev.herakles.nightjar.picker.Module

/**
 * The single Android-facing entry point for the receive pipeline (spec.md v6 receive-plumbing,
 * gate-31/32): `MainActivity` hands this a [Uri] straight off an `ACTION_SEND`/`ACTION_VIEW`
 * intent (already off the main thread -- see `MainActivity.kt`'s `LaunchedEffect`), and gets back
 * exactly one [IncomingOutcome] (INV-12). Reads bytes ([IncomingAndroidAdapters.readBoundedBytes]),
 * sniffs them ([FileSniffer.sniff]), then dispatches into [IncomingRouter] with whichever small
 * Android adapter that route needs (a decoded [android.graphics.Bitmap] for images, a demuxed
 * PCM buffer for compressed audio -- WAV needs no adapter at all).
 *
 * On [IncomingOutcome.Caught], persists the firefly via [FireflyRepository.insertWithMedia] as a
 * `"RECEIVED"` row in the right jar -- the exact same `direction` value and write-then-insert
 * path every other catch site in this app already uses (`ImageStegoScreen.kt`,
 * `AudioStegoScreen.kt`, `AcousticModemScreen.kt`). Builds its own [FireflyRepository] from
 * [FireflyDatabase]'s singleton accessor rather than threading the Compose-scoped instance
 * through from `MainActivity` -- both wrap the same underlying singleton DAO/media directory, so
 * this stays a self-contained entry point callable from a plain `Context` (and testable without
 * Compose).
 *
 * Reports the result to [DebugProbe] as its last step, unconditionally -- the v6 addition to the
 * `COVERT_DEBUG` probe contract (spec.md's Runtime Verification Surface): "the dump gains the
 * last incoming file (action, MIME type, detected technique, outcome from INV-12's five)."
 */
object IncomingPipeline {

    suspend fun route(context: Context, uri: Uri, action: String): IncomingOutcome {
        val bytes = IncomingAndroidAdapters.readBoundedBytes(context.contentResolver, uri)
        if (bytes == null) {
            val outcome = IncomingOutcome.Unsupported("couldn't read this file, or it's too large")
            DebugProbe.reportIncoming(action, sniffedType = "UNREADABLE", technique = null, outcome = outcome.probeOutcomeName)
            return outcome
        }

        val sniffed = FileSniffer.sniff(bytes)
        val outcome = when (sniffed.domain) {
            SniffedDomain.IMAGE -> {
                val bitmap = IncomingAndroidAdapters.decodeBoundedBitmap(bytes)
                if (bitmap == null) {
                    IncomingOutcome.Unsupported("couldn't read this as an image, or it's too large")
                } else {
                    IncomingRouter.routeImage(bytes, bitmap, sniffed, sniffed.defaultExtension())
                }
            }
            SniffedDomain.AUDIO -> if (sniffed == SniffedType.WAV) {
                IncomingRouter.routeWav(bytes)
            } else {
                val modemPcm = IncomingAndroidAdapters.decodeCompressedAudioForModem(context, uri)
                IncomingRouter.routeCompressedAudio(bytes, modemPcm, sniffed.defaultExtension())
            }
            SniffedDomain.UNSUPPORTED -> IncomingOutcome.Unsupported("nightjar doesn't know this kind of file")
        }

        if (outcome is IncomingOutcome.Caught) {
            persistCaught(context, outcome)
        }

        DebugProbe.reportIncoming(action, sniffedType = sniffed.name, technique = outcome.probeTechnique, outcome = outcome.probeOutcomeName)
        return outcome
    }

    /** `carrierKind` for [FireflyRecord] -- mirrors every other catch site's own IMAGE/AUDIO
     *  split. [Module.DETECTOR] is unreachable here ([IncomingRouter] never produces a
     *  [IncomingOutcome.Caught] with that module -- the meadow never catches a firefly, spec.md
     *  `JarRole.WATCHING`), kept present per this codebase's unreachable-but-present discipline
     *  (`MainActivity.kt`'s `ScreenSaver` KDoc). */
    private fun carrierKindFor(module: Module): String? = when (module) {
        Module.IMAGE_STEGANOGRAPHY -> "IMAGE"
        Module.AUDIO_STEGANOGRAPHY, Module.ACOUSTIC_MODEM -> "AUDIO"
        Module.DETECTOR -> null
    }

    private suspend fun persistCaught(context: Context, outcome: IncomingOutcome.Caught) {
        val repository = FireflyRepository(FireflyDatabase.getInstance(context).fireflyDao(), FireflyMediaStore(context))
        val record = FireflyRecord(
            moduleId = outcome.module.name,
            direction = "RECEIVED",
            timestampMillis = System.currentTimeMillis(),
            payloadSizeBytes = outcome.payload.size,
            technique = outcome.technique,
            payloadPreview = outcome.payload.decodeToString().take(40),
            carrierKind = carrierKindFor(outcome.module),
        )
        repository.insertWithMedia(record, outcome.carrierBytes, outcome.extension)
    }
}
