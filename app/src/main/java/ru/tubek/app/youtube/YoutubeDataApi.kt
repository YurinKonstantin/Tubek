package ru.tubek.app.youtube

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.tubek.app.BuildConfig
import ru.tubek.app.data.SubscriptionEntity
import ru.tubek.app.data.WatchHistoryEntity
import java.io.IOException
import java.security.MessageDigest

/**
 * Клиент YouTube Data API v3 (метаданные, поиск, подписки).
 * Потоки видео сюда не входят — их по-прежнему даёт NewPipe.
 *
 * Для API key с ограничением «Android apps» обязательны заголовки
 * X-Android-Package / X-Android-Cert — иначе Google отвечает
 * «requests from this Android client application &lt;empty&gt; are blocked».
 */
class YoutubeDataApi(
    private val apiKey: String = BuildConfig.YOUTUBE_API_KEY,
    private val clientProvider: () -> OkHttpClient,
    private val accessTokenProvider: () -> String? = { null },
    private val androidPackageName: String? = null,
    private val androidCertSha1: String? = null
) {
    constructor(
        context: Context,
        clientProvider: () -> OkHttpClient,
        accessTokenProvider: () -> String? = { null },
        apiKey: String = BuildConfig.YOUTUBE_API_KEY
    ) : this(
        apiKey = apiKey,
        clientProvider = clientProvider,
        accessTokenProvider = accessTokenProvider,
        androidPackageName = context.packageName,
        androidCertSha1 = signingCertSha1(context)
    )
    suspend fun search(
        query: String,
        maxResults: Int = 25,
        regionCode: String? = null,
        language: String? = null
    ): List<VideoItem> {
        val searchUrl = baseUrl("search")
            .addQueryParameter("part", "snippet")
            .addQueryParameter("type", "video")
            .addQueryParameter("q", query.trim())
            .addQueryParameter("maxResults", maxResults.coerceIn(1, 50).toString())
            .apply {
                regionCode?.takeIf { it.isNotBlank() }?.let { addQueryParameter("regionCode", it) }
                language?.takeIf { it.isNotBlank() }?.let {
                    addQueryParameter("relevanceLanguage", it.substringBefore('-'))
                }
            }
            .build()
        val searchJson = getJson(searchUrl.toString())
        val ids = searchJson.optJSONArray("items").orEmpty().mapNotNull { item ->
            item.optJSONObject("id")?.optString("videoId")?.takeIf { it.isNotBlank() }
        }
        return videosByIds(ids)
    }

    suspend fun trending(
        regionCode: String = "US",
        maxResults: Int = 40
    ): List<VideoItem> {
        val url = baseUrl("videos")
            .addQueryParameter("part", "snippet,contentDetails,statistics")
            .addQueryParameter("chart", "mostPopular")
            .addQueryParameter("maxResults", maxResults.coerceIn(1, 50).toString())
            .addQueryParameter("regionCode", regionCode.ifBlank { "US" })
            .build()
        return parseVideoItems(getJson(url.toString()).optJSONArray("items"))
    }

    suspend fun videosByIds(ids: List<String>): List<VideoItem> {
        if (ids.isEmpty()) return emptyList()
        val result = ArrayList<VideoItem>(ids.size)
        ids.distinct().chunked(50).forEach { chunk ->
            val url = baseUrl("videos")
                .addQueryParameter("part", "snippet,contentDetails,statistics")
                .addQueryParameter("id", chunk.joinToString(","))
                .build()
            result += parseVideoItems(getJson(url.toString()).optJSONArray("items"))
        }
        val order = ids.withIndex().associate { it.value to it.index }
        return result.sortedBy { order[it.id] ?: Int.MAX_VALUE }
    }

    suspend fun videoResource(id: String): VideoResource? {
        val url = baseUrl("videos")
            .addQueryParameter("part", "snippet,contentDetails,statistics")
            .addQueryParameter("id", id)
            .build()
        val item = getJson(url.toString()).optJSONArray("items")?.optJSONObject(0) ?: return null
        return parseVideoResource(item)
    }

    suspend fun relatedVideos(seedTitle: String, excludeId: String, maxResults: Int = 20): List<VideoItem> {
        val query = seedTitle
            .split(Regex("\\s+"))
            .filter { it.length >= 3 }
            .take(6)
            .joinToString(" ")
            .ifBlank { seedTitle.take(40) }
        return search(query, maxResults = maxResults + 5)
            .filter { it.id != excludeId }
            .take(maxResults)
    }

    suspend fun resolveChannelId(channelUrlOrId: String): String? {
        val raw = channelUrlOrId.trim()
        if (raw.isBlank()) return null
        if (raw.matches(Regex("^UC[\\w-]{22}$"))) return raw

        val handle = when {
            raw.startsWith("@") -> raw.removePrefix("@")
            raw.contains("/@") -> raw.substringAfter("/@").substringBefore('/').substringBefore('?')
            raw.contains("/channel/") -> {
                val id = raw.substringAfter("/channel/").substringBefore('/').substringBefore('?')
                return id.takeIf { it.startsWith("UC") }
            }
            raw.contains("/c/") || raw.contains("/user/") ->
                raw.substringAfterLast('/').substringBefore('?')
            else -> raw.removePrefix("@")
        }.takeIf { it.isNotBlank() } ?: return null

        // forHandle (новый API) → fallback forUsername
        runCatching {
            val url = baseUrl("channels")
                .addQueryParameter("part", "id")
                .addQueryParameter("forHandle", handle.removePrefix("@"))
                .build()
            getJson(url.toString()).optJSONArray("items")
                ?.optJSONObject(0)
                ?.optString("id")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()?.let { return it }

        val url = baseUrl("channels")
            .addQueryParameter("part", "id")
            .addQueryParameter("forUsername", handle)
            .build()
        return getJson(url.toString()).optJSONArray("items")
            ?.optJSONObject(0)
            ?.optString("id")
            ?.takeIf { it.isNotBlank() }
    }

    suspend fun channelInfo(channelId: String): ChannelInfo? {
        val url = baseUrl("channels")
            .addQueryParameter("part", "snippet,contentDetails")
            .addQueryParameter("id", channelId)
            .build()
        val item = getJson(url.toString()).optJSONArray("items")?.optJSONObject(0) ?: return null
        val snippet = item.optJSONObject("snippet") ?: return null
        val uploads = item.optJSONObject("contentDetails")
            ?.optJSONObject("relatedPlaylists")
            ?.optString("uploads")
            ?.takeIf { it.isNotBlank() }
        val thumbs = snippet.optJSONObject("thumbnails")
        val avatar = thumbs?.bestThumbUrl()
        return ChannelInfo(
            id = item.optString("id"),
            title = snippet.optString("title"),
            url = "https://www.youtube.com/channel/${item.optString("id")}",
            avatarUrl = avatar,
            uploadsPlaylistId = uploads
        )
    }

    suspend fun channelVideos(channelUrlOrId: String, limit: Int = 15): List<VideoItem> {
        val channelId = resolveChannelId(channelUrlOrId) ?: return emptyList()
        val info = channelInfo(channelId) ?: return emptyList()
        val playlistId = info.uploadsPlaylistId ?: return emptyList()
        val ids = playlistVideoIds(playlistId, limit)
        return videosByIds(ids)
    }

    suspend fun channelShorts(channelUrlOrId: String, limit: Int = 15): List<VideoItem> {
        val channelId = resolveChannelId(channelUrlOrId) ?: return emptyList()
        val url = baseUrl("search")
            .addQueryParameter("part", "snippet")
            .addQueryParameter("type", "video")
            .addQueryParameter("channelId", channelId)
            .addQueryParameter("videoDuration", "short")
            .addQueryParameter("order", "date")
            .addQueryParameter("maxResults", limit.coerceIn(1, 50).toString())
            .build()
        val ids = getJson(url.toString()).optJSONArray("items").orEmpty().mapNotNull { item ->
            item.optJSONObject("id")?.optString("videoId")?.takeIf { it.isNotBlank() }
        }
        return videosByIds(ids)
            .filter { item ->
                val d = item.durationSeconds
                d == null || d in 1..MAX_SHORT_DURATION_SEC
            }
            .take(limit)
    }

    suspend fun discoverShorts(limit: Int = 40, regionCode: String? = null): List<VideoItem> {
        val collected = LinkedHashMap<String, VideoItem>()
        for (query in listOf("#shorts", "shorts")) {
            if (collected.size >= limit) break
            val url = baseUrl("search")
                .addQueryParameter("part", "snippet")
                .addQueryParameter("type", "video")
                .addQueryParameter("q", query)
                .addQueryParameter("videoDuration", "short")
                .addQueryParameter("maxResults", 25.toString())
                .apply {
                    regionCode?.takeIf { it.isNotBlank() }?.let { addQueryParameter("regionCode", it) }
                }
                .build()
            val ids = getJson(url.toString()).optJSONArray("items").orEmpty().mapNotNull { item ->
                item.optJSONObject("id")?.optString("videoId")?.takeIf { it.isNotBlank() }
            }
            videosByIds(ids).forEach { item ->
                val d = item.durationSeconds
                if (d == null || d in 1..MAX_SHORT_DURATION_SEC) {
                    collected.putIfAbsent(item.id, item)
                }
            }
        }
        return collected.values.take(limit)
    }

    suspend fun listSubscriptions(maxResults: Int = 50): List<RemoteSubscription> {
        val token = requireAccessToken()
        val result = ArrayList<RemoteSubscription>()
        var pageToken: String? = null
        do {
            val url = baseUrl("subscriptions", useApiKey = false)
                .addQueryParameter("part", "snippet,contentDetails")
                .addQueryParameter("mine", "true")
                .addQueryParameter("maxResults", "50")
                .addQueryParameter("order", "alphabetical")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val json = getJson(url.toString(), bearerToken = token)
            json.optJSONArray("items").orEmpty().forEach { item ->
                val snippet = item.optJSONObject("snippet") ?: return@forEach
                val channelId = snippet.optJSONObject("resourceId")?.optString("channelId")
                    ?.takeIf { it.isNotBlank() } ?: return@forEach
                val thumbs = snippet.optJSONObject("thumbnails")
                result += RemoteSubscription(
                    apiSubscriptionId = item.optString("id"),
                    channelId = channelId,
                    name = snippet.optString("title"),
                    channelUrl = "https://www.youtube.com/channel/$channelId",
                    avatarUrl = thumbs?.bestThumbUrl()
                )
            }
            pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (pageToken != null && result.size < maxResults)
        return result.take(maxResults)
    }

    suspend fun subscribe(channelId: String): RemoteSubscription {
        val token = requireAccessToken()
        val body = JSONObject()
            .put(
                "snippet",
                JSONObject().put(
                    "resourceId",
                    JSONObject()
                        .put("kind", "youtube#channel")
                        .put("channelId", channelId)
                )
            )
            .toString()
            .toRequestBody(JSON_MEDIA)
        val url = baseUrl("subscriptions", useApiKey = false)
            .addQueryParameter("part", "snippet")
            .build()
        val json = requestJson(
            authorizedRequest(url, token)
                .post(body)
                .build()
        )
        val snippet = json.optJSONObject("snippet")
        val resolvedChannelId = snippet?.optJSONObject("resourceId")?.optString("channelId")
            ?.takeIf { it.isNotBlank() } ?: channelId
        return RemoteSubscription(
            apiSubscriptionId = json.optString("id"),
            channelId = resolvedChannelId,
            name = snippet?.optString("title").orEmpty(),
            channelUrl = "https://www.youtube.com/channel/$resolvedChannelId",
            avatarUrl = snippet?.optJSONObject("thumbnails")?.bestThumbUrl()
        )
    }

    suspend fun unsubscribe(apiSubscriptionId: String) {
        val token = requireAccessToken()
        val url = baseUrl("subscriptions", useApiKey = false)
            .addQueryParameter("id", apiSubscriptionId)
            .build()
        requestJson(
            authorizedRequest(url, token)
                .delete()
                .build(),
            allowEmpty = true
        )
    }

    suspend fun findSubscriptionId(channelId: String): String? {
        return listSubscriptions(maxResults = 200)
            .firstOrNull { it.channelId == channelId }
            ?.apiSubscriptionId
    }

    /** «Понравившиеся» — ближайший аналог истории через официальный API. */
    suspend fun likedVideos(maxResults: Int = 50): List<VideoItem> {
        val token = requireAccessToken()
        val url = baseUrl("videos", useApiKey = false)
            .addQueryParameter("part", "snippet,contentDetails,statistics")
            .addQueryParameter("myRating", "like")
            .addQueryParameter("maxResults", maxResults.coerceIn(1, 50).toString())
            .build()
        return parseVideoItems(getJson(url.toString(), bearerToken = token).optJSONArray("items"))
    }

    private suspend fun playlistVideoIds(playlistId: String, limit: Int): List<String> {
        val ids = ArrayList<String>()
        var pageToken: String? = null
        while (ids.size < limit) {
            val pageSize = (limit - ids.size).coerceIn(1, 50)
            val url = baseUrl("playlistItems")
                .addQueryParameter("part", "contentDetails")
                .addQueryParameter("playlistId", playlistId)
                .addQueryParameter("maxResults", pageSize.toString())
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val json = getJson(url.toString())
            json.optJSONArray("items").orEmpty().forEach { item ->
                item.optJSONObject("contentDetails")
                    ?.optString("videoId")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { ids += it }
            }
            pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() } ?: break
        }
        return ids.take(limit)
    }

    private fun parseVideoItems(items: JSONArray?): List<VideoItem> =
        items.orEmpty().mapNotNull { parseVideoItem(it) }

    private fun parseVideoItem(item: JSONObject): VideoItem? {
        val id = item.optString("id").takeIf { it.isNotBlank() } ?: return null
        val snippet = item.optJSONObject("snippet") ?: return null
        val channelId = snippet.optString("channelId").takeIf { it.isNotBlank() }
        val duration = parseIso8601Duration(
            item.optJSONObject("contentDetails")?.optString("duration")
        )
        return VideoItem(
            id = id,
            title = snippet.optString("title"),
            uploader = snippet.optString("channelTitle"),
            thumbnailUrl = snippet.optJSONObject("thumbnails")?.bestThumbUrl()
                ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            durationSeconds = duration,
            url = "https://www.youtube.com/watch?v=$id",
            uploaderUrl = channelId?.let { "https://www.youtube.com/channel/$it" }
        )
    }

    private fun parseVideoResource(item: JSONObject): VideoResource? {
        val video = parseVideoItem(item) ?: return null
        val snippet = item.optJSONObject("snippet") ?: return null
        val stats = item.optJSONObject("statistics")
        val channelId = snippet.optString("channelId").takeIf { it.isNotBlank() }
        return VideoResource(
            item = video,
            description = snippet.optString("description"),
            viewCount = stats?.optString("viewCount")?.toLongOrNull(),
            channelId = channelId,
            channelUrl = channelId?.let { "https://www.youtube.com/channel/$it" },
            publishedAt = snippet.optString("publishedAt")
        )
    }

    private fun baseUrl(method: String, useApiKey: Boolean = true) =
        "https://www.googleapis.com/youtube/v3/$method".toHttpUrl().newBuilder().apply {
            if (useApiKey) {
                require(apiKey.isNotBlank()) {
                    "YouTube API key не задан. Добавьте youtube.api.key в local.properties"
                }
                addQueryParameter("key", apiKey)
            }
        }

    private fun requireAccessToken(): String =
        accessTokenProvider()?.takeIf { it.isNotBlank() }
            ?: throw IOException("Нужна авторизация Google для этого запроса")

    private fun getJson(url: String, bearerToken: String? = null): JSONObject {
        val builder = Request.Builder().url(url).get()
        applyAndroidApiKeyHeaders(builder)
        if (!bearerToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $bearerToken")
        }
        return requestJson(builder.build())
    }

    private fun requestJson(request: Request, allowEmpty: Boolean = false): JSONObject {
        clientProvider().newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: "YouTube API HTTP ${response.code}"
                throw IOException(message)
            }
            if (allowEmpty && body.isBlank()) return JSONObject()
            return JSONObject(body)
        }
    }

    private fun applyAndroidApiKeyHeaders(builder: Request.Builder) {
        val pkg = androidPackageName?.takeIf { it.isNotBlank() } ?: return
        val cert = androidCertSha1?.takeIf { it.isNotBlank() } ?: return
        builder.header("X-Android-Package", pkg)
        builder.header("X-Android-Cert", cert)
    }

    private fun authorizedRequest(url: okhttp3.HttpUrl, token: String): Request.Builder =
        Request.Builder().url(url).also { builder ->
            applyAndroidApiKeyHeaders(builder)
            builder.header("Authorization", "Bearer $token")
        }

    companion object {
        private const val MAX_SHORT_DURATION_SEC = 60L
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun parseIso8601Duration(raw: String?): Long? {
            if (raw.isNullOrBlank()) return null
            val match = Regex(
                "^PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?$",
                RegexOption.IGNORE_CASE
            ).matchEntire(raw.trim()) ?: return null
            val hours = match.groupValues[1].toLongOrNull() ?: 0L
            val minutes = match.groupValues[2].toLongOrNull() ?: 0L
            val seconds = match.groupValues[3].toLongOrNull() ?: 0L
            val total = hours * 3600 + minutes * 60 + seconds
            return total.takeIf { it > 0 }
        }

        /** SHA-1 подписи APK без двоеточий (как ждёт Google API key restriction). */
        fun signingCertSha1(context: Context): String? {
            return runCatching {
                val pm = context.packageManager
                val packageName = context.packageName
                val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    val signingInfo = info.signingInfo ?: return null
                    if (signingInfo.hasMultipleSigners()) {
                        signingInfo.apkContentsSigners
                    } else {
                        signingInfo.signingCertificateHistory
                    }
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
                }
                val signature = signatures?.firstOrNull() ?: return null
                val digest = MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
                digest.joinToString("") { byte -> "%02x".format(byte) }
            }.getOrNull()
        }
    }
}

