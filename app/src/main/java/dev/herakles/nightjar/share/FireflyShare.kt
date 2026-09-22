package dev.herakles.nightjar.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.herakles.nightjar.DebugProbe
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * v6 send plumbing: jar-mode "send this firefly"
 * and "hide one in a photo" hand the system share sheet a copy written into a private *cache*
 * directory (`context.cacheDir/outgoing/`), exposed only through [androidx.core.content.FileProvider]
 * (`AndroidManifest.xml`'s `${applicationId}.outgoing` provider + `res/xml/outgoing_paths.xml`).
 * [prepareOutgoing] never touches `MediaStore` and never writes anywhere under shared/external
 * storage -- that split is the whole point: jar-mode send is a private
 * cache-file share, while the technical screens' existing save/share (`ImageStegoScreen.kt`,
 * `AcousticModemScreen.kt`) keeps writing to `Pictures/Nightjar`/`Music/Nightjar` as before and
 * is untouched by this file. No permission is declared or needed here, since
 * every byte this object writes only ever leaves the app through the system share sheet the
 * operator explicitly invokes via [shareIntent].
 *
 * A "keep a copy" gesture -- writing that same content to `Pictures/Nightjar`/`Music/Nightjar`,
 * matching the technical screens' existing save/share precedent -- is deliberately OUT of scope
 * for this object (design/screen-flow.md "Two send flows" step 3: it is its own separate,
 * explicit MediaStore write the UI layer performs on its own follow-up user gesture, never
 * something [prepareOutgoing] or [shareIntent] does as a side effect).
 *
 * This task (v6, "send plumbing") only builds this utility -- no screen calls
 * [prepareOutgoing]/[shareIntent] yet, that UI wiring is a later task. The one live call site
 * today is the [sweepOutgoing] `Context` overload, wired into `MainActivity.onCreate`.
 */
object FireflyShare {

    /** Subdirectory of `context.cacheDir` this object ever reads or writes -- must match
     *  `res/xml/outgoing_paths.xml`'s `<cache-path path="outgoing/">`. */
    private const val OUTGOING_SUBDIR = "outgoing"

    /** `${applicationId}.outgoing` -- must match AndroidManifest.xml's `<provider
     *  android:authorities>`. Built from [Context.getPackageName] rather than hard-coded so this
     *  can never silently desync from a future applicationId change. */
    private const val AUTHORITY_SUFFIX = ".outgoing"

    /** Default staleness window for [sweepOutgoing] -- one hour. */
    const val DEFAULT_MAX_AGE_MILLIS: Long = 60 * 60 * 1000L

    /**
     * Writes [bytes] into `cacheDir/outgoing/` under a neutral name derived from [kind] and
     * [now] ([outgoingFileName]), and returns the `content://` [Uri] a [shareIntent] hands to
     * the system share sheet.
     *
     * Never touches `MediaStore` and never writes outside the app's private cache directory.
     * Reports the send to [DebugProbe] ("the last send (technique, cache file bytes, authority
     * used), cache-dir file count") -- a no-op in release builds, same as every other
     * `DebugProbe.report*` call site.
     *
     * Throws [IOException] if the write fails, mirroring `ImageStegoScreen.kt`'s
     * `saveBitmapAsPngToMediaStore` precedent of surfacing a real reason rather than a silent
     * no-op that leaves a caller's `runCatching` with nothing to report.
     *
     * [extension] defaults to [kind]'s own [OutgoingKind.fileExtension] -- every existing caller
     * (a fresh encode: workshop save/share, audio-stego jar embed, hide-in-photo) keeps getting
     * exactly that. Review finding #3 (v6/review-fix): the "share an existing caught firefly"
     * call site (`JarDetailScreen.kt`) passes the firefly's own real on-disk extension instead
     * (`dev.herakles.nightjar.share.outgoingMimeAndExtensionFor`), so a modem firefly caught from
     * a compressed voice note keeps its real container instead of being renamed `.wav`.
     */
    fun prepareOutgoing(
        context: Context,
        bytes: ByteArray,
        kind: OutgoingKind,
        now: Instant = Instant.now(),
        extension: String = kind.fileExtension,
    ): Uri {
        val dir = outgoingDir(context).apply { mkdirs() }
        val file = File(dir, outgoingFileName(kind, now, extension = extension))
        try {
            file.writeBytes(bytes)
        } catch (failure: IOException) {
            throw IOException("failed writing outgoing cache file $file", failure)
        }
        val authority = "${context.packageName}$AUTHORITY_SUFFIX"
        val uri = FileProvider.getUriForFile(context, authority, file)
        DebugProbe.reportOutgoingSend(technique = kind.name, bytes = bytes.size.toLong(), authority = authority)
        DebugProbe.reportOutgoingFileCount(dir.list()?.size ?: 0)
        return uri
    }

