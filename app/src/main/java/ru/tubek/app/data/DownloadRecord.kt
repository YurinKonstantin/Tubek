package ru.tubek.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download_history")
data class DownloadRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String?,
    val qualityLabel: String,
    val filePath: String,
    val mimeType: String,
    val isAudioOnly: Boolean,
    val videoUrl: String,
    val downloadedAt: Long = System.currentTimeMillis()
)
