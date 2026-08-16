package ru.tubek.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.tubek.app.data.PreferencesRepository
import ru.tubek.app.download.DownloadNotifier
import ru.tubek.app.notify.NewVideoCheckWorker
import ru.tubek.app.proxy.CustomProxyParser
import ru.tubek.app.proxy.ProxyPool
import ru.tubek.app.proxy.ProxyStatsStore
import ru.tubek.app.youtube.YoutubeService

class TubekApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        YoutubeService.init(this)
        val stats = ProxyStatsStore(this)
        val pool = ProxyPool.get()
        pool.attachStats(stats)
        pool.attachScope(appScope)
        // Статистика (в т.ч. последний удачный прокси) должна быть в памяти до прогрева
        appScope.launch {
            stats.loadIntoMemory()
            warmUpProxy(pool)
        }
        createNotificationChannels()
        NewVideoCheckWorker.enqueue(this)
    }

    private suspend fun warmUpProxy(pool: ProxyPool) {
        val prefs = PreferencesRepository(this)
        val enabled = prefs.proxyEnabled.first()
        pool.setEnabled(enabled)
        pool.setResponseTimeoutSec(prefs.proxyTimeoutSec.first())
        pool.setSource(prefs.proxySource.first())
        val address = prefs.customProxyAddress.first()
        val mode = prefs.customProxyMode.first()
        val username = prefs.customProxyUsername.first()
        val password = prefs.customProxyPassword.first()
        pool.setCustomProxy(CustomProxyParser.parse(address, username, password), mode)
        if (!enabled) return
        pool.ensureReady()
        YoutubeService.rebuildProxyClients(this)
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                DownloadNotifier.CHANNEL_ID,
                "Загрузки",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Прогресс скачивания видео"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                NewVideoCheckWorker.CHANNEL_ID,
                "Новые видео",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Уведомления о новых роликах с подписок"
            }
        )
    }
}
