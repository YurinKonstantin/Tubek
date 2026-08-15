package ru.tubek.app.feed

import ru.tubek.app.data.SubscriptionEntity
import ru.tubek.app.youtube.VideoItem
import ru.tubek.app.youtube.YoutubeRepository

/**
 * Собирает ленту Shorts: ролики с подписок + обнаруженные short-form видео.
 */
class ShortsFeedMixer(
    private val repository: YoutubeRepository
) {
    suspend fun build(subscriptions: List<SubscriptionEntity>): List<VideoItem> {
        val fromSubs = mutableListOf<VideoItem>()
        subscriptions.take(10).forEach { sub ->
            fromSubs += runCatching {
                repository.channelShorts(sub.channelUrl, limit = 6)
            }.getOrDefault(emptyList())
        }

        val discoveredResult = runCatching { repository.discoverShorts(limit = 40) }
        val discovered = discoveredResult.getOrElse { error ->
            if (fromSubs.isEmpty()) throw error
            emptyList()
        }

        val merged = (fromSubs.shuffled() + discovered).distinctBy { it.id }
        if (merged.isEmpty()) {
            discoveredResult.exceptionOrNull()?.let { throw it }
                ?: error("Shorts недоступны")
        }
        return merged
    }
}
