package ru.tubek.app.download

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import ru.tubek.app.R

object DownloadNotifier {
    const val CHANNEL_ID = "tubek_downloads"

    fun showProgress(context: Context, id: Int, title: String, progress: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Скачивание")
            .setContentText(title)
            .setOnlyAlertOnce(true)
            .setOngoing(progress in 0..99)
            .setProgress(100, progress.coerceIn(0, 100), progress < 0)
            .build()
        manager(context).notify(id, notification)
    }

    fun showCompleted(context: Context, id: Int, title: String, path: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Готово")
            .setContentText("$title\n$path")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title\nСохранено: $path"))
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        manager(context).notify(id, notification)
    }

    fun showFailed(context: Context, id: Int, title: String, error: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Ошибка загрузки")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(error))
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        manager(context).notify(id, notification)
    }

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)
}
