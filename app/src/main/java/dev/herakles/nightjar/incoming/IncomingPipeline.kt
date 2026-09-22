package dev.herakles.nightjar.incoming

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import dev.herakles.nightjar.DebugProbe
import dev.herakles.nightjar.modules.fireflyjar.FireflyDatabase
import dev.herakles.nightjar.modules.fireflyjar.FireflyMediaStore
import dev.herakles.nightjar.modules.fireflyjar.FireflyRecord
import dev.herakles.nightjar.modules.fireflyjar.FireflyRepository
import dev.herakles.nightjar.modules.fireflyjar.InsufficientStorageException
import dev.herakles.nightjar.modules.fireflyjar.MAX_STORED_MESSAGE_CHARS
import dev.herakles.nightjar.picker.Module
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/**
 * The single Android-facing entry point for the receive pipeline: `MainActivity` hands this a
 * [Uri] straight off an `ACTION_SEND`/`ACTION_VIEW`
 * intent (already off the main thread -- see `MainActivity.kt`'s `LaunchedEffect`), and gets back
 * exactly one [IncomingOutcome]. Reads bytes ([IncomingAndroidAdapters.readBoundedBytes]),
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
 * `COVERT_DEBUG` probe contract: "the dump gains the
 * last incoming file (action, MIME type, detected technique, outcome from the five)."
 *
 * [route] is a total function over every `Uri` an external app can hand it, by construction:
 * the whole body runs under one `catch (Throwable)`, so
 * a hostile/broken `ContentProvider`, an `OutOfMemoryError` from any decode step, or a full-disk
 * `IOException` on persist all resolve to [IncomingOutcome.Unsupported] rather than crashing the
 * app -- restoring the "exactly one outcome" guarantee this module already claimed
 * but this function didn't yet keep.
 */
object IncomingPipeline {

    suspend fun route(context: Context, uri: Uri, action: String): IncomingOutcome = try {
        routeUnguarded(context, uri, action)
    } catch (cancelled: CancellationException) {
        // Structured cancellation must propagate, never be reported as an outcome.
        throw cancelled
    } catch (crash: Throwable) {
        val outcome = IncomingOutcome.Unsupported("something went wrong reading this file")
        DebugProbe.reportIncoming(action, sniffedType = "CRASH", technique = null, outcome = outcome.probeOutcomeName)
        outcome
    }

    private suspend fun routeUnguarded(context: Context, uri: Uri, action: String): IncomingOutcome {
        // Narrow the intent surface to content:// -- the
        // only scheme a share/view sender legitimately uses. A file:// Uri would resolve under
        // this app's own UID (no exfiltration path exists; this app requests no INTERNET permission),
        // but accepting it at all is a needless confused-deputy surface with no legitimate use.
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            val outcome = IncomingOutcome.Unsupported("nightjar doesn't know this kind of file")
            DebugProbe.reportIncoming(action, sniffedType = "BAD_SCHEME", technique = null, outcome = outcome.probeOutcomeName)
            return outcome
        }

        val bounded = IncomingAndroidAdapters.readBoundedBytes(context.contentResolver, uri)
        val bytes = when (bounded) {
            is IncomingAndroidAdapters.Bounded.TooLarge -> {
                DebugProbe.reportIncoming(action, sniffedType = "TOO_LARGE", technique = null, outcome = IncomingOutcome.TooLarge.probeOutcomeName)
                return IncomingOutcome.TooLarge
            }
            is IncomingAndroidAdapters.Bounded.Failed -> {
                val outcome = IncomingOutcome.Unsupported("couldn't read this file")
                DebugProbe.reportIncoming(action, sniffedType = "UNREADABLE", technique = null, outcome = outcome.probeOutcomeName)
                return outcome
            }
            is IncomingAndroidAdapters.Bounded.Ok -> bounded.value
        }

