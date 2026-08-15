package ru.tubek.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import ru.tubek.app.MainActivity
import ru.tubek.app.R
import ru.tubek.app.data.PreferencesRepository
import ru.tubek.app.data.SubscriptionRepository
import ru.tubek.app.proxy.withProxyFallback
import ru.tubek.app.youtube.YoutubeRepository
import java.util.concurrent.TimeUnit

class NewVideoCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = PreferencesRepository(applicationContext)
        if (!prefs.notifyNewVideos.first()) return Result.success()

        val subscriptions = SubscriptionRepository(applicationContext).getAll()
            .filter { it.notifyEnabled }
        if (subscriptions.isEmpty()) return Result.success()

        val repo = YoutubeRepository()
        val subRepo = SubscriptionRepository(applicationContext)
        ensureChannel(applicationContext)

        subscriptions.forEach { sub ->
            val latest = withProxyFallback(applicationContext) {
                repo.channelVideos(sub.channelUrl, limit = 1).firstOrNull()
            }.getOrNull() ?: return@forEach

            if (latest.id != sub.lastSeenVideoId) {
                if (sub.lastSeenVideoId != null) {
                    showNotification(applicationContext, sub.name, latest.title, latest.url)
                }
                subRepo.setLastSeenVideoId(sub.channelId, latest.id)
            }
        }
        return Result.success()
    }

    companion object {
        const val CHANNEL_ID = "tubek_subscriptions"
        private const val WORK_NAME = "new_video_check"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<NewVideoCheckWorker>(2, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Новые видео",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Уведомления о новых роликах с подписок"
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        private fun showNotification(
            context: Context,
            channelName: String,
            title: String,
            videoUrl: String
        ) {
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_SHARED_URL, videoUrl)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                context,
                videoUrl.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(channelName)
                .setContentText(title)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(videoUrl.hashCode(), notification)
        }
    }
}
