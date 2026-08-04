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
}
