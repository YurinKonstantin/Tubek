package ru.tubek.app.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class DownloadHistoryRepository(context: Context) {
    private val dao = TubekDatabase.get(context).downloadDao()

    fun observe(): Flow<List<DownloadRecord>> = dao.observeAll()

    suspend fun add(record: DownloadRecord) {
        dao.insert(record)
    }

    suspend fun delete(id: Long) {
        dao.deleteById(id)
    }

    suspend fun clear() {
        dao.clearAll()
    }
}