    /**
     * Builds the `ACTION_SEND` share-sheet [Intent] for [uri]/[mime]: `EXTRA_STREAM` + a
     * [ClipData] (built via the raw label/mimeTypes/item constructor, not
     * `ClipData.newUri(ContentResolver, ...)`, so this stays a pure function of [uri]/[mime] with
     * no `Context` needed) + `FLAG_GRANT_READ_URI_PERMISSION`, wrapped in `Intent.createChooser`
     * so the operator picks the destination app every time rather than nightjar ever defaulting
     * to one. Shape matches `AcousticModemScreen.kt`'s `shareFromDevice`/`buildPngShareIntent` in
     * `ImageStegoScreen.kt`, minus their MediaStore-specific framing -- [uri] here is always a
     * [FileProvider] Uri, never `MediaStore`'s own content provider.
     */
    fun shareIntent(uri: Uri, mime: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData(mime, arrayOf(mime), ClipData.Item(uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null)
    }

    /**
     * Deletes files directly under [dir] whose last-modified time is older than [maxAgeMillis]
     * relative to [now]. Pure policy over whatever [dir]/[now] it's given -- no `Context`, no
     * real clock unless a caller passes one -- so a JVM test can point [dir] at a JUnit
     * `TemporaryFolder` and control "now" directly.
     *
     * Deliberately never deletes eagerly right after a send: a share target may read the Uri
     * asynchronously (the chooser can stay open, or the receiving app can import on its own
     * schedule), so an immediate delete could yank the file out from under a legitimate read.
     * [DEFAULT_MAX_AGE_MILLIS] (one hour) is comfortably past any realistic share-target read,
     * and this app's one real call site (the [Context] overload below) runs the sweep once per
     * process start rather than per send.
     *
     * Only ever touches files directly inside [dir] (not subdirectories, not [dir]'s parent, and
     * nothing that isn't a plain file) -- anything else on disk is never in scope no matter its
     * age. A missing/non-directory [dir] is a silent no-op, matching `FireflyMediaStore`'s
     * "ensure, don't assume" directory handling elsewhere in this codebase.
     */
    fun sweepOutgoing(dir: File, now: Instant, maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS) {
        val cutoff = now.toEpochMilli() - maxAgeMillis
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isFile && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    /**
     * Convenience overload for this app's one real call site: `MainActivity.onCreate`, which
     * dispatches this onto `Dispatchers.IO` there since it touches the filesystem ("a one-line
     * call; don't restructure MainActivity"). Resolves the real outgoing
     * directory from [context], sweeps it with the real clock via [sweepOutgoing], then reports
     * the post-sweep file count to [DebugProbe].
     */
    fun sweepOutgoing(context: Context) {
        val dir = outgoingDir(context)
        sweepOutgoing(dir, Instant.now())
        DebugProbe.reportOutgoingFileCount(dir.list()?.size ?: 0)
    }

    /** `cacheDir/outgoing/` -- must match `res/xml/outgoing_paths.xml`'s registered path. */
    private fun outgoingDir(context: Context): File = File(context.cacheDir, OUTGOING_SUBDIR)
}

/**
 * The kind of file [FireflyShare.prepareOutgoing] is about to write, driving both its MIME type
 * and [outgoingFileName]'s neutral display name.
 *
 * [IMAGE_STURDY] gets a `.jpg` extension (not `.png`) -- deliberately matching what a messaging
 * app's own photo-send path would produce anyway, since the sturdy technique exists specifically
 * to survive that recompression. [IMAGE_EXACT] stays `.png`: the exact-LSB frame
 * only survives byte-for-byte as a lossless file (the "send it as a file" advisory).
 */
enum class OutgoingKind(val mimeType: String, internal val filePrefix: String, internal val fileExtension: String) {
    IMAGE_EXACT(mimeType = "image/png", filePrefix = "IMG", fileExtension = "png"),
    IMAGE_STURDY(mimeType = "image/jpeg", filePrefix = "IMG", fileExtension = "jpg"),
    AUDIO(mimeType = "audio/wav", filePrefix = "AUD", fileExtension = "wav"),
}

/** `yyyyMMdd_HHmmss` -- naming pattern (`IMG_yyyyMMdd_HHmmss.png`, etc).
 *  Immutable/thread-safe per [DateTimeFormatter]'s own contract, so this is safely shared across
 *  every [outgoingFileName] call. */
private val OUTGOING_TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

/**
 * Neutral, disguise-safe display name for an outgoing file: never "firefly"/"nightjar"/"stego"
 * anywhere in it, matching what a phone camera or an ordinary chat app would itself produce
 * (e.g. `IMG_20260101_120000.png`, `AUD_20260101_120000.wav`) so a recipient's gallery or file
 * list shows nothing that reads as covert.
 *
 * A pure function of [kind]/[now]/[zone] -- no filesystem access, no `Context`, no side effect --
 * so it's directly and deterministically testable: the same three inputs always produce the same
 * name. [zone] defaults to the device's zone for real callers; tests pass a fixed [ZoneId] (e.g.
 * `ZoneOffset.UTC`) so the expected string doesn't depend on the machine running the test.
 *
 * [extension] defaults to [kind]'s own [OutgoingKind.fileExtension] -- see [FireflyShare
 * .prepareOutgoing]'s matching KDoc for why a caller ever overrides it (review finding #3,
 * v6/review-fix).
 */
internal fun outgoingFileName(
    kind: OutgoingKind,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    extension: String = kind.fileExtension,
): String {
    val stamp = OUTGOING_TIMESTAMP_FORMAT.withZone(zone).format(now)
    return "${kind.filePrefix}_$stamp.$extension"
}
