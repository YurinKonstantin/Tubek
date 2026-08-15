package ru.tubek.app.feed

import ru.tubek.app.data.SubscriptionEntity
import ru.tubek.app.data.WatchHistoryEntity
import ru.tubek.app.youtube.VideoItem
import ru.tubek.app.youtube.YoutubeRepository

data class HomeFeed(
    val fromSubscriptions: List<VideoItem> = emptyList(),
    val recommended: List<VideoItem> = emptyList(),
    val trending: List<VideoItem> = emptyList(),
    val shorts: List<VideoItem> = emptyList()
)

/**
 * Собирает главную: подписки + рекомендации по тематике + тренды + Shorts для полок.
 */
class HomeFeedMixer(
    private val repository: YoutubeRepository
) {
    private val shortsMixer = ShortsFeedMixer(repository)

    suspend fun build(
        subscriptions: List<SubscriptionEntity>,
        recentHistory: List<WatchHistoryEntity>
    ): HomeFeed {
        // Тренды критичны для пустой главной — ошибка должна триггерить смену прокси.
        // При наличии подписок тренды мягкие: лента всё равно соберётся.
        val trendingResult = runCatching { repository.trending() }
        val trending = trendingResult.getOrElse { error ->
            if (subscriptions.isEmpty()) throw error
            emptyList()
        }
        val shorts = runCatching { shortsMixer.build(subscriptions) }.getOrDefault(emptyList())

        if (subscriptions.isEmpty()) {
            if (trending.isEmpty()) {
                trendingResult.exceptionOrNull()?.let { throw it }
                    ?: error("Тренды недоступны")
            }
            return HomeFeed(trending = trending, shorts = shorts)
        }

        val fromSubs = mutableListOf<VideoItem>()
        val topicCorpus = mutableListOf<String>()

        subscriptions.take(12).forEach { sub ->
            topicCorpus += sub.name
            val videos = runCatching {
                repository.channelVideos(sub.channelUrl, limit = 8)
            }.getOrDefault(emptyList())
            fromSubs += videos
            topicCorpus += videos.take(3).map { it.title }
        }

        topicCorpus += recentHistory.take(10).map { it.title }

        val subscribedIds = fromSubs.map { it.id }.toHashSet()
        val subscribedChannelKeys = subscriptions.map { it.channelId }.toHashSet()

        val queries = extractTopics(topicCorpus).take(3)
        val recommended = mutableListOf<VideoItem>()
        queries.forEach { query ->
            val found = runCatching { repository.search(query) }.getOrDefault(emptyList())
            recommended += found.filter { item ->
                item.id !in subscribedIds &&
                    YoutubeRepository.extractChannelId(item.uploaderUrl) !in subscribedChannelKeys
            }
        }

        fromSubs.take(2).forEach { seed ->
            val related = runCatching {
                repository.resolve(seed.url).related
            }.getOrDefault(emptyList())
            recommended += related.filter { it.id !in subscribedIds }
        }

        val feed = HomeFeed(
            fromSubscriptions = fromSubs.distinctBy { it.id }.sortedByDescending { it.durationSeconds ?: 0 },
            recommended = recommended.distinctBy { it.id }.take(30),
            trending = trending.filter { it.id !in subscribedIds }.take(40),
            shorts = shorts
        )

        if (!feed.hasAnyContent()) {
            trendingResult.exceptionOrNull()?.let { throw it }
                ?: error("Лента недоступна. Смените прокси")
        }
        return feed
    }

    private fun HomeFeed.hasAnyContent(): Boolean =
        fromSubscriptions.isNotEmpty() || recommended.isNotEmpty() || trending.isNotEmpty()

    private fun extractTopics(corpus: List<String>): List<String> {
        val stop = STOP_WORDS
        val counts = HashMap<String, Int>()
        corpus.forEach { text ->
            text.lowercase()
                .split(Regex("[^\\p{L}\\p{Nd}]+"))
                .filter { it.length >= 3 && it !in stop }
                .forEach { token ->
                    counts[token] = (counts[token] ?: 0) + 1
                }
        }
        return counts.entries
            .sortedByDescending { it.value }
            .map { it.key }
            .filter { it.length in 3..24 }
    }

    companion object {
        private val STOP_WORDS = setOf(
            "the", "and", "for", "with", "this", "that", "from", "your", "you", "are",
            "как", "это", "для", "что", "или", "при", "все", "его", "она", "они",
            "видео", "канал", "смотреть", "новый", "новые", "сегодня", "live", "stream",
            "official", "music", "video", "shorts", "episode", "part"
        )
    }
}
