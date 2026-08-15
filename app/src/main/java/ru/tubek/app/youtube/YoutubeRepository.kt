package ru.tubek.app.youtube

import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import java.util.Locale
import java.util.regex.Pattern

class YoutubeRepository {

    private val youtube = ServiceList.YouTube

    suspend fun trending(): List<VideoItem> = withContext(Dispatchers.IO) {
        val extractor = youtube.kioskList.defaultKioskExtractor
        applyLocalization(extractor)
        extractor.fetchPage()
        extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { it.toVideoItem() }
            .distinctBy { it.id }
    }

    suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val extractor = youtube.getSearchExtractor(query.trim())
        applyLocalization(extractor)
        extractor.fetchPage()
        extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { it.toVideoItem() }
            .distinctBy { it.id }
    }

    suspend fun channelVideos(channelUrl: String, limit: Int = 15): List<VideoItem> =
        withContext(Dispatchers.IO) {
            val channelExtractor = youtube.getChannelExtractor(channelUrl)
            applyLocalization(channelExtractor)
            channelExtractor.fetchPage()
            val tabs = channelExtractor.tabs
            val videosTab = tabs.firstOrNull { tab ->
                tab.url?.contains("/videos", ignoreCase = true) == true ||
                    tab.contentFilters.any { it.contains("video", ignoreCase = true) }
            } ?: tabs.firstOrNull()

            if (videosTab != null) {
                val tabExtractor = youtube.getChannelTabExtractor(videosTab)
                applyLocalization(tabExtractor)
                tabExtractor.fetchPage()
                val items = tabExtractor.initialPage?.items.orEmpty()
                return@withContext items
                    .filterIsInstance<StreamInfoItem>()
                    .mapNotNull { it.toVideoItem() }
                    .distinctBy { it.id }
                    .take(limit)
            }

            emptyList()
        }

    suspend fun channelShorts(channelUrl: String, limit: Int = 15): List<VideoItem> =
        withContext(Dispatchers.IO) {
            val channelExtractor = youtube.getChannelExtractor(channelUrl)
            applyLocalization(channelExtractor)
            channelExtractor.fetchPage()
            val shortsTab = channelExtractor.tabs.firstOrNull { tab ->
                tab.contentFilters.any { it.equals(ChannelTabs.SHORTS, ignoreCase = true) } ||
                    tab.url?.contains("/shorts", ignoreCase = true) == true
            } ?: return@withContext emptyList()

            val tabExtractor = youtube.getChannelTabExtractor(shortsTab)
            applyLocalization(tabExtractor)
            tabExtractor.fetchPage()
            tabExtractor.initialPage?.items.orEmpty()
                .filterIsInstance<StreamInfoItem>()
                .filter { it.isLikelyShort() }
                .mapNotNull { it.toVideoItem() }
                .distinctBy { it.id }
                .take(limit)
        }

    /**
     * Лента Shorts: поиск + короткие ролики из трендов.
     * NewPipe не даёт отдельный kiosk Shorts, поэтому собираем по признакам short-form.
     */
    suspend fun discoverShorts(limit: Int = 40): List<VideoItem> = withContext(Dispatchers.IO) {
        val collected = LinkedHashMap<String, VideoItem>()
        for (query in listOf("#shorts", "shorts")) {
            if (collected.size >= limit) break
            val extractor = youtube.getSearchExtractor(query)
            applyLocalization(extractor)
            extractor.fetchPage()
            extractor.initialPage.items
                .filterIsInstance<StreamInfoItem>()
                .filter { it.isLikelyShort() }
                .mapNotNull { it.toVideoItem() }
                .forEach { item -> collected.putIfAbsent(item.id, item) }
        }
        if (collected.size < limit) {
            runCatching { trending() }.getOrDefault(emptyList())
                .filter { item ->
                    val duration = item.durationSeconds
                    duration != null && duration in 1..MAX_SHORT_DURATION_SEC
                }
                .forEach { item -> collected.putIfAbsent(item.id, item) }
        }
        collected.values.take(limit)
    }

    suspend fun resolve(
        urlOrId: String,
        preferredAudioLanguage: String = Locale.getDefault().language
    ): VideoDetails = withContext(Dispatchers.IO) {
        val url = normalizeUrl(urlOrId)
        val extractor = youtube.getStreamExtractor(url)
        // force* обязателен: иначе StreamingService подменяет Localization на language-only
        // без страны → invalid_argument в Innertube.
        applyLocalization(extractor)
        val info = StreamInfo.getInfo(extractor)
        val channelUrl = info.uploaderUrl
        val channelId = extractChannelId(channelUrl)
        // info.name — оригинальный title из videoDetails (не автоперевод YouTube)
        val item = VideoItem(
            id = info.id,
            title = info.name.orEmpty(),
            uploader = info.uploaderName.orEmpty(),
            thumbnailUrl = resolveThumbnailUrl(info.id, info.thumbnails.maxByOrNull { it.height }?.url),
            durationSeconds = info.duration.takeIf { it > 0 },
            url = info.url ?: url,
            uploaderUrl = channelUrl
        )

        val progressiveAudio = info.audioStreams
            .orEmpty()
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.content.isNullOrBlank() }

        val anyAudio = info.audioStreams
            .orEmpty()
            .filter { !it.content.isNullOrBlank() }
            .ifEmpty { progressiveAudio }

        val preferredLang = preferredAudioLanguage
            .trim()
            .lowercase(Locale.US)
            .ifBlank { Locale.getDefault().language.lowercase(Locale.US) }

        val audioLanguages = buildAudioLanguages(anyAudio)
        val selectedAudio = pickPreferredAudioLanguage(audioLanguages, preferredLang)
        val bestAudioUrl = selectedAudio?.audioUrl
            ?: pickBestAudio(anyAudio, preferredLang)?.content
        val selectedLangCode = selectedAudio?.code

        val muxed = info.videoStreams
            .orEmpty()
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.content.isNullOrBlank() }
            .map { it.toDownloadOption() }

        val audioDownloads = progressiveAudio.map { it.toDownloadOption() }

        val downloadStreams = (muxed + audioDownloads)
            .sortedWith(
                compareByDescending<StreamOption> { !it.isAudioOnly }
                    .thenByDescending { parseHeight(it.resolution) }
                    .thenByDescending { it.bitrate ?: 0 }
            )
            .distinctBy { "${it.quality}-${it.mimeType}-${it.isAudioOnly}-${it.formatId}" }

        val adaptiveVideo = info.videoOnlyStreams
            .orEmpty()
            .filter {
                !it.content.isNullOrBlank() &&
                    it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
            }
            .sortedByDescending { parseHeight(it.getResolution()) }

        val adaptiveByHeight = LinkedHashMap<Int, PlaybackOption>()
        if (!bestAudioUrl.isNullOrBlank()) {
            adaptiveVideo.forEach { video ->
                val height = parseHeight(video.getResolution())
                if (height <= 0 || adaptiveByHeight.containsKey(height)) return@forEach
                adaptiveByHeight[height] = PlaybackOption(
                    label = formatQualityLabel(height, video.getResolution()),
                    height = height,
                    videoUrl = video.content,
                    audioUrl = bestAudioUrl,
                    isAudioOnly = false,
                    audioLanguageCode = selectedLangCode
                )
            }
        }

        val muxedByHeight = LinkedHashMap<Int, PlaybackOption>()
        muxed.forEach { stream ->
            val height = parseHeight(stream.resolution)
            if (height <= 0 || muxedByHeight.containsKey(height)) return@forEach
            muxedByHeight[height] = PlaybackOption(
                label = formatQualityLabel(height, stream.resolution ?: stream.quality),
                height = height,
                videoUrl = stream.url,
                audioUrl = null,
                isAudioOnly = false,
                audioLanguageCode = null
            )
        }

        // Adaptive (видео+аудио синхрон) предпочтительнее muxed на той же высоте
        val videoPlayback = (adaptiveByHeight.keys + muxedByHeight.keys)
            .distinct()
            .sortedDescending()
            .mapNotNull { h -> adaptiveByHeight[h] ?: muxedByHeight[h] }
            .take(12)

        val audioOnlyPlayback = bestAudioUrl?.let {
            listOf(
                PlaybackOption(
                    label = "Только звук",
                    height = 0,
                    videoUrl = null,
                    audioUrl = it,
                    isAudioOnly = true,
                    audioLanguageCode = selectedLangCode
                )
            )
        }.orEmpty()

        val playbackOptions = (videoPlayback.ifEmpty { muxedByHeight.values.toList() } + audioOnlyPlayback)
            .sortedByDescending { it.height }

        val related = info.relatedItems
            .orEmpty()
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { it.toVideoItem() }
            .distinctBy { it.id }
            .take(20)

        VideoDetails(
            item = item,
            description = info.description?.content.orEmpty(),
            viewCount = info.viewCount.takeIf { it >= 0 },
            channelId = channelId,
            channelUrl = channelUrl,
            channelAvatarUrl = info.uploaderAvatars.maxByOrNull { it.height }?.url,
            playbackOptions = playbackOptions,
            streams = downloadStreams,
            related = related,
            audioLanguages = audioLanguages,
            selectedAudioLanguage = selectedLangCode
        )
    }

    private fun applyLocalization(extractor: org.schabi.newpipe.extractor.Extractor) {
        extractor.forceLocalization(YoutubeService.currentLocalization())
        extractor.forceContentCountry(YoutubeService.currentContentCountry())
    }

    /** Пересобрать опции воспроизведения с другой аудиодорожкой. */
    fun withAudioLanguage(details: VideoDetails, languageCode: String): VideoDetails {
        val track = details.audioLanguages.firstOrNull {
            it.code.equals(languageCode, ignoreCase = true)
        } ?: return details
        val playback = details.playbackOptions.map { opt ->
            when {
                opt.isAudioOnly -> opt.copy(
                    audioUrl = track.audioUrl,
                    audioLanguageCode = track.code
                )
                !opt.audioUrl.isNullOrBlank() -> opt.copy(
                    audioUrl = track.audioUrl,
                    audioLanguageCode = track.code
                )
                else -> opt
            }
        }
        return details.copy(
            playbackOptions = playback,
            selectedAudioLanguage = track.code
        )
    }

    private fun StreamInfoItem.isLikelyShort(): Boolean {
        if (isShortFormContent) return true
        if (url?.contains("/shorts/", ignoreCase = true) == true) return true
        val seconds = duration
        return seconds in 1..MAX_SHORT_DURATION_SEC
    }

    private fun StreamInfoItem.toVideoItem(): VideoItem? {
        val url = url ?: return null
        val id = extractVideoId(url) ?: return null
        return VideoItem(
            id = id,
            title = name.orEmpty(),
            uploader = uploaderName.orEmpty(),
            thumbnailUrl = resolveThumbnailUrl(id, thumbnails.maxByOrNull { it.height }?.url),
            durationSeconds = duration.takeIf { it > 0 },
            url = url,
            uploaderUrl = uploaderUrl
        )
    }

    private fun resolveThumbnailUrl(videoId: String, fromExtractor: String?): String {
        val raw = fromExtractor?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            return when {
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("http") -> raw
                else -> raw
            }
        }
        return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    private fun formatQualityLabel(height: Int, raw: String?): String {
        val cleaned = raw?.trim().orEmpty()
        return when {
            cleaned.contains("p", ignoreCase = true) && cleaned.length <= 8 -> cleaned
            height > 0 -> "${height}p"
            cleaned.isNotBlank() -> cleaned
            else -> "Авто"
        }
    }

    private fun buildAudioLanguages(audios: List<AudioStream>): List<AudioLanguageOption> {
        if (audios.isEmpty()) return emptyList()
        val bestByCode = LinkedHashMap<String, AudioStream>()
        for (audio in audios) {
            val code = audioLanguageCode(audio)
            val prev = bestByCode[code]
            if (prev == null || audioRank(audio, code) > audioRank(prev, code)) {
                bestByCode[code] = audio
            }
        }
        return bestByCode.map { (code, stream) ->
            AudioLanguageOption(
                code = code,
                label = formatAudioLanguageLabel(code, stream),
                audioUrl = stream.content,
                isOriginal = stream.audioTrackType == AudioTrackType.ORIGINAL
            )
        }.sortedWith(
            compareByDescending<AudioLanguageOption> { it.isOriginal }
                .thenBy { it.label.lowercase(Locale.getDefault()) }
        )
    }

    private fun pickPreferredAudioLanguage(
        options: List<AudioLanguageOption>,
        preferredLang: String
    ): AudioLanguageOption? {
        if (options.isEmpty()) return null
        options.firstOrNull { it.code.equals(preferredLang, ignoreCase = true) }?.let { return it }
        // Близкий код: pt-BR → pt
        options.firstOrNull {
            preferredLang.startsWith(it.code, ignoreCase = true) ||
                it.code.startsWith(preferredLang, ignoreCase = true)
        }?.let { return it }
        options.firstOrNull { it.isOriginal }?.let { return it }
        return options.firstOrNull()
    }

    private fun pickBestAudio(audios: List<AudioStream>, preferredLang: String): AudioStream? {
        if (audios.isEmpty()) return null
        return audios.maxByOrNull { audioRank(it, preferredLang) }
    }

    private fun audioLanguageCode(audio: AudioStream): String {
        audio.audioLocale?.language?.takeIf { it.length >= 2 }?.let {
            return it.lowercase(Locale.US)
        }
        val trackId = audio.audioTrackId?.trim().orEmpty()
        if (trackId.isNotEmpty()) {
            val fromId = trackId.substringBefore('.').substringBefore('-').lowercase(Locale.US)
            if (fromId.length in 2..3 && fromId.all { it.isLetter() }) return fromId
        }
        return "und"
    }

    private fun audioRank(audio: AudioStream, preferredLang: String): Long {
        val code = audioLanguageCode(audio)
        val langMatch = when {
            code.equals(preferredLang, ignoreCase = true) -> 3_000_000L
            preferredLang.startsWith(code, ignoreCase = true) ||
                code.startsWith(preferredLang, ignoreCase = true) -> 2_500_000L
            else -> 0L
        }
        val typeScore = when (audio.audioTrackType) {
            AudioTrackType.ORIGINAL -> 300_000L
            AudioTrackType.DUBBED -> 200_000L
            AudioTrackType.SECONDARY -> 100_000L
            AudioTrackType.DESCRIPTIVE -> 0L
            null -> 150_000L
        }
        val bitrate = (
            audio.averageBitrate.takeIf { it > 0 }
                ?: audio.bitrate.takeIf { it > 0 }
                ?: 0
            ).toLong()
        return langMatch + typeScore + bitrate
    }

    private fun formatAudioLanguageLabel(code: String, stream: AudioStream): String {
        val trackName = stream.audioTrackName?.trim().orEmpty()
        val langName = when {
            code == "und" -> "Неизвестный"
            else -> Locale(code).getDisplayLanguage(Locale("ru"))
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString() }
                .ifBlank { code.uppercase(Locale.US) }
        }
        val typeHint = when (stream.audioTrackType) {
            AudioTrackType.ORIGINAL -> "оригинал"
            AudioTrackType.DUBBED -> "дубляж"
            AudioTrackType.DESCRIPTIVE -> "описание"
            AudioTrackType.SECONDARY -> "доп."
            null -> null
        }
        return buildString {
            append(if (trackName.isNotBlank()) trackName else langName)
            if (typeHint != null) {
                val base = if (trackName.isNotBlank()) trackName else langName
                if (!base.contains(typeHint, ignoreCase = true)) {
                    append(" · ")
                    append(typeHint)
                }
            }
        }
    }

    private fun VideoStream.toDownloadOption(): StreamOption {
        val heightLabel = getResolution().takeIf { it.isNotBlank() && it != VideoStream.RESOLUTION_UNKNOWN }
            ?: "видео"
        val container = format?.suffix ?: "mp4"
        return StreamOption(
            formatId = itag,
            label = "$heightLabel · $container",
            mimeType = format?.mimeType ?: "video/mp4",
            quality = heightLabel,
            resolution = heightLabel,
            bitrate = bitrate.takeIf { it > 0 },
            sizeBytes = null,
            isVideoOnly = false,
            isAudioOnly = false,
            url = content
        )
    }

    private fun AudioStream.toDownloadOption(): StreamOption {
        val average = averageBitrate.takeIf { it > 0 } ?: bitrate.takeIf { it > 0 }
        val container = format?.suffix ?: "m4a"
        val label = buildString {
            append("Только аудио")
            average?.let { append(" · $it кбит/с") }
            append(" · ")
            append(container)
        }
        return StreamOption(
            formatId = itag,
            label = label,
            mimeType = format?.mimeType ?: "audio/mp4",
            quality = "audio",
            resolution = null,
            bitrate = average,
            sizeBytes = null,
            isVideoOnly = false,
            isAudioOnly = true,
            url = content
        )
    }

    private fun parseHeight(resolution: String?): Int {
        if (resolution.isNullOrBlank()) return 0
        return resolution.filter { it.isDigit() }.toIntOrNull() ?: 0
    }

    companion object {
        private const val MAX_SHORT_DURATION_SEC = 60L

        private val VIDEO_ID_PATTERN = Pattern.compile(
            "(?:youtu\\.be/|youtube\\.com/(?:watch\\?v=|embed/|shorts/|live/|v/))([\\w-]{11})"
        )
        private val CHANNEL_ID_PATTERN = Pattern.compile("youtube\\.com/(?:channel/|c/|@|user/)([^/?&#]+)")

        fun extractVideoId(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.matches(Regex("[\\w-]{11}"))) return trimmed
            val matcher = VIDEO_ID_PATTERN.matcher(trimmed)
            return if (matcher.find()) matcher.group(1) else null
        }

        fun extractChannelId(url: String?): String? {
            if (url.isNullOrBlank()) return null
            val matcher = CHANNEL_ID_PATTERN.matcher(url)
            return if (matcher.find()) matcher.group(1) else url.hashCode().toString()
        }

        fun normalizeUrl(input: String): String {
            val id = extractVideoId(input)
            return if (id != null) "https://www.youtube.com/watch?v=$id" else input.trim()
        }

        fun looksLikeYoutubeUrl(input: String): Boolean {
            val value = input.trim()
            return value.contains("youtu", ignoreCase = true) || value.matches(Regex("[\\w-]{11}"))
        }
    }
}