data class ChannelInfo(
    val id: String,
    val title: String,
    val url: String,
    val avatarUrl: String?,
    val uploadsPlaylistId: String?
)

data class VideoResource(
    val item: VideoItem,
    val description: String,
    val viewCount: Long?,
    val channelId: String?,
    val channelUrl: String?,
    val publishedAt: String?
)

data class RemoteSubscription(
    val apiSubscriptionId: String,
    val channelId: String,
    val name: String,
    val channelUrl: String,
    val avatarUrl: String?
) {
    fun toEntity(notifyEnabled: Boolean = true): SubscriptionEntity =
        SubscriptionEntity(
            channelId = channelId,
            name = name,
            channelUrl = channelUrl,
            avatarUrl = avatarUrl,
            notifyEnabled = notifyEnabled,
            apiSubscriptionId = apiSubscriptionId
        )
}

fun List<VideoItem>.toHistoryEntities(
    positionLookup: (String) -> Long = { 0L }
): List<WatchHistoryEntity> = mapIndexed { index, item ->
    WatchHistoryEntity(
        videoId = item.id,
        title = item.title,
        uploader = item.uploader,
        uploaderUrl = item.uploaderUrl,
        thumbnailUrl = item.thumbnailUrl,
        videoUrl = item.url,
        durationSeconds = item.durationSeconds,
        positionMs = positionLookup(item.id),
        watchedAt = System.currentTimeMillis() - index
    )
}

private fun JSONObject.bestThumbUrl(): String? {
    val order = listOf("maxres", "standard", "high", "medium", "default")
    for (key in order) {
        val url = optJSONObject(key)?.optString("url")?.takeIf { it.isNotBlank() }
        if (url != null) return url
    }
    return null
}

private fun JSONArray?.orEmpty(): List<JSONObject> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            optJSONObject(i)?.let { add(it) }
        }
    }
}
