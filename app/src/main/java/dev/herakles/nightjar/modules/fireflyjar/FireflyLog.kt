package dev.herakles.nightjar.modules.fireflyjar

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One firefly: a single CovertCarrier/CovertDetector encode or decode event, logged for the
 * Firefly Jar UI (gate-11). `moduleId` stores [dev.herakles.nightjar.ModuleId.name] so the log
 * stays decoupled from that enum's Kotlin type.
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
)

@Dao
interface FireflyDao {
    @Insert
    suspend fun insert(record: FireflyRecord)

    @Query("SELECT * FROM firefly_records WHERE moduleId = :moduleId ORDER BY timestampMillis DESC")
    fun observeByModule(moduleId: String): Flow<List<FireflyRecord>>

    @Query("SELECT * FROM firefly_records ORDER BY timestampMillis DESC")
    fun observeAll(): Flow<List<FireflyRecord>>

    @Query("DELETE FROM firefly_records")
    suspend fun clearAll()
}

@Database(entities = [FireflyRecord::class], version = 1)
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
                ).build().also { instance = it }
            }
    }
}
