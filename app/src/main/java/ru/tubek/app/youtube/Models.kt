package ru.tubek.app.youtube

data class VideoItem(
    val id: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val url: String,
    val uploaderUrl: String? = null
)

/** Поток для онлайн-просмотра (adaptive: video + audio или muxed). */
data class PlaybackOption(
    val label: String,
    val height: Int,
    val videoUrl: String?,
    val audioUrl: String?,
    val isAudioOnly: Boolean = false,
    val audioLanguageCode: String? = null
)

/** Доступная аудиодорожка (язык / дубляж). */
data class AudioLanguageOption(
    val code: String,
    val label: String,
    val audioUrl: String,
    val isOriginal: Boolean = false
)

data class StreamOption(
    val formatId: Int,
    val label: String,
    val mimeType: String,
    val quality: String,
    val resolution: String?,
    val bitrate: Int?,
    val sizeBytes: Long?,
    val isVideoOnly: Boolean,
    val isAudioOnly: Boolean,
    val url: String
)

data class VideoDetails(
    val item: VideoItem,
    val description: String,
    val viewCount: Long?,
    val channelId: String?,
    val channelUrl: String?,
    val channelAvatarUrl: String?,
    val playbackOptions: List<PlaybackOption>,
    val streams: List<StreamOption>,
    val related: List<VideoItem> = emptyList(),
    val audioLanguages: List<AudioLanguageOption> = emptyList(),
    val selectedAudioLanguage: String? = null
)

fun Long?.formatDuration(): String {
    if (this == null || this <= 0) return ""
    val total = this
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

fun Long?.formatSize(): String {
    if (this == null || this <= 0) return "размер неизвестен"
    val kb = this / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> "%.2f ГБ".format(gb)
        mb >= 1 -> "%.1f МБ".format(mb)
        else -> "%.0f КБ".format(kb)
    }
}