        // Captured into a local val, not referenced
        // directly inside the checkCancelled lambdas below -- `coroutineContext` is a
        // suspend-only property, and those lambdas are plain `() -> Unit`, so they can only close
        // over an already-resolved `CoroutineContext` value (same pattern
        // `AcousticModemScreen.kt`'s own `decodeCancellable` uses for the identical reason).
        val ctx = coroutineContext

        val sniffed = FileSniffer.sniff(bytes)
        val outcome = when (sniffed.domain) {
            SniffedDomain.IMAGE -> when (val bitmap = IncomingAndroidAdapters.decodeBoundedBitmap(bytes)) {
                is IncomingAndroidAdapters.Bounded.TooLarge -> IncomingOutcome.TooLarge
                is IncomingAndroidAdapters.Bounded.Failed -> IncomingOutcome.Unsupported("couldn't read this as an image")
                is IncomingAndroidAdapters.Bounded.Ok -> IncomingRouter.routeImage(bytes, bitmap.value, sniffed, sniffed.defaultExtension())
            }
            SniffedDomain.AUDIO -> if (sniffed == SniffedType.WAV) {
                IncomingRouter.routeWav(bytes) { ctx.ensureActive() }
            } else {
                val modemPcm = IncomingAndroidAdapters.decodeCompressedAudioForModem(bytes)
                IncomingRouter.routeCompressedAudio(bytes, modemPcm, sniffed.defaultExtension()) { ctx.ensureActive() }
            }
            SniffedDomain.UNSUPPORTED -> IncomingOutcome.Unsupported("nightjar doesn't know this kind of file")
        }

        val finalOutcome = if (outcome is IncomingOutcome.Caught) persistCaught(context, outcome) else outcome

        DebugProbe.reportIncoming(action, sniffedType = sniffed.name, technique = finalOutcome.probeTechnique, outcome = finalOutcome.probeOutcomeName)
        return finalOutcome
    }

    /** `carrierKind` for [FireflyRecord] -- mirrors every other catch site's own IMAGE/AUDIO
     *  split. [Module.DETECTOR] is unreachable here ([IncomingRouter] never produces a
     *  [IncomingOutcome.Caught] with that module -- the meadow never catches a firefly,
     *  `JarRole.WATCHING`), kept present per this codebase's unreachable-but-present discipline
     *  (`MainActivity.kt`'s `ScreenSaver` KDoc). */
    private fun carrierKindFor(module: Module): String? = when (module) {
        Module.IMAGE_STEGANOGRAPHY -> "IMAGE"
        Module.AUDIO_STEGANOGRAPHY, Module.ACOUSTIC_MODEM -> "AUDIO"
        Module.DETECTOR -> null
    }

    /**
     * Persists [outcome]'s firefly, returning [outcome] unchanged on success. Returns
     * [IncomingOutcome.OutOfSpace] instead if the write refused itself over free space -- caught
     * specifically here, ahead of [route]'s generic
     * [Throwable] boundary, so a device that's simply full gets its own honest outcome rather
     * than the same catch-all every other unanticipated failure gets.
     */
    private suspend fun persistCaught(context: Context, outcome: IncomingOutcome.Caught): IncomingOutcome {
        val repository = FireflyRepository(FireflyDatabase.getInstance(context).fireflyDao(), FireflyMediaStore(context))
        val record = FireflyRecord(
            moduleId = outcome.module.name,
            direction = "RECEIVED",
            timestampMillis = System.currentTimeMillis(),
            payloadSizeBytes = outcome.payload.size,
            technique = outcome.technique,
            payloadPreview = outcome.payload.decodeToString().take(MAX_STORED_MESSAGE_CHARS),
            carrierKind = carrierKindFor(outcome.module),
        )
        return try {
            repository.insertWithMedia(record, outcome.carrierBytes, outcome.extension)
            outcome
        } catch (fullDisk: InsufficientStorageException) {
            IncomingOutcome.OutOfSpace
        }
    }
}
