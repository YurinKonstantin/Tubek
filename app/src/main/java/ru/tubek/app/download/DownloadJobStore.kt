package ru.tubek.app.download

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * WorkManager ограничивает InputData ~10 КБ, а URL YouTube часто длиннее.
 * Параметры задания храним в файле кэша, в Worker передаём только id.
 */
object DownloadJobStore {

    data class Job(
        val id: String,
        val url: String,
        val audioUrl: String?,
        val title: String,
        val mimeType: String,
        val extension: String,
        val videoId: String,
        val uploader: String,
        val thumbnailUrl: String?,
        val qualityLabel: String,
        val isAudioOnly: Boolean,
        val videoPageUrl: String
    )

    fun save(context: Context, job: Job): String {
        val dir = jobsDir(context)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "${job.id}.json")
        val json = JSONObject()
            .put("id", job.id)
            .put("url", job.url)
            .put("audioUrl", job.audioUrl)
            .put("title", job.title)
            .put("mimeType", job.mimeType)
            .put("extension", job.extension)
            .put("videoId", job.videoId)
            .put("uploader", job.uploader)
            .put("thumbnailUrl", job.thumbnailUrl)
            .put("qualityLabel", job.qualityLabel)
            .put("isAudioOnly", job.isAudioOnly)
            .put("videoPageUrl", job.videoPageUrl)
        file.writeText(json.toString())
        return job.id
    }

    fun createId(): String = UUID.randomUUID().toString()

    fun load(context: Context, id: String): Job {
        val file = File(jobsDir(context), "$id.json")
        if (!file.exists()) error("Задание загрузки не найдено")
        val json = JSONObject(file.readText())
        return Job(
            id = json.getString("id"),
            url = json.getString("url"),
            audioUrl = json.optString("audioUrl", "").takeIf { it.isNotBlank() && it != "null" },
            title = json.optString("title", "video"),
            mimeType = json.optString("mimeType", "video/mp4"),
            extension = json.optString("extension", "mp4"),
            videoId = json.optString("videoId", ""),
            uploader = json.optString("uploader", ""),
            thumbnailUrl = json.optString("thumbnailUrl", "").takeIf { it.isNotBlank() && it != "null" },
            qualityLabel = json.optString("qualityLabel", ""),
            isAudioOnly = json.optBoolean("isAudioOnly", false),
            videoPageUrl = json.optString("videoPageUrl", "")
        )
    }

    fun delete(context: Context, id: String) {
        runCatching { File(jobsDir(context), "$id.json").delete() }
    }

    private fun jobsDir(context: Context): File = File(context.cacheDir, "download_jobs")
}
