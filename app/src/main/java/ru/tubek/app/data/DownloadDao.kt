package ru.tubek.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download_history ORDER BY downloadedAt DESC")
    fun observeAll(): Flow<List<DownloadRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: DownloadRecord): Long

    @Query("DELETE FROM download_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM download_history")
    suspend fun clearAll()
}
