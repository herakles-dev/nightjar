package dev.herakles.nightjar.modules.fireflyjar

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Verification for the v1 -> v2 Room migration on [FireflyDatabase] that
 * adds `carrierKind`, `mediaPath`, `mediaBytes` to `firefly_records`.
 *
 * The v1 schema is version 1 in production with no migration and no destructive fallback — a
 * wrong move here throws at open for every user holding fireflies. This test builds a real
 * on-disk v1 database by hand (in-memory would never exercise a migration path), then opens it
 * through [FireflyDatabase] with [MIGRATION_1_2] applied. Room's own post-migration schema
 * validation (triggered on open) is the assertion that matters most: if the migrated table
 * disagrees with the [FireflyRecord] entity on column type/nullability/default, Room throws
 * before this test gets a chance to run its own asserts.
 *
 * No androidTest source set exists in this project, so MigrationTestHelper is not available.
 * This uses the same Robolectric 4.13 setup as [FireflyLogTest], but against a persistent file
 * (via context.getDatabasePath) instead of an in-memory database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FireflyMigration1To2Test {

    private companion object {
        /** identityHash from app/schemas/.../1.json — what a real v1 database on disk carries. */
        const val V1_IDENTITY_HASH = "55f340ddfb54673b82f5dac2d6d5095f"
    }

    private lateinit var context: Context
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath("firefly_migration_test.db")
        deleteDatabaseFiles()
    }

    @After
    fun tearDown() {
        deleteDatabaseFiles()
    }

    private fun deleteDatabaseFiles() {
        dbFile.delete()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        File(dbFile.path + "-journal").delete()
    }

    /**
     * Hand-builds a v1 `firefly_records` table matching app/schemas/.../1.json verbatim.
     *
     * This also writes `room_master_table` holding the v1 identity hash, because a database Room
     * actually created on a user's phone has it. Its presence changes which branch Room takes on
     * open (identity verification rather than the "unknown pre-populated database" validate-and-
     * adopt path), so omitting it would test a path no real user is on.
     */
    private fun buildV1Database() {
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `firefly_records` (`id` INTEGER PRIMARY KEY " +
                "AUTOINCREMENT NOT NULL, `moduleId` TEXT NOT NULL, `direction` TEXT NOT NULL, " +
                "`timestampMillis` INTEGER NOT NULL, `payloadSizeBytes` INTEGER NOT NULL, " +
                "`technique` TEXT, `payloadPreview` TEXT)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS room_master_table " +
                "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, ?)",
            arrayOf(V1_IDENTITY_HASH),
        )
        db.version = 1

        db.insert(
            "firefly_records",
            null,
            ContentValues().apply {
                put("moduleId", "IMAGE_LSB")
                put("direction", "ENCODE")
                put("timestampMillis", 1_000L)
                put("payloadSizeBytes", 128)
                put("technique", "LSB")
                put("payloadPreview", "hello-preview")
            },
        )
        db.insert(
            "firefly_records",
            null,
            ContentValues().apply {
                put("moduleId", "AUDIO_SPREAD_SPECTRUM")
                put("direction", "DECODE")
                put("timestampMillis", 2_000L)
                put("payloadSizeBytes", 512)
                putNull("technique")
                putNull("payloadPreview")
            },
        )
        db.close()
    }

    @Test
    fun migrationFrom1To2PreservesRowsAndAppliesDefaults() = runBlocking {
        buildV1Database()

        // Room validates the post-migration table against the FireflyRecord entity on open —
        // a column-type or nullability mismatch throws here rather than reaching a user.
        val database = Room.databaseBuilder(context, FireflyDatabase::class.java, dbFile.name)
            .addMigrations(MIGRATION_1_2)
            .build()
        val dao = database.fireflyDao()

        val all = dao.observeAll().first()
        assertEquals(2, all.size)

        val imageRecord = all.single { it.moduleId == "IMAGE_LSB" }
        assertEquals("ENCODE", imageRecord.direction)
        assertEquals(1_000L, imageRecord.timestampMillis)
        assertEquals(128, imageRecord.payloadSizeBytes)
        assertEquals("LSB", imageRecord.technique)
        assertEquals("hello-preview", imageRecord.payloadPreview)
        assertNull(imageRecord.carrierKind)
        assertNull(imageRecord.mediaPath)
        assertEquals(0L, imageRecord.mediaBytes)

        val audioRecord = all.single { it.moduleId == "AUDIO_SPREAD_SPECTRUM" }
        assertEquals("DECODE", audioRecord.direction)
        assertEquals(2_000L, audioRecord.timestampMillis)
        assertEquals(512, audioRecord.payloadSizeBytes)
        assertNull(audioRecord.technique)
        assertNull(audioRecord.payloadPreview)
        assertNull(audioRecord.carrierKind)
        assertNull(audioRecord.mediaPath)
        assertEquals(0L, audioRecord.mediaBytes)

        // A fresh v2 insert round-trips all nine fields, including the three new ones.
        val fresh = FireflyRecord(
            moduleId = "ACOUSTIC_MODEM",
            direction = "ENCODE",
            timestampMillis = 3_000L,
            payloadSizeBytes = 64,
            technique = "FSK",
            payloadPreview = "modem-preview",
            carrierKind = "AUDIO",
            mediaPath = "firefly_3000.wav",
            mediaBytes = 4_096L,
        )
        dao.insert(fresh)

        val freshRetrieved = dao.observeByModule("ACOUSTIC_MODEM").first().single()
        assertEquals(fresh.moduleId, freshRetrieved.moduleId)
        assertEquals(fresh.direction, freshRetrieved.direction)
        assertEquals(fresh.timestampMillis, freshRetrieved.timestampMillis)
        assertEquals(fresh.payloadSizeBytes, freshRetrieved.payloadSizeBytes)
        assertEquals(fresh.technique, freshRetrieved.technique)
        assertEquals(fresh.payloadPreview, freshRetrieved.payloadPreview)
        assertEquals(fresh.carrierKind, freshRetrieved.carrierKind)
        assertEquals(fresh.mediaPath, freshRetrieved.mediaPath)
        assertEquals(fresh.mediaBytes, freshRetrieved.mediaBytes)

        database.close()

        // Second launch: reopening the now-migrated file must not throw. This is the half the
        // migration test would otherwise miss — a migration can leave the table correct but the
        // stored identity hash stale, which opens fine once and then throws "Room cannot verify
        // the data integrity" on every subsequent launch.
        val reopened = Room.databaseBuilder(context, FireflyDatabase::class.java, dbFile.name)
            .addMigrations(MIGRATION_1_2)
            .build()
        assertEquals(3, reopened.fireflyDao().observeAll().first().size)
        reopened.close()
    }
}
