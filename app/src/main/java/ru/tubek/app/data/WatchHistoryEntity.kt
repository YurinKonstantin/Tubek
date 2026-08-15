package ru.tubek.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val uploader: String,
    val uploaderUrl: String?,
    val thumbnailUrl: String?,
    val videoUrl: String,
    val durationSeconds: Long?,
    val positionMs: Long = 0,
    val watchedAt: Long = System.currentTimeMillis()
)
