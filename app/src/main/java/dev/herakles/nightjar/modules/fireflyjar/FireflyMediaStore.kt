package dev.herakles.nightjar.modules.fireflyjar

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * App-private on-disk store for persisted carrier media.
 *
 * Backs every byte with `File(context.filesDir, "fireflies")` — deliberately NOT `MediaStore`
 * and never `Environment.getExternalStorageDirectory()`. Implicit retention on every firefly
 * catch must stay app-private; spraying files into the user's Pictures/Music gallery on every
 * catch would violate that guarantee. Writing to shared storage is reserved for the technical screens'
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
     *
     * Refuses to write -- throwing [InsufficientStorageException] -- if fewer than
     * [MIN_FREE_SPACE_BYTES] would remain on the partition afterward. This is a device-free-space
     * FLOOR, not a total-received-bytes CEILING: it
     * never refuses a write while the device genuinely has room, so it never becomes a retention
     * policy of its own -- "retention is user-managed" (`STORAGE_WARNING_THRESHOLD_BYTES`
     * in `JarShelfScreen.kt` is deliberately advisory-only, "never gates catching") stays intact
     * for ordinary usage. What this guards against is different: a peer flooding the receive
     * pipeline with many distinct carriers (content-addressing only dedupes byte-identical ones)
     * driving the device to ENOSPC mid-write, which without this check would surface as a bare
     * `IOException` after a partial write rather than a clean refusal before one.
     */
    fun write(bytes: ByteArray, extension: String): String {
        val filename = "${sha256Hex(bytes)}.$extension"
        val file = File(directory, filename)
        if (file.exists() && file.length() == bytes.size.toLong()) return filename
        // Checked against `directory`, not `file`: `File.usableSpace` is 0 for a path that
        // doesn't yet exist and "does not name a partition" on some JVM/filesystem combinations
        // (confirmed empirically under this project's Robolectric test harness) -- `directory`
        // is always real by this point ([directory]'s own getter `mkdirs()`s it on every access).
        val usable = directory.usableSpace
        if (usable < bytes.size.toLong() + MIN_FREE_SPACE_BYTES) {
            throw InsufficientStorageException(
                "writing ${bytes.size} bytes would leave under ${MIN_FREE_SPACE_BYTES}B free " +
                    "($usable available)",
            )
        }
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
     * Crash-recovery sweep for the gap between writing a media file and inserting its
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

    /**
     * Counts files in the fireflies directory that are NOT in [known] — how many carrier files
     * nothing in the database currently references. Purely a read: never deletes anything,
     * unlike [sweepOrphans]. Backs [dev.herakles.nightjar.DebugProbe]'s `orphan_file_count`
     * (v4 probe contract) via [FireflyRepository.probeSnapshot].
     *
     * **Deliberately NOT age-gated the way [sweepOrphans] is**, and that's a real difference in
     * what the two report, not an oversight: [sweepOrphans]' [MIN_ORPHAN_AGE_MILLIS] floor exists
     * to protect a file that's moments from gaining its row -- the write-then-insert gap
     * [FireflyRepository.insertWithMedia]'s own KDoc documents. A file inside that gap has no
     * referencing row *yet*, so this method counts it, even though [sweepOrphans] would correctly
     * leave it alone if run at that same instant. That means a probe snapshot taken mid-catch can
     * read one higher than what the sweep would actually reclaim right then -- a real, possible
     * value, not a bug.
     *
     * That tradeoff is intentional: this is a read-only diagnostic, not a deletion decision, and
     * the two failure directions aren't symmetric. Silently under-reporting a genuine crash orphan
     * because it happens to be a few seconds younger than [MIN_ORPHAN_AGE_MILLIS] would let a real
     * "no orphans" assertion pass falsely, which defeats the entire point of exposing this as
     * queryable state. An occasional +1 during a normal
     * catch's sub-millisecond write-then-insert window is the far cheaper cost. A caller that
     * specifically wants "how many would the sweep reclaim if it ran right now" would need to
     * apply the same age filter itself; nothing here currently needs that narrower question
     * answered.
     */
    fun countUnreferenced(known: Set<String>): Int {
        val candidates = directory.listFiles() ?: return 0
        return candidates.count { it.name !in known }
    }

    companion object {
        /**
         * How old an unreferenced file must be before the sweep will reclaim it. Comfortably
         * longer than the widest write-then-insert gap (a ~1.9 MB acoustic capture) and far
         * shorter than the gap between app launches, which is when real orphans are found.
         */
        internal const val MIN_ORPHAN_AGE_MILLIS = 5 * 60 * 1000L

        /** [write]'s free-space floor -- see that
         *  function's KDoc for why this is a floor, not a ceiling. */
        internal const val MIN_FREE_SPACE_BYTES = 20L * 1024 * 1024
    }
}

/** Thrown by [FireflyMediaStore.write] when writing would leave the partition below
 *  [FireflyMediaStore.MIN_FREE_SPACE_BYTES] free. A
 *  subclass of [java.io.IOException] so every existing `catch (e: IOException)`/`catch (e:
 *  Exception)` boundary around a catch-site's own persist call already handles it -- most
 *  directly, [dev.herakles.nightjar.incoming.IncomingPipeline]'s own [Throwable] boundary. */
class InsufficientStorageException(message: String) : java.io.IOException(message)
