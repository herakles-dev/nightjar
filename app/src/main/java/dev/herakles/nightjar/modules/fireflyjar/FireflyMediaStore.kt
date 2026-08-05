package dev.herakles.nightjar.modules.fireflyjar

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * App-private on-disk store for persisted carrier media (gate-16/gate-20, INV-5/INV-6).
 *
 * Backs every byte with `File(context.filesDir, "fireflies")` — deliberately NOT `MediaStore`
 * and never `Environment.getExternalStorageDirectory()`. Implicit retention on every firefly
 * catch must stay app-private; spraying files into the user's Pictures/Music gallery on every
 * catch would violate INV-5. Writing to shared storage is reserved for the technical screens'
 * explicit, user-invoked save/share gesture — a separate code path this class has no part in.
 *
 * [write] returns a bare FILENAME, never an absolute path: `filesDir` moves between installs, so
 * an absolute path stored in the [FireflyRecord.mediaPath] column would dangle after a reinstall.
 * Callers persist the filename and re-resolve it against `filesDir` on every access.
 */
class FireflyMediaStore(private val context: Context) {

    /**
     * Resolved and ensured on every access, not cached in a `by lazy`.
     *
     * A lazy would call `mkdirs()` exactly once per instance, so anything that removed the
     * directory afterwards would leave [write] calling `writeBytes` against a missing parent and
     * throwing `FileNotFoundException`. `mkdirs()` on an existing directory is a cheap no-op, and
     * these operations are already doing disk I/O.
     */
    private val directory: File
        get() = File(context.filesDir, "fireflies").apply { mkdirs() }

    /**
     * Writes [bytes] under a content-addressed name (SHA-256 of the bytes + [extension]) and
     * returns that FILENAME. Identical carriers therefore collapse onto a single file.
     *
     * This is not a micro-optimisation: on device, embedding a payload and then extracting it
     * stored the same 5-second clip twice under two random names, so the jar grew at roughly
     * twice the rate a user would expect for what they could hear.
     *
     * An existing file is reused only when its length also matches. A same-named file of a
     * different length can only be a truncated leftover from a process that died mid-write, so
     * rewriting it is the self-healing branch rather than a redundant one.
     *
     * Because one file can now back several rows, deletion MUST stay reference-counted —
     * see [FireflyRepository]'s `deleteMediaFileIfUnreferenced`. Reverting that would turn
     * deleting one firefly into the silent destruction of another's carrier.
     */
    fun write(bytes: ByteArray, extension: String): String {
        val filename = "${sha256Hex(bytes)}.$extension"
        val file = File(directory, filename)
        if (file.exists() && file.length() == bytes.size.toLong()) return filename
        file.writeBytes(bytes)
        return filename
    }

    /**
     * Hashes on the caller's thread deliberately. All three catch sites already call this from
     * `withContext(Dispatchers.Default)`, so an extra dispatcher hop here would buy nothing and
     * hide where the cost actually lives.
     */
    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** Reads the file named [filename] from the fireflies directory, or null if it doesn't exist. */
    fun read(filename: String): ByteArray? {
        val file = File(directory, filename)
        return if (file.exists()) file.readBytes() else null
    }

    /** Deletes the file named [filename]. A missing file is a no-op, not an error. */
    fun delete(filename: String) {
        File(directory, filename).delete()
    }

    /** Deletes every file in the fireflies directory. */
    fun deleteAll() {
        directory.listFiles()?.forEach { it.delete() }
    }

    /**
     * Deletes files in the fireflies directory that are NOT in [known] and are older than
     * [MIN_ORPHAN_AGE_MILLIS].
     *
     * Crash-recovery sweep (INV-6) for the gap between writing a media file and inserting its
     * owning [FireflyRecord] row: if the process dies in between, the file is orphaned with no
     * row referencing it. [known] should be the full set of `mediaPath` values currently in the
     * database — anything on disk outside that set is unreferenced and safe to reclaim.
     *
     * The age floor is what makes this safe against an in-flight catch. The caller reads the
     * database and then calls this, so a catch completing between those two steps writes a file
     * that is on disk but absent from [known] — and sweeping it would strand a live row with no
     * carrier and no error. A file that new is seconds old; a genuine crash orphan was stranded
     * by a previous process and is therefore old. Skipping recent files closes the window
     * without needing a lock.
     */
    fun sweepOrphans(known: Set<String>) {
        // Listed before any age comparison so `now` can never precede a candidate's timestamp.
        val candidates = directory.listFiles() ?: return
        val now = System.currentTimeMillis()
        candidates.forEach { file ->
            if (file.name !in known && now - file.lastModified() >= MIN_ORPHAN_AGE_MILLIS) {
                file.delete()
            }
        }
    }

    companion object {
        /**
         * How old an unreferenced file must be before the sweep will reclaim it. Comfortably
         * longer than the widest write-then-insert gap (a ~1.9 MB acoustic capture) and far
         * shorter than the gap between app launches, which is when real orphans are found.
         */
        internal const val MIN_ORPHAN_AGE_MILLIS = 5 * 60 * 1000L
    }
}
