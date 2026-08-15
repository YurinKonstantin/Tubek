package ru.tubek.app.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class SubscriptionRepository(context: Context) {
    private val dao = TubekDatabase.get(context).subscriptionDao()

    fun observeAll(): Flow<List<SubscriptionEntity>> = dao.observeAll()

    fun observeIsSubscribed(channelId: String): Flow<Boolean> = dao.observeIsSubscribed(channelId)

    suspend fun getAll(): List<SubscriptionEntity> = dao.getAll()

    suspend fun getById(channelId: String): SubscriptionEntity? = dao.getById(channelId)

    suspend fun subscribe(
        channelId: String,
        name: String,
        channelUrl: String,
        avatarUrl: String?
    ) {
        dao.upsert(
            SubscriptionEntity(
                channelId = channelId,
                name = name,
                channelUrl = channelUrl,
                avatarUrl = avatarUrl
            )
        )
    }

    suspend fun unsubscribe(channelId: String) = dao.delete(channelId)

    suspend fun setNotifyEnabled(channelId: String, enabled: Boolean) =
        dao.setNotifyEnabled(channelId, enabled)

    suspend fun setLastSeenVideoId(channelId: String, videoId: String) =
        dao.setLastSeenVideoId(channelId, videoId)
}
