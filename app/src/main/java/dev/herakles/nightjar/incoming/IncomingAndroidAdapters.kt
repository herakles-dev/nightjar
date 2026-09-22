package dev.herakles.nightjar.incoming

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dev.herakles.nightjar.NightjarAcoustics
import dev.herakles.nightjar.PcmAudio
import dev.herakles.nightjar.modules.acoustic.decodeCompressedAudioToPcm
import dev.herakles.nightjar.modules.acoustic.padToFrameBoundary
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * The Android-specific decode adapters [IncomingRouter]'s core stays free of (build notes:
 * "Android decoding behind small adapters, so it's unit-testable"). [IncomingPipeline] is the
 * only caller.
 */
object IncomingAndroidAdapters {

    /**
     * Why a bounded read/decode step below returned nothing usable -- kept distinct from a plain
     * nullable result (gate-41 safety re-audit, finding F-3) so [IncomingPipeline] can report an
     * honest [IncomingOutcome.TooLarge] instead of folding "this file is simply too big" into the
     * same generic [IncomingOutcome.Unsupported] a genuinely-unrecognized format gets.
     */
    sealed interface Bounded<out T> {
        data class Ok<T>(val value: T) : Bounded<T>
        data object TooLarge : Bounded<Nothing>
        data object Failed : Bounded<Nothing>
    }

    /** spec.md v6 receive-plumbing build notes: "reject > 50 MB." */
    const val MAX_INCOMING_FILE_BYTES = 50 * 1024 * 1024

    /** spec.md v6 receive-plumbing build notes: "refuse exact-LSB attempts above ~24 MP (avoid
     *  OOM)." Expressed in total pixels, not megapixels, to avoid a floating-point comparison. */
    private const val MAX_IMAGE_PIXELS = 24_000_000L

    /**
     * `true` if a `width`x`height` image is over [MAX_IMAGE_PIXELS]. Split out of
     * [decodeBoundedBitmap] as a small pure function (`internal`, not `private`) so
     * [IncomingAndroidAdaptersTest] can exercise the size-limit arithmetic directly with plain
     * JUnit, without needing to actually allocate/encode a real oversized [Bitmap] in a test.
     */
    internal fun exceedsPixelLimit(width: Int, height: Int): Boolean =
        width.toLong() * height.toLong() > MAX_IMAGE_PIXELS

    /**
     * Reads all of [uri]'s bytes, bounded to [maxBytes]. Never throws -- any I/O failure or a
     * missing/unreadable Uri reads as [Bounded.Failed]; exceeding the bound reads as the
     * distinct [Bounded.TooLarge] (gate-41 safety re-audit, finding F-3) rather than the same
     * generic failure, so the caller can report an honest reason instead of discarding it.
     */
    fun readBoundedBytes(
        contentResolver: ContentResolver,
        uri: Uri,
        maxBytes: Int = MAX_INCOMING_FILE_BYTES,
    ): Bounded<ByteArray> = try {
        val stream = contentResolver.openInputStream(uri) ?: return Bounded.Failed
        stream.use {
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = it.read(chunk)
                if (n < 0) break
                total += n
                if (total > maxBytes) return Bounded.TooLarge
                out.write(chunk, 0, n)
            }
            Bounded.Ok(out.toByteArray())
        }
    } catch (ioFailure: IOException) {
        Bounded.Failed
    } catch (denied: SecurityException) {
        Bounded.Failed
    } catch (oom: OutOfMemoryError) {
        // gate-41 safety re-audit, finding F-2 (defense in depth): the 50 MB bound already makes
        // this unreachable in practice, but ByteArrayOutputStream's internal doubling means peak
        // usage briefly exceeds the final size -- fail closed rather than let an Error escape.
        Bounded.Failed
    }

    /**
     * Bounded image decode (build notes: "bound image decode with inJustDecodeBounds and refuse
     * exact-LSB attempts above ~24 MP"): reads dimensions only first via
     * [BitmapFactory.Options.inJustDecodeBounds], refuses anything over [MAX_IMAGE_PIXELS]
     * without ever allocating pixel memory for it (as the distinct [Bounded.TooLarge], gate-41
     * safety re-audit finding F-3), then decodes for real. [Bounded.Failed] for anything that
     * isn't a decodable image, or that runs out of memory anyway despite the bound (a large
     * `Bitmap.Config.ARGB_8888` allocation can still fail on a memory-constrained device even
     * under the pixel cap).
     *
     * The whole body is one try/catch (build notes: "never crash on malformed input") -- a
     * sufficiently malformed byte array can make even the bounds-only first decode throw rather
     * than cleanly report `outWidth/outHeight <= 0` (confirmed empirically under this project's
     * Robolectric test harness; real Android's own OEM decoders are also documented to vary in
     * how defensively they fail), so this doesn't assume only the second, real decode can fail.
     */
    fun decodeBoundedBitmap(bytes: ByteArray): Bounded<Bitmap> = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            Bounded.Failed
        } else if (exceedsPixelLimit(width, height)) {
            Bounded.TooLarge
        } else {
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options())
            if (decoded == null) Bounded.Failed else Bounded.Ok(decoded)
        }
    } catch (oom: OutOfMemoryError) {
        Bounded.Failed
    } catch (malformed: Exception) {
        Bounded.Failed
    }

    /**
     * Compressed audio -> PCM for the acoustic modem only (build notes: "reuse the modem's
     * MediaExtractor decode for the acoustic modem only"). Delegates to
     * [dev.herakles.nightjar.modules.acoustic.decodeCompressedAudioToPcm]'s [ByteArray] overload
     * -- the exact MediaExtractor/MediaCodec path `AcousticModemScreen.kt`'s own file-import
     * action uses, reused rather than reimplemented (that function and its `ImportedAudio`
     * result type were bumped from `private` to `internal` for this call site).
     *
     * Takes [bytes] -- [IncomingPipeline]'s own already-read, already-sniffed copy -- rather than
     * re-opening the source `Uri` a second time (gate-41 safety re-audit, finding F-8): a hostile
     * `ContentProvider` could otherwise serve different bytes on each open, persisting a carrier
     * that doesn't actually contain the payload this function decoded.
     *
     * Returns `null` if the bytes couldn't be demuxed at all (no audio track, corrupt container).
     * Returns a non-null but EMPTY array if the container's own declared sample rate/channel
     * count didn't match [NightjarAcoustics.SAMPLE_RATE_HZ] mono (that function's own contract,
     * unchanged) -- either way, [IncomingRouter.routeCompressedAudio] treats "nothing usable" as
     * [SqueezedContainer.COMPRESSED_AUDIO], never a crash. A non-empty result is padded to a
     * whole number of [NightjarAcoustics.FRAME_SAMPLES] via [padToFrameBoundary], the same
     * pre-decode step `AcousticModemController.importAndDecode` applies.
     */
    suspend fun decodeCompressedAudioForModem(bytes: ByteArray): PcmAudio? {
        val imported = decodeCompressedAudioToPcm(bytes) ?: return null
        if (imported.sampleRateHz != NightjarAcoustics.SAMPLE_RATE_HZ || imported.numChannels != 1) {
            return ShortArray(0)
        }
        return padToFrameBoundary(imported.samples)
    }
}
