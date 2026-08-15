package ru.tubek.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY watchedAt DESC")
    fun observeAll(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history ORDER BY watchedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE videoId = :videoId LIMIT 1")
    suspend fun getById(videoId: String): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchHistoryEntity)

    @Query("UPDATE watch_history SET positionMs = :positionMs, watchedAt = :watchedAt WHERE videoId = :videoId")
    suspend fun updatePosition(videoId: String, positionMs: Long, watchedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM watch_history WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    @Query("DELETE FROM watch_history")
    suspend fun clearAll()
}
