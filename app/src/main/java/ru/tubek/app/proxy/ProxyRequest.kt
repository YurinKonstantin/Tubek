package ru.tubek.app.proxy

import android.content.Context
import ru.tubek.app.youtube.YoutubeService

/**
 * Единый механизм: короткий таймаут → при сбое смена прокси (до [maxTries] раз).
 * Используется лентой, поиском, карточкой видео, плеером и фоновыми задачами.
 *
 * Статусы «ищем другой прокси» показываются только после сбоя текущего подключения,
 * а не при фоновом поиске более быстрых кандидатов.
 */
suspend fun <T> withProxyFallback(
    context: Context,
    maxTries: Int = 6,
    onStatus: (suspend (String) -> Unit)? = null,
    block: suspend () -> T
): Result<T> {
    val pool = ProxyPool.get()
    val app = context.applicationContext
    YoutubeService.rebuildProxyClients(app)

    val hadWorking = pool.current() != null

    if (pool.isEnabled() && !hadWorking) {
        onStatus?.invoke(
            "Ищем рабочее подключение. Это может занять некоторое время…"
        )
        pool.ensureReady()
        YoutubeService.rebuildProxyClients(app)
    }

    val first = runCatching { block() }
    if (first.isSuccess) {
        return first
    }
    if (!pool.isEnabled()) return first

    var lastError: Throwable? = first.exceptionOrNull()
    // Сбой: либо не было прокси, либо текущий перестал работать — ищем другой
    onStatus?.invoke(
        if (hadWorking) {
            "Подключение пропало. Ищем другой прокси…"
        } else {
            "Подключение недоступно. Ищем рабочий прокси…"
        }
    )

    for (i in 0 until maxTries) {
        var next = pool.switchToNext()
        if (next == null) {
            onStatus?.invoke("Список прокси исчерпан. Загружаем новый…")
            next = pool.forceRefreshListAndRotate()
        }
        YoutubeService.rebuildProxyClients(app)

        if (next == null) {
            onStatus?.invoke("Рабочий прокси не найден")
            break
        }

        onStatus?.invoke("Пробуем прокси → ${next.key}")

        val started = System.nanoTime()
        val attempt = runCatching { block() }
        if (attempt.isSuccess) {
            val latencyMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
            pool.recordActiveSuccess(latencyMs)
            return attempt
        }
        lastError = attempt.exceptionOrNull() ?: lastError
        onStatus?.invoke("Прокси не отвечает. Меняем…")
    }
    return Result.failure(lastError ?: first.exceptionOrNull() ?: Exception("Прокси недоступны"))
}
