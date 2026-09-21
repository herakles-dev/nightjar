package dev.herakles.nightjar.modules.fireflyjar

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One firefly: a single CovertCarrier/CovertDetector encode or decode event, logged for the
 * Firefly Jar UI (gate-11). `moduleId` stores [dev.herakles.nightjar.ModuleId.name] so the log
 * stays decoupled from that enum's Kotlin type.
 *
 * v2 (gate-16) adds media attachment fields: [carrierKind] ("IMAGE"|"AUDIO"|null), [mediaPath]
 * (a FILENAME inside filesDir/fireflies/ — never an absolute path, since filesDir moves between
 * installs), and [mediaBytes] (byte size of the attached media, 0 when none attached).
 */
@Entity(tableName = "firefly_records")
data class FireflyRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val moduleId: String,
    val direction: String,
    val timestampMillis: Long,
    val payloadSizeBytes: Int,
    val technique: String?,
    val payloadPreview: String?,
    val carrierKind: String? = null,
    val mediaPath: String? = null,
    @ColumnInfo(defaultValue = "0") val mediaBytes: Long = 0,
)

@Dao
interface FireflyDao {
    @Insert
    suspend fun insert(record: FireflyRecord)

    @Query("SELECT * FROM firefly_records WHERE moduleId = :moduleId ORDER BY timestampMillis DESC")
    fun observeByModule(moduleId: String): Flow<List<FireflyRecord>>

    @Query("SELECT * FROM firefly_records ORDER BY timestampMillis DESC")
    fun observeAll(): Flow<List<FireflyRecord>>

    /** Resolves a single record by [id], or null if no such row exists. Used by
     *  [FireflyRepository.deleteFirefly] to look up the row's `mediaPath` before deleting it. */
    @Query("SELECT * FROM firefly_records WHERE id = :id")
    suspend fun getById(id: Long): FireflyRecord?

    @Query("DELETE FROM firefly_records")
    suspend fun clearAll()

    @Query("DELETE FROM firefly_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Physical on-disk media usage (gate-20, Stage E prerequisite). Two corrections over a naive
     * `SELECT SUM(mediaBytes) FROM firefly_records`:
     *
     * 1. `COALESCE(..., 0)` -- SQL `SUM` over zero rows returns `NULL`, not `0`. An empty jar is
     *    every new user's starting state, so an unguarded caller would show "null" there.
     * 2. `SELECT DISTINCT mediaPath, mediaBytes` before summing -- content-addressed dedup
     *    ([FireflyMediaStore.write]) means several rows can share one file. Summing `mediaBytes`
     *    per ROW (the naive query) would report logical carrier size, double-counting every
     *    shared file once per referencing row -- the exact overstatement dedup exists to remove.
     *    Summing over the distinct `(mediaPath, mediaBytes)` pairs instead reports what's
     *    actually sitting on disk, which is what "your jars are holding X MB" means to a user.
     */
    @Query(
        "SELECT COALESCE(SUM(mediaBytes), 0) FROM " +
            "(SELECT DISTINCT mediaPath, mediaBytes FROM firefly_records WHERE mediaPath IS NOT NULL)",
    )
    fun observeTotalMediaBytes(): Flow<Long>

    @Query("SELECT mediaPath FROM firefly_records WHERE mediaPath IS NOT NULL")
    suspend fun allMediaPaths(): List<String>

    /** Total row count -- the `record_count` field of [dev.herakles.nightjar.DebugProbe]'s
     *  stored-media report (gate-20 v4 probe contract). */
    @Query("SELECT COUNT(*) FROM firefly_records")
    suspend fun countAll(): Int

    /** Rows carrying an attached media file -- the `records_with_media_count` field of
     *  [dev.herakles.nightjar.DebugProbe]'s stored-media report (gate-20 v4 probe contract). */
    @Query("SELECT COUNT(*) FROM firefly_records WHERE mediaPath IS NOT NULL")
    suspend fun countWithMedia(): Int

    /**
     * Counts rows OTHER than [excludingId] whose `mediaPath` equals [path].
     *
     * Backs [FireflyRepository]'s reference-counted delete (gate-20, task #23): once task #24
     * lands content-addressed filenames, two rows can share one on-disk file, so deleting that
     * file is only safe when this returns 0. [excludingId] lets a caller exclude a row that is
     * still present in the table at check time (the row about to be deleted) or a sentinel that
     * matches no real row (a failed insert that never got one) -- see
     * [FireflyRepository.deleteMediaFileIfUnreferenced].
     */
    @Query("SELECT COUNT(*) FROM firefly_records WHERE mediaPath = :path AND id != :excludingId")
    suspend fun countReferencesTo(path: String, excludingId: Long): Int
}

/**
 * v1 -> v2 (gate-16): adds the media attachment columns. SQLite cannot add a NOT NULL column
 * without a SQL-level default, so `mediaBytes` carries `DEFAULT 0` here AND
 * `@ColumnInfo(defaultValue = "0")` on the entity above — otherwise Room's post-migration
 * schema validation (run on open) would see a migrated table whose `mediaBytes` column lacks a
 * default, disagree with the entity's expected TableInfo, and throw. Matching both sides also
 * means a fresh v2 install produces the identical table to a migrated v1 one.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE firefly_records ADD COLUMN carrierKind TEXT")
        db.execSQL("ALTER TABLE firefly_records ADD COLUMN mediaPath TEXT")
        db.execSQL("ALTER TABLE firefly_records ADD COLUMN mediaBytes INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(entities = [FireflyRecord::class], version = 2)
abstract class FireflyDatabase : RoomDatabase() {
    abstract fun fireflyDao(): FireflyDao

    companion object {
        @Volatile
        private var instance: FireflyDatabase? = null

        fun getInstance(context: Context): FireflyDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FireflyDatabase::class.java,
                    "firefly_jar.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }
    }
}
