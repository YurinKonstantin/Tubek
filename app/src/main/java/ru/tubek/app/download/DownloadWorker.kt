package ru.tubek.app.download

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import okhttp3.Request
import ru.tubek.app.data.DownloadHistoryRepository
import ru.tubek.app.data.DownloadRecord
import ru.tubek.app.network.OkHttpClients
import ru.tubek.app.proxy.ProxyPool
import ru.tubek.app.youtube.YoutubeService
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val client get() = OkHttpClients.download()

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val notificationId = inputData.getInt(KEY_NOTIFICATION_ID, id.hashCode())
        var title = "video"

        return try {
            val job = DownloadJobStore.load(applicationContext, jobId)
            title = job.title.ifBlank { "video" }
            DownloadNotifier.showProgress(applicationContext, notificationId, title, 0)

            val safeName = sanitizeFileName(title) + "." + job.extension
            val savedPath = downloadWithProxyRetry(
                job.url,
                safeName,
                job.mimeType,
                notificationId,
                title
            )

            DownloadHistoryRepository(applicationContext).add(
                DownloadRecord(
                    videoId = job.videoId.ifBlank { title.hashCode().toString() },
                    title = title,
                    uploader = job.uploader,
                    thumbnailUrl = job.thumbnailUrl,
                    qualityLabel = job.qualityLabel.ifBlank { job.extension },
                    filePath = savedPath,
                    mimeType = job.mimeType,
                    isAudioOnly = job.isAudioOnly,
                    videoUrl = job.videoPageUrl.ifBlank { job.url },
                    downloadedAt = System.currentTimeMillis()
                )
            )
            DownloadNotifier.showCompleted(applicationContext, notificationId, title, savedPath)
            Result.success(workDataOf(KEY_PATH to savedPath))
        } catch (t: Throwable) {
            DownloadNotifier.showFailed(
                applicationContext,
                notificationId,
                title,
                t.message ?: "Неизвестная ошибка"
            )
            Result.failure(workDataOf(KEY_ERROR to (t.message ?: "error")))
        } finally {
            DownloadJobStore.delete(applicationContext, jobId)
        }
    }

    private suspend fun downloadWithProxyRetry(
        url: String,
        fileName: String,
        mimeType: String,
        notificationId: Int,
        title: String
    ): String {
        YoutubeService.rebuildProxyClients(applicationContext)
        var lastError: Throwable? = null
        val pool = ProxyPool.get()
        val attempts = if (pool.isEnabled()) 6 else 1
        for (index in 0 until attempts) {
            try {
                return downloadToPublicDownloads(url, fileName, mimeType, notificationId, title)
            } catch (t: Throwable) {
                lastError = t
                if (!pool.isEnabled() || index == attempts - 1) throw t
                pool.switchToNext()
                YoutubeService.rebuildProxyClients(applicationContext)
            }
        }
        throw lastError ?: Exception("Не удалось скачать")
    }

    private fun downloadToPublicDownloads(
        url: String,
        fileName: String,
        mimeType: String,
        notificationId: Int,
        title: String
    ): String {
        val request = buildRequest(url)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: не удалось скачать поток")
            }
            val body = response.body ?: error("Пустой ответ сервера")
            val total = body.contentLength()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Tubik")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = applicationContext.contentResolver
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = resolver.insert(collection, values) ?: error("Не удалось создать файл")
                try {
                    resolver.openOutputStream(uri)?.use { output ->
                        body.byteStream().use { input ->
                            copyWithProgress(input, output, total, notificationId, title)
                        }
                    } ?: error("Не удалось открыть файл для записи")
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    return "Download/Tubik/$fileName"
                } catch (t: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw t
                }
            }

            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Tubik"
            )
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, fileName)
            FileOutputStream(outFile).use { output ->
                body.byteStream().use { input ->
                    copyWithProgress(input, output, total, notificationId, title)
                }
            }
            return outFile.absolutePath
        }
    }

    private fun buildRequest(url: String): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.youtube.com/")
            .header("Origin", "https://www.youtube.com")
            .header("Accept", "*/*")
            .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Connection", "keep-alive")
            .build()

    private fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        total: Long,
        notificationId: Int,
        title: String
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var read: Int
        var downloaded = 0L
        var lastProgress = -1
        while (input.read(buffer).also { read = it } >= 0) {
            output.write(buffer, 0, read)
            downloaded += read
            if (total > 0) {
                val progress = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                if (progress != lastProgress) {
                    lastProgress = progress
                    DownloadNotifier.showProgress(applicationContext, notificationId, title, progress)
                    setProgressAsync(workDataOf(KEY_PROGRESS to progress))
                }
            } else {
                DownloadNotifier.showProgress(applicationContext, notificationId, title, -1)
            }
        }
        output.flush()
        DownloadNotifier.showProgress(applicationContext, notificationId, title, 100)
        setProgressAsync(workDataOf(KEY_PROGRESS to 100))
    }

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_NOTIFICATION_ID = "notification_id"
        const val KEY_PATH = "path"
        const val KEY_ERROR = "error"
        const val KEY_PROGRESS = "progress"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        fun enqueue(
            context: Context,
            url: String,
            title: String,
            mimeType: String,
            extension: String,
            videoId: String = "",
            uploader: String = "",
            thumbnailUrl: String? = null,
            qualityLabel: String = "",
            isAudioOnly: Boolean = false,
            videoPageUrl: String = ""
        ) {
            val jobId = DownloadJobStore.createId()
            DownloadJobStore.save(
                context,
                DownloadJobStore.Job(
                    id = jobId,
                    url = url,
                    audioUrl = null,
                    title = title,
                    mimeType = mimeType,
                    extension = extension,
                    videoId = videoId,
                    uploader = uploader,
                    thumbnailUrl = thumbnailUrl,
                    qualityLabel = qualityLabel,
                    isAudioOnly = isAudioOnly,
                    videoPageUrl = videoPageUrl
                )
            )

            val data = Data.Builder()
                .putString(KEY_JOB_ID, jobId)
                .putInt(KEY_NOTIFICATION_ID, (System.currentTimeMillis() % Int.MAX_VALUE).toInt())
                .build()

            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(data)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "download_$jobId",
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        private fun sanitizeFileName(name: String): String {
            val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            return cleaned.take(80).ifBlank { "tubek_video" }
        }
    }
}
