package ru.tubek.app.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import ru.tubek.app.youtube.VideoItem

class WatchHistoryRepository(context: Context) {
    private val dao = TubekDatabase.get(context).watchHistoryDao()

    fun observeAll(): Flow<List<WatchHistoryEntity>> = dao.observeAll()

    suspend fun getRecent(limit: Int): List<WatchHistoryEntity> = dao.getRecent(limit)

    suspend fun getById(videoId: String): WatchHistoryEntity? = dao.getById(videoId)

    suspend fun recordWatch(item: VideoItem, uploaderUrl: String?, positionMs: Long? = null) {
        val existing = dao.getById(item.id)
        dao.upsert(
            WatchHistoryEntity(
                videoId = item.id,
                title = item.title,
                uploader = item.uploader,
                uploaderUrl = uploaderUrl,
                thumbnailUrl = item.thumbnailUrl,
                videoUrl = item.url,
                durationSeconds = item.durationSeconds,
                positionMs = positionMs ?: existing?.positionMs ?: 0L,
                watchedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun updatePosition(videoId: String, positionMs: Long) {
        dao.updatePosition(videoId, positionMs)
    }

    suspend fun delete(videoId: String) = dao.delete(videoId)

    suspend fun clear() = dao.clearAll()
}
