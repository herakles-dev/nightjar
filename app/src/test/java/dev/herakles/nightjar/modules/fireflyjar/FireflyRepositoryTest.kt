package dev.herakles.nightjar.modules.fireflyjar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Verification that [FireflyRepository] makes a firefly's row and its
 * attached media file inseparable. Uses the same Robolectric setup as [FireflyLogTest] (in-memory
 * Room database) and [FireflyMediaStoreTest] (real [FireflyMediaStore] against `filesDir`), so
 * every assertion here exercises the real DAO and the real on-disk store together, not mocks of
 * either.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FireflyRepositoryTest {

    private lateinit var database: FireflyDatabase
    private lateinit var dao: FireflyDao
    private lateinit var mediaStore: FireflyMediaStore
    private lateinit var repository: FireflyRepository
    private lateinit var directory: File

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, FireflyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.fireflyDao()
        directory = File(context.filesDir, "fireflies")
        directory.deleteRecursively()
        mediaStore = FireflyMediaStore(context)
        repository = FireflyRepository(dao, mediaStore)
    }

    @After
    fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    private fun record(
        moduleId: String = "IMAGE_LSB",
        timestampMillis: Long = 1_000L,
        mediaPath: String? = null,
        mediaBytes: Long = 0,
        // Lets insertWithMedia() callers set carrierKind up front, independent of mediaPath --
        // the repository fills mediaPath in itself, so it's never known by the caller yet.
        carrierOverride: String? = null,
    ) = FireflyRecord(
        moduleId = moduleId,
        direction = "ENCODE",
        timestampMillis = timestampMillis,
        payloadSizeBytes = 16,
        technique = "LSB",
        payloadPreview = "preview",
        carrierKind = carrierOverride ?: if (mediaPath != null) "IMAGE" else null,
        mediaPath = mediaPath,
        mediaBytes = mediaBytes,
    )

    // --- clearAll() ---

    @Test
    fun clearAllEmptiesBothTheTableAndTheFirefliesDirectory() = runBlocking {
        val firstFilename = mediaStore.write(byteArrayOf(1, 2, 3), "png")
        val secondFilename = mediaStore.write(byteArrayOf(4, 5, 6), "wav")
        dao.insert(record(timestampMillis = 1L, mediaPath = firstFilename, mediaBytes = 3))
        dao.insert(record(timestampMillis = 2L, mediaPath = secondFilename, mediaBytes = 3))
        assertEquals(2, dao.observeAll().first().size)
        assertEquals(2, directory.listFiles()?.size)

        repository.clearAll()

        assertTrue("table must be empty", dao.observeAll().first().isEmpty())
        assertTrue("fireflies directory must be empty", directory.listFiles()?.isEmpty() ?: true)
    }

    // --- deleteFirefly(id) ---

    @Test
    fun deleteFireflyRemovesExactlyOneRowAndItsFileAndLeavesTheOtherIntact() = runBlocking {
        val doomedFilename = mediaStore.write(byteArrayOf(9, 9, 9), "png")
        val keptFilename = mediaStore.write(byteArrayOf(1, 1, 1), "png")
        dao.insert(record(timestampMillis = 1L, mediaPath = doomedFilename, mediaBytes = 3))
        dao.insert(record(timestampMillis = 2L, mediaPath = keptFilename, mediaBytes = 3))
        val doomedId = dao.observeAll().first().single { it.mediaPath == doomedFilename }.id

        repository.deleteFirefly(doomedId)

        val remaining = dao.observeAll().first()
        assertEquals(1, remaining.size)
        assertEquals(keptFilename, remaining.single().mediaPath)
        assertFalse("doomed file must be deleted", File(directory, doomedFilename).exists())
        assertTrue("kept file must remain", File(directory, keptFilename).exists())
    }

    @Test
    fun deleteFireflyWithNullMediaPathRemovesTheRowWithoutThrowing() = runBlocking {
        // Pre-migration firefly: mediaPath is null. A naive mediaStore.delete(record.mediaPath!!)
        // would crash on the first one a user tries to delete.
        dao.insert(record(timestampMillis = 1L, mediaPath = null))
        val id = dao.observeAll().first().single().id

        repository.deleteFirefly(id)

        assertTrue(dao.observeAll().first().isEmpty())
    }

    // --- sweepOrphans() ---

    @Test
    fun sweepOrphansDeletesStrayFileAndKeepsFilesNamedByLiveRows() = runBlocking {
        val liveFilename = mediaStore.write(byteArrayOf(7, 7), "png")
        dao.insert(record(timestampMillis = 1L, mediaPath = liveFilename, mediaBytes = 2))
        // Hand-plant a stray file the repository never wrote via a record insert -- simulates a
        // crash between a media write and its owning FireflyRecord insert.
        val strayFile = File(directory, "orphan-stray.png")
        strayFile.writeBytes(byteArrayOf(8, 8))
        assertTrue(strayFile.exists())
        // Backdated past the sweep's age floor -- a real crash orphan comes from an earlier
        // process. Recent files are deliberately protected so an in-flight catch cannot be
        // swept between the repository's database read and the directory listing.
        strayFile.setLastModified(
            System.currentTimeMillis() - FireflyMediaStore.MIN_ORPHAN_AGE_MILLIS - 60_000L,
        )

        repository.sweepOrphans()

        assertFalse("stray file must be deleted", strayFile.exists())
        assertTrue("file named by a live row must be kept", File(directory, liveFilename).exists())
    }

    // --- dedup + reference counting, end to end (tasks #23 + #24) ---

    /**
     * The scenario both features exist for, exercised through the real repository rather than by
     * hand-planting a shared mediaPath.
     *
     * Observed on device: embedding a payload and then extracting it produced two fireflies whose
     * carriers were byte-identical. Content-addressing collapses those onto one file, which is
     * only safe because deletion is reference-counted — without that, deleting either firefly
     * would destroy the other's carrier and leave a live row pointing at nothing, silently.
     */
    @Test
    fun identicalCarriersShareOneFileAndDeletingOneFireflyLeavesTheOtherPlayable() = runBlocking {
        val bytes = byteArrayOf(5, 5, 5, 5, 5, 5)
        repository.insertWithMedia(record(timestampMillis = 1L, carrierOverride = "AUDIO"), bytes, "wav")
        repository.insertWithMedia(record(timestampMillis = 2L, carrierOverride = "AUDIO"), bytes, "wav")

        val rows = dao.observeAll().first()
        assertEquals(2, rows.size)
        assertEquals("identical carriers must collapse onto one file", rows[0].mediaPath, rows[1].mediaPath)
        assertEquals(1, directory.listFiles()?.size)

        repository.deleteFirefly(rows.first().id)

        val survivor = dao.observeAll().first().single()
        assertEquals(1, directory.listFiles()?.size)
        assertArrayEquals(
            "the surviving firefly's carrier must still be readable",
            bytes,
            mediaStore.read(survivor.mediaPath!!),
        )

        repository.deleteFirefly(survivor.id)

        assertTrue(
            "the last referrer going away finally reclaims the file",
            directory.listFiles()?.isEmpty() ?: true,
        )
    }

    // --- insertWithMedia(record, media, extension) / insert(record) ---

    @Test
    fun insertWithMediaWritesExactlyOneFileAndOneRowThatNameEachOther() = runBlocking {
        val bytes = byteArrayOf(11, 22, 33, 44)

        repository.insertWithMedia(
            record(moduleId = "AUDIO_ECHO", carrierOverride = "AUDIO"),
            bytes,
            "wav",
        )

        val rows = dao.observeAll().first()
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("AUDIO", row.carrierKind)
        assertEquals(bytes.size.toLong(), row.mediaBytes)
        val mediaPath = row.mediaPath
        assertTrue("mediaPath must be set", mediaPath != null)
        val files = directory.listFiles()
        assertEquals(1, files?.size)
        assertEquals(mediaPath, files!!.single().name)
        assertTrue(bytes.contentEquals(mediaStore.read(mediaPath!!)))
    }

    @Test
    fun insertWithMediaRollsBackTheFileWhenTheInsertThrows() = runBlocking {
        // Real FireflyMediaStore (so the write is genuine), but a FireflyDao whose insert()
        // always throws -- a stub is the cleanest way to force a deterministic, empty-table
        // failure independent of any pre-existing row/constraint state. Every other DAO method
        // still delegates to the real Room-backed [dao], so verification below reads real state.
        val failingRepository = FireflyRepository(FailingInsertDao(dao), mediaStore)
        val bytes = byteArrayOf(5, 6, 7)

        try {
            failingRepository.insertWithMedia(record(carrierOverride = "IMAGE"), bytes, "png")
            org.junit.Assert.fail("expected insertWithMedia to rethrow the DAO failure")
        } catch (expected: IllegalStateException) {
            // expected -- FailingInsertDao.insert always throws this
        }

        assertTrue("no file may survive a failed insert", directory.listFiles()?.isEmpty() ?: true)
        assertTrue("no row may survive a failed insert", dao.observeAll().first().isEmpty())
    }

    @Test
    fun insertLeavesANullMediaPathRowAndWritesNoFile() = runBlocking {
        repository.insert(record(timestampMillis = 1L))

        val rows = dao.observeAll().first()
        assertEquals(1, rows.size)
        assertNull(rows.single().mediaPath)
        assertTrue("media-less insert must not touch disk", directory.listFiles()?.isEmpty() ?: true)
    }

    /** Delegates every [FireflyDao] method to [delegate] except [insert], which always throws. */
    private class FailingInsertDao(private val delegate: FireflyDao) : FireflyDao by delegate {
        override suspend fun insert(record: FireflyRecord): Long {
            throw IllegalStateException("forced insert failure for test")
        }
    }

    // --- reference-counted delete ---
    //
    // Dedup does not exist yet, so these tests simulate a shared mediaPath by
    // inserting two rows through the DAO directly with the same filename -- exactly the
    // situation content-hashed filenames will produce for real.

    @Test
    fun deletingFirstOfTwoSharersKeepsTheFileAndTheOtherRowStaysReadable() = runBlocking {
        val sharedFilename = mediaStore.write(byteArrayOf(1, 2, 3), "png")
        dao.insert(record(timestampMillis = 1L, mediaPath = sharedFilename, mediaBytes = 3))
        dao.insert(record(timestampMillis = 2L, mediaPath = sharedFilename, mediaBytes = 3))
        val rows = dao.observeAll().first()
        val firstId = rows.single { it.timestampMillis == 1L }.id

        repository.deleteFirefly(firstId)

        val remaining = dao.observeAll().first()
        assertEquals(1, remaining.size)
        assertEquals(sharedFilename, remaining.single().mediaPath)
        assertTrue("file must survive -- the second row still names it", File(directory, sharedFilename).exists())
        assertTrue(
            "second row's media must still be readable",
            byteArrayOf(1, 2, 3).contentEquals(mediaStore.read(sharedFilename)),
        )
    }

    @Test
    fun deletingSecondOfTwoSharersThenRemovesTheFile() = runBlocking {
        val sharedFilename = mediaStore.write(byteArrayOf(1, 2, 3), "png")
        dao.insert(record(timestampMillis = 1L, mediaPath = sharedFilename, mediaBytes = 3))
        dao.insert(record(timestampMillis = 2L, mediaPath = sharedFilename, mediaBytes = 3))
        val rows = dao.observeAll().first()
        val firstId = rows.single { it.timestampMillis == 1L }.id
        val secondId = rows.single { it.timestampMillis == 2L }.id

        repository.deleteFirefly(firstId)
        assertTrue("file must still survive after only one of two sharers is deleted", File(directory, sharedFilename).exists())
        repository.deleteFirefly(secondId)

        assertTrue("table must be empty", dao.observeAll().first().isEmpty())
        assertFalse("file must be deleted once the last sharer is gone", File(directory, sharedFilename).exists())
    }

    @Test
    fun rollbackWithAPreExistingSharerMustNotDeleteTheOtherRowsFile() = runBlocking {
        // Reproduces "write() handed back an existing, shared filename (post-#24 dedup), and the
        // row insert then failed" -- WITHOUT needing to control FireflyMediaStore's UUID
        // generation. SharingThenFailingInsertDao sees the real filename insertWithMedia just
        // stamped onto the record (it's in `record.mediaPath` by the time insert() is called),
        // plants a genuine sibling row under that SAME filename via the real dao, THEN throws.
        // That reproduces the collision exactly: two live rows would share the file at the
        // moment the insert failed.
        val sharingRepository = FireflyRepository(SharingThenFailingInsertDao(dao), mediaStore)
        val bytes = byteArrayOf(9, 9, 9)

        try {
            sharingRepository.insertWithMedia(record(carrierOverride = "IMAGE"), bytes, "png")
            org.junit.Assert.fail("expected insertWithMedia to rethrow the DAO failure")
        } catch (expected: IllegalStateException) {
            // expected -- SharingThenFailingInsertDao.insert always throws this
        }

        val remaining = dao.observeAll().first()
        assertEquals("the sibling row planted by the fake DAO must survive", 1, remaining.size)
        val sharedPath = remaining.single().mediaPath!!
        assertTrue("the shared file must survive a failed rollback", File(directory, sharedPath).exists())
        assertTrue(
            "the shared file's bytes must be untouched",
            bytes.contentEquals(mediaStore.read(sharedPath)),
        )
    }

    /**
     * Plants a real sibling row under [record]'s `mediaPath` (already the genuine
     * [FireflyMediaStore]-written filename by the time [insert] is called -- see
     * [FireflyRepository.insertWithMedia]) via [delegate], then always throws. Used only by
     * [rollbackWithAPreExistingSharerMustNotDeleteTheOtherRowsFile].
     */
    private class SharingThenFailingInsertDao(private val delegate: FireflyDao) : FireflyDao by delegate {
        override suspend fun insert(record: FireflyRecord): Long {
            delegate.insert(record.copy(payloadPreview = "pre-existing sharer"))
            throw IllegalStateException("forced insert failure for test")
        }
    }

    // --- probeSnapshot() (v4 probe contract) ---

    @Test
    fun probeSnapshotOnAnEmptyRepositoryIsAllZeros() = runBlocking {
        val snapshot = repository.probeSnapshot()

        assertEquals(0, snapshot.recordCount)
        assertEquals(0, snapshot.recordsWithMediaCount)
        assertEquals(0L, snapshot.totalMediaBytes)
        assertEquals(0, snapshot.orphanFileCount)
    }

    @Test
    fun probeSnapshotCountsRecordsAndMediaBytesTogether() = runBlocking {
        // A media-bearing firefly (counted in recordsWithMediaCount + totalMediaBytes)...
        repository.insertWithMedia(
            record(timestampMillis = 1L, carrierOverride = "IMAGE"),
            byteArrayOf(1, 2, 3),
            "png",
        )
        // ...and a media-less firefly (counted only in recordCount).
        repository.insert(record(timestampMillis = 2L))

        val snapshot = repository.probeSnapshot()

        assertEquals(2, snapshot.recordCount)
        assertEquals(1, snapshot.recordsWithMediaCount)
        assertEquals(3L, snapshot.totalMediaBytes)
    }

    // --- probeSnapshot().orphanFileCount (real count via
    // FireflyMediaStore.countUnreferenced, not a fabricated/hardcoded 0) ---

    @Test
    fun probeSnapshotOrphanFileCountIsZeroAfterClearAll() = runBlocking {
        repository.insertWithMedia(
            record(timestampMillis = 1L, carrierOverride = "IMAGE"),
            byteArrayOf(1, 2, 3),
            "png",
        )

        repository.clearAll()

        assertEquals(0, repository.probeSnapshot().orphanFileCount)
    }

    @Test
    fun probeSnapshotOrphanFileCountIsZeroAfterDeletingOneOfTwoSharersOfAStillReferencedFile() = runBlocking {
        val bytes = byteArrayOf(5, 5, 5)
        // Content-addressed write: both inserts collapse onto the same on-disk file.
        repository.insertWithMedia(record(timestampMillis = 1L, carrierOverride = "IMAGE"), bytes, "png")
        repository.insertWithMedia(record(timestampMillis = 2L, carrierOverride = "IMAGE"), bytes, "png")
        val doomedId = dao.observeAll().first().first().id

        repository.deleteFirefly(doomedId)

        // The shared file must survive (the surviving row still references it), so it must NOT
        // count as unreferenced -- this is exactly the reference-counted-delete guarantee
        // (FireflyRepository.deleteMediaFileIfUnreferenced) reflected in the probe.
        assertEquals(0, repository.probeSnapshot().orphanFileCount)
    }

    @Test
    fun probeSnapshotOrphanFileCountIsNAfterNFilesAreWrittenWithNoRows() = runBlocking {
        mediaStore.write(byteArrayOf(1), "png")
        mediaStore.write(byteArrayOf(2), "png")
        mediaStore.write(byteArrayOf(3), "png")
        // No dao.insert()/repository.insert() call for any of the three above -- genuine
        // unreferenced files, the exact shape a crash between a media write and its row insert
        // leaves behind (FireflyRepository.insertWithMedia's own KDoc).

        assertEquals(3, repository.probeSnapshot().orphanFileCount)
    }

    @Test
    fun deletingASoleReferenceFileStillDeletesItLikeBeforeReferenceCounting() = runBlocking {
        val filename = mediaStore.write(byteArrayOf(4, 5, 6), "png")
        dao.insert(record(timestampMillis = 1L, mediaPath = filename, mediaBytes = 3))
        val id = dao.observeAll().first().single().id

        repository.deleteFirefly(id)

        assertTrue(dao.observeAll().first().isEmpty())
        assertFalse("sole-reference file must still be deleted", File(directory, filename).exists())
    }
}
