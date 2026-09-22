package dev.herakles.nightjar.share

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException

/**
 * "keep a copy" (design/screen-flow.md v6 "Two send flows" step 3; spec.md v6, amended INV-5,
 * task W2-2 item 3): an explicit, separate gesture the operator takes AFTER a jar-mode send,
 * writing the exact bytes [FireflyShare.prepareOutgoing] already staged for the share sheet into
 * shared storage -- `Pictures/Nightjar` for an image [kind], `Music/Nightjar` for
 * [OutgoingKind.AUDIO] -- mirroring the technical screens' own save precedent
 * (`ImageStegoScreen.kt`'s `saveBitmapAsPngToMediaStore`, `AcousticModemScreen.kt`/
 * `AudioStegoScreen.kt`'s `writeWavToMediaStore`) rather than re-deriving a new one from scratch.
 *
 * Never called automatically -- amended INV-5 requires its own explicit tap every time; every
 * call site in this app (`JarDetailScreen.kt`'s "keep a copy" link) is a direct response to a
 * user tap, never a side effect of [FireflyShare.prepareOutgoing]/[FireflyShare.shareIntent]
 * themselves.
 *
 * Two-phase write (`IS_PENDING = 1` while [bytes] streams out, cleared to `0` only once that
 * succeeds), same pattern `saveBitmapAsPngToMediaStore` already established, so a half-written
 * file is never visible to the gallery or a share target mid-write. Throws [IOException] on
 * failure so the caller's own `runCatching`/try-catch surfaces a real reason.
 *
 * [mimeType]/[extension] default to [kind]'s own [OutgoingKind.mimeType]/
 * [OutgoingKind.fileExtension] -- every existing caller (a fresh encode) keeps getting exactly
 * that. Review finding #3 (v6/review-fix): "keep a copy" of an EXISTING caught firefly
 * (`JarDetailScreen.kt`) passes the firefly's own real on-disk values instead
 * (`dev.herakles.nightjar.share.outgoingMimeAndExtensionFor`), so a modem firefly caught from a
 * compressed voice note keeps its real container/MIME type instead of being mislabeled
 * audio/wav.
 */
fun keepOutgoingCopy(
    context: Context,
    bytes: ByteArray,
    kind: OutgoingKind,
    mimeType: String = kind.mimeType,
    extension: String = kind.fileExtension,
): Uri {
    val resolver = context.contentResolver
    val displayName = "nightjar_${System.currentTimeMillis()}.$extension"
    return when (kind) {
        OutgoingKind.AUDIO -> writeToMediaStore(
            resolver = resolver,
            collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            relativePath = "${Environment.DIRECTORY_MUSIC}/Nightjar",
            displayName = displayName,
            mimeType = mimeType,
            bytes = bytes,
        )
        OutgoingKind.IMAGE_EXACT, OutgoingKind.IMAGE_STURDY -> writeToMediaStore(
            resolver = resolver,
            collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            relativePath = "${Environment.DIRECTORY_PICTURES}/Nightjar",
            displayName = displayName,
            mimeType = mimeType,
            bytes = bytes,
        )
    }
}

private fun writeToMediaStore(
    resolver: ContentResolver,
    collection: Uri,
    relativePath: String,
    displayName: String,
    mimeType: String,
    bytes: ByteArray,
): Uri {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(collection, values)
        ?: throw IOException("MediaStore rejected the insert (collection unavailable)")
    try {
        val opened = resolver.openOutputStream(uri)?.use { stream -> stream.write(bytes) }
        checkNotNull(opened) { "couldn't open an output stream for $uri" }
    } catch (failure: Exception) {
        resolver.delete(uri, null, null) // don't leave a pending/broken row behind
        throw IOException("failed writing bytes to MediaStore for $uri", failure)
    }
    values.clear()
    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    return uri
}
