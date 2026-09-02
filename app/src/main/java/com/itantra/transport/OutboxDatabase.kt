package com.itantra.transport

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val messageId: String,
    val packetJson: String,
    val createdAt: Long,
    var retryCount: Int,
    var lastAttempt: Long,
    var isAcknowledged: Boolean
)

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE isAcknowledged = 0 ORDER BY createdAt ASC")
    suspend fun pendingMessages(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE isAcknowledged = 1")
    suspend fun deliveredMessages(): List<OutboxEntity>

    @Query("UPDATE outbox SET retryCount = :retry, lastAttempt = :t WHERE messageId = :id")
    suspend fun updateAttempt(id: String, retry: Int, t: Long)

    @Query("UPDATE outbox SET isAcknowledged = 1 WHERE messageId = :id")
    suspend fun markAcknowledged(id: String)

    @Query("DELETE FROM outbox WHERE messageId = :id")
    suspend fun delete(id: String)
}

@Database(entities = [OutboxEntity::class], version = 1, exportSchema = false)
abstract class OutboxDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao

    companion object {
        @Volatile private var instance: OutboxDatabase? = null

        fun getDatabase(context: Context): OutboxDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OutboxDatabase::class.java,
                    "itantra_outbox.db"
                ).build().also { instance = it }
            }
        }
    }
}
