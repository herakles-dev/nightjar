package dev.herakles.nightjar.incoming

import android.content.ContentResolver
import android.content.Context
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
     * Reads all of [uri]'s bytes, bounded to [maxBytes]. Never throws -- any I/O failure, a
     * missing/unreadable Uri, or exceeding the bound, all read as `null` (the caller maps that to
     * [IncomingOutcome.Unsupported]).
     */
    fun readBoundedBytes(
        contentResolver: ContentResolver,
        uri: Uri,
        maxBytes: Int = MAX_INCOMING_FILE_BYTES,
    ): ByteArray? = try {
        contentResolver.openInputStream(uri)?.use { stream ->
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                total += n
                if (total > maxBytes) return null
                out.write(chunk, 0, n)
            }
            out.toByteArray()
        }
    } catch (ioFailure: IOException) {
        null
    } catch (denied: SecurityException) {
        null
    }

    /**
     * Bounded image decode (build notes: "bound image decode with inJustDecodeBounds and refuse
     * exact-LSB attempts above ~24 MP"): reads dimensions only first via
     * [BitmapFactory.Options.inJustDecodeBounds], refuses anything over [MAX_IMAGE_PIXELS]
     * without ever allocating pixel memory for it, then decodes for real. `null` for anything
     * that isn't a decodable image, is oversized, or runs out of memory anyway despite the bound
     * (a large `Bitmap.Config.ARGB_8888` allocation can still fail on a memory-constrained
     * device even under the pixel cap).
     *
     * The whole body is one try/catch (build notes: "never crash on malformed input") -- a
     * sufficiently malformed byte array can make even the bounds-only first decode throw rather
     * than cleanly report `outWidth/outHeight <= 0` (confirmed empirically under this project's
     * Robolectric test harness; real Android's own OEM decoders are also documented to vary in
     * how defensively they fail), so this doesn't assume only the second, real decode can fail.
     */
    fun decodeBoundedBitmap(bytes: ByteArray): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            null
        } else if (exceedsPixelLimit(width, height)) {
            null
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options())
        }
    } catch (oom: OutOfMemoryError) {
        null
    } catch (malformed: Exception) {
        null
    }

    /**
     * Compressed audio -> PCM for the acoustic modem only (build notes: "reuse the modem's
     * MediaExtractor decode for the acoustic modem only"). Delegates to
     * [dev.herakles.nightjar.modules.acoustic.decodeCompressedAudioToPcm] -- the exact
     * MediaExtractor/MediaCodec path `AcousticModemScreen.kt`'s own file-import action uses,
     * reused rather than reimplemented (that function and its `ImportedAudio` result type were
     * bumped from `private` to `internal` for this call site; no behavior there changed).
     *
     * Returns `null` if the file couldn't be read/demuxed at all (unreadable Uri, no audio track,
     * corrupt container). Returns a non-null but EMPTY array if the container's own declared
     * sample rate/channel count didn't match [NightjarAcoustics.SAMPLE_RATE_HZ] mono (that
     * function's own contract, unchanged) -- either way,
     * [IncomingRouter.routeCompressedAudio] treats "nothing usable" as
     * [SqueezedContainer.COMPRESSED_AUDIO], never a crash. A non-empty result is padded to a
     * whole number of [NightjarAcoustics.FRAME_SAMPLES] via [padToFrameBoundary], the same
     * pre-decode step `AcousticModemController.importAndDecode` applies.
     */
    suspend fun decodeCompressedAudioForModem(context: Context, uri: Uri): PcmAudio? {
        val imported = decodeCompressedAudioToPcm(context, uri) ?: return null
        if (imported.sampleRateHz != NightjarAcoustics.SAMPLE_RATE_HZ || imported.numChannels != 1) {
            return ShortArray(0)
        }
        return padToFrameBoundary(imported.samples)
    }
}
