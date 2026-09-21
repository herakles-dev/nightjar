package dev.herakles.nightjar.modules.fireflyjar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verification for task #5 (Firefly Jar DAO, gate-11). Uses an in-memory Room database
 * (Robolectric-backed Context, matching the ImageStegoCarrierTest convention) so each test
 * exercises the real Room-generated DAO implementation without touching the on-disk
 * firefly_jar.db.
 *
 * Covers [FireflyDao]'s 4 operations: insert, observeByModule, observeAll, clearAll.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FireflyLogTest {

    private lateinit var database: FireflyDatabase
    private lateinit var dao: FireflyDao

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, FireflyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.fireflyDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun record(
        moduleId: String,
        direction: String = "ENCODE",
        timestampMillis: Long = 1_000L,
        payloadSizeBytes: Int = 16,
        technique: String? = "LSB",
        payloadPreview: String? = "preview",
    ) = FireflyRecord(
        moduleId = moduleId,
        direction = direction,
        timestampMillis = timestampMillis,
        payloadSizeBytes = payloadSizeBytes,
        technique = technique,
        payloadPreview = payloadPreview,
    )

    // --- insert() ---

    @Test
    fun insertedRecordIsRetrievable() = runBlocking {
        val inserted = record(moduleId = "IMAGE_LSB", direction = "ENCODE", timestampMillis = 42L, payloadSizeBytes = 128)

        dao.insert(inserted)

        val all = dao.observeAll().first()
        assertEquals(1, all.size)
        val retrieved = all.single()
        assertEquals(inserted.moduleId, retrieved.moduleId)
        assertEquals(inserted.direction, retrieved.direction)
        assertEquals(inserted.timestampMillis, retrieved.timestampMillis)
        assertEquals(inserted.payloadSizeBytes, retrieved.payloadSizeBytes)
        assertEquals(inserted.technique, retrieved.technique)
        assertEquals(inserted.payloadPreview, retrieved.payloadPreview)
    }

    // --- observeByModule(moduleId) ---

    @Test
    fun observeByModuleReturnsOnlyMatchingModuleRecords() = runBlocking {
        dao.insert(record(moduleId = "IMAGE_LSB", timestampMillis = 1L))
        dao.insert(record(moduleId = "IMAGE_LSB", timestampMillis = 2L))
        dao.insert(record(moduleId = "AUDIO_SPREAD_SPECTRUM", timestampMillis = 3L))
        dao.insert(record(moduleId = "ACOUSTIC_MODEM", timestampMillis = 4L))

        val imageRecords = dao.observeByModule("IMAGE_LSB").first()

        assertEquals(2, imageRecords.size)
        assertTrue(imageRecords.all { it.moduleId == "IMAGE_LSB" })
    }

    @Test
    fun observeByModuleReturnsEmptyWhenNoRecordsMatch() = runBlocking {
        dao.insert(record(moduleId = "IMAGE_LSB", timestampMillis = 1L))

        val acousticRecords = dao.observeByModule("ACOUSTIC_MODEM").first()

        assertTrue(acousticRecords.isEmpty())
    }

    // --- observeAll() ---

    @Test
    fun observeAllReturnsRecordsAcrossAllModules() = runBlocking {
        dao.insert(record(moduleId = "IMAGE_LSB", timestampMillis = 1L))
        dao.insert(record(moduleId = "AUDIO_SPREAD_SPECTRUM", timestampMillis = 2L))
        dao.insert(record(moduleId = "ACOUSTIC_MODEM", timestampMillis = 3L))

        val all = dao.observeAll().first()

        assertEquals(3, all.size)
        val moduleIds = all.map { it.moduleId }.toSet()
        assertEquals(setOf("IMAGE_LSB", "AUDIO_SPREAD_SPECTRUM", "ACOUSTIC_MODEM"), moduleIds)
    }

    // --- clearAll() ---

    @Test
    fun clearAllEmptiesTheTable() = runBlocking {
        dao.insert(record(moduleId = "IMAGE_LSB", timestampMillis = 1L))
        dao.insert(record(moduleId = "AUDIO_SPREAD_SPECTRUM", timestampMillis = 2L))
        assertEquals(2, dao.observeAll().first().size)

        dao.clearAll()

        assertTrue(dao.observeAll().first().isEmpty())
    }

    // --- observeTotalMediaBytes() (G-04, gate-20 Stage E prerequisite) ---

    private fun recordWithMedia(
        timestampMillis: Long,
        mediaPath: String?,
        mediaBytes: Long,
    ) = record(moduleId = "IMAGE_LSB", timestampMillis = timestampMillis).copy(
        carrierKind = if (mediaPath != null) "IMAGE" else null,
        mediaPath = mediaPath,
        mediaBytes = mediaBytes,
    )

    @Test
    fun observeTotalMediaBytesOnAnEmptyTableIsZeroNotNull() = runBlocking {
        assertEquals(0L, dao.observeTotalMediaBytes().first())
    }

    @Test
    fun observeTotalMediaBytesSumsMediaBytesAcrossDistinctRows() = runBlocking {
        dao.insert(recordWithMedia(timestampMillis = 1L, mediaPath = "a.png", mediaBytes = 100L))
        dao.insert(recordWithMedia(timestampMillis = 2L, mediaPath = "b.wav", mediaBytes = 250L))
        // Media-less row -- must not contribute a phantom 0 that masks a real bug, and must not throw.
        dao.insert(recordWithMedia(timestampMillis = 3L, mediaPath = null, mediaBytes = 0L))

        assertEquals(350L, dao.observeTotalMediaBytes().first())
    }

    /**
     * Content-addressed dedup ([FireflyMediaStore.write]) means several rows can share one
     * on-disk file. A naive `SUM(mediaBytes)` over every ROW would double-count that shared
     * file's bytes once per referencing row -- exactly the overstatement dedup exists to remove.
     * The DISTINCT-`(mediaPath, mediaBytes)` query must count the shared file exactly once.
     */
    @Test
    fun observeTotalMediaBytesCountsASharedMediaPathOnlyOnce() = runBlocking {
        dao.insert(recordWithMedia(timestampMillis = 1L, mediaPath = "shared.wav", mediaBytes = 480_000L))
        dao.insert(recordWithMedia(timestampMillis = 2L, mediaPath = "shared.wav", mediaBytes = 480_000L))
        dao.insert(recordWithMedia(timestampMillis = 3L, mediaPath = "other.png", mediaBytes = 5_000L))

        assertEquals(485_000L, dao.observeTotalMediaBytes().first())
    }

    // --- countAll() / countWithMedia() (G-05, gate-20 v4 probe contract) ---

    @Test
    fun countAllAndCountWithMediaOnAnEmptyTableAreBothZero() = runBlocking {
        assertEquals(0, dao.countAll())
        assertEquals(0, dao.countWithMedia())
    }

    @Test
    fun countAllAndCountWithMediaCountRowsNotDistinctFiles() = runBlocking {
        // Same shared mediaPath as observeTotalMediaBytesCountsASharedMediaPathOnlyOnce -- these
        // two counts are ROW counts (record_count / records_with_media_count in the probe dump),
        // deliberately NOT deduplicated by file the way observeTotalMediaBytes is.
        dao.insert(recordWithMedia(timestampMillis = 1L, mediaPath = "shared.wav", mediaBytes = 480_000L))
        dao.insert(recordWithMedia(timestampMillis = 2L, mediaPath = "shared.wav", mediaBytes = 480_000L))
        dao.insert(recordWithMedia(timestampMillis = 3L, mediaPath = null, mediaBytes = 0L))

        assertEquals(3, dao.countAll())
        assertEquals(2, dao.countWithMedia())
    }
}
