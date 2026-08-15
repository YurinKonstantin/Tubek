package ru.tubek.app.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.tubek.app.data.PreferencesRepository
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class BetterProxyOffer(
    val endpoint: ProxyEndpoint,
    val latencyMs: Long,
    val currentLatencyMs: Long
)

data class SilentProxySwitch(
    val endpoint: ProxyEndpoint,
    val latencyMs: Long,
    val previousLatencyMs: Long
)

/**
 * Пул бесплатных прокси.
 *
 * 1) Запрос всех источников сразу.
 * 2) Сортировка: своя статистика → заявленный отклик источника.
 * 3) Параллельные пробы; при первом рабочем — отдача наружу.
 * 4) Фоном продолжаем проверку → резерв; при заметно лучшем — тихая смена.
 */
class ProxyPool(
    private val fetcher: ProxyListFetcher = ProxyListFetcher()
) {
    private val mutex = Mutex()
    private val active = AtomicReference<ProxyEndpoint?>(null)
    private val candidates = ArrayDeque<ProxyEndpoint>()
    private val verifiedBackups = ArrayDeque<ProxyEndpoint>()
    private var enabled = true
    private var source: ProxySource = ProxySource.ALL
    private val timeoutSec = AtomicInteger(PreferencesRepository.DEFAULT_PROXY_TIMEOUT_SEC)
    private var statsStore: ProxyStatsStore? = null
    private var backgroundScope: CoroutineScope? = null
    private var backgroundScanJob: Job? = null
    private val scanning = AtomicBoolean(false)
    private val activeLatencyMs = AtomicLong(0)
    private val pendingBetter = AtomicReference<BetterProxyOffer?>(null)
    private val _betterProxyOffer = MutableStateFlow<BetterProxyOffer?>(null)
    val betterProxyOffer: StateFlow<BetterProxyOffer?> = _betterProxyOffer.asStateFlow()

    private val _silentSwitch = MutableSharedFlow<SilentProxySwitch>(extraBufferCapacity = 4)
    val silentSwitch: SharedFlow<SilentProxySwitch> = _silentSwitch.asSharedFlow()

    private var customEndpoint: ProxyEndpoint? = null
    private var customMode: CustomProxyMode = CustomProxyMode.OFF

    fun attachStats(store: ProxyStatsStore) {
        statsStore = store
    }

    fun attachScope(scope: CoroutineScope) {
        backgroundScope = scope
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            active.set(null)
            activeLatencyMs.set(0)
            clearBetterOffer()
            backgroundScanJob?.cancel()
            verifiedBackups.clear()
        }
    }

    fun setResponseTimeoutSec(seconds: Int) {
        timeoutSec.set(
            seconds.coerceIn(
                PreferencesRepository.MIN_PROXY_TIMEOUT_SEC,
                PreferencesRepository.MAX_PROXY_TIMEOUT_SEC
            )
        )
    }

    fun responseTimeoutSec(): Long = timeoutSec.get().toLong()

    fun setSource(value: ProxySource) {
        if (source != value) {
            source = value
            candidates.clear()
            verifiedBackups.clear()
            active.set(null)
            activeLatencyMs.set(0)
            clearBetterOffer()
            backgroundScanJob?.cancel()
        }
    }

    fun setCustomProxy(endpoint: ProxyEndpoint?, mode: CustomProxyMode) {
        val changed = customEndpoint?.key != endpoint?.key ||
            customEndpoint?.username != endpoint?.username ||
            customEndpoint?.password != endpoint?.password ||
            customMode != mode
        customEndpoint = endpoint
        customMode = mode
        if (!changed) return
        clearBetterOffer()
        backgroundScanJob?.cancel()
        when (mode) {
            CustomProxyMode.OFF -> {
                if (active.get()?.source == "custom") {
                    active.set(null)
                    activeLatencyMs.set(0)
                }
            }
            CustomProxyMode.ONLY -> {
                active.set(null)
                activeLatencyMs.set(0)
                candidates.clear()
                verifiedBackups.clear()
            }
            CustomProxyMode.WITH_AUTO -> {
                active.set(null)
                activeLatencyMs.set(0)
                candidates.clear()
                verifiedBackups.clear()
            }
        }
    }

    fun customMode(): CustomProxyMode = customMode

    fun customEndpoint(): ProxyEndpoint? = customEndpoint

    fun currentSource(): ProxySource = source

    fun isEnabled(): Boolean = enabled

    fun current(): ProxyEndpoint? = if (enabled) active.get() else null

    fun currentLatencyMs(): Long = activeLatencyMs.get()

    fun currentJavaProxy(): Proxy? = current()?.toJavaProxy()

    fun applyPendingBetter(): ProxyEndpoint? {
        if (customMode == CustomProxyMode.ONLY) {
            clearBetterOffer()
            return null
        }
        val offer = pendingBetter.getAndSet(null) ?: return null
        _betterProxyOffer.value = null
        active.set(offer.endpoint)
        activeLatencyMs.set(offer.latencyMs)
        return offer.endpoint
    }

    fun clearBetterOffer() {
        pendingBetter.set(null)
        _betterProxyOffer.value = null
    }

    private fun setActive(endpoint: ProxyEndpoint, latencyMs: Long) {
        active.set(endpoint)
        activeLatencyMs.set(latencyMs)
        clearBetterOffer()
    }

    /**
     * Заметно более быстрый прокси из фоновой проверки — тихая смена.
     */
    private fun considerBetter(endpoint: ProxyEndpoint, latencyMs: Long) {
        if (customMode == CustomProxyMode.ONLY) return
        val currentLat = activeLatencyMs.get()
        if (currentLat <= 0) return
        val activeKey = active.get()?.key
        if (endpoint.key == activeKey) return
        val muchFaster = latencyMs <= (currentLat * 0.6) && (currentLat - latencyMs) >= 400
        if (!muchFaster) return
        setActive(endpoint, latencyMs)
        _silentSwitch.tryEmit(SilentProxySwitch(endpoint, latencyMs, currentLat))
    }

    suspend fun ensureReady(): ProxyEndpoint? {
        if (!enabled) return null
        active.get()?.let { return it }
        if (customMode == CustomProxyMode.ONLY) {
            return probeCustomOnly()
        }
        if (customMode == CustomProxyMode.WITH_AUTO) {
            probeCustomPreferred()?.let { return it }
        }
        ensureCandidatesFromAllSources()
        return rotateParallel(
            maxProbes = MAX_PROBES_PER_SWITCH,
            concurrency = PARALLEL_PROBES,
            timeoutSec = FAST_PROBE_TIMEOUT_SEC
        )
    }

    suspend fun forceRefreshListAndRotate(): ProxyEndpoint? {
        if (!enabled) return null
        if (customMode == CustomProxyMode.ONLY) {
            return probeCustomOnly()
        }
        mutex.withLock {
            candidates.clear()
            verifiedBackups.clear()
        }
        if (customMode == CustomProxyMode.WITH_AUTO) {
            probeCustomPreferred()?.let { return it }
        }
        ensureCandidatesFromAllSources()
        return rotateParallel(
            maxProbes = MAX_PROBES_PER_SWITCH,
            concurrency = PARALLEL_PROBES,
            timeoutSec = FAST_PROBE_TIMEOUT_SEC
        )
    }

    suspend fun switchToNext(): ProxyEndpoint? {
        if (!enabled) return null
        if (customMode == CustomProxyMode.ONLY) {
            return probeCustomOnly()
        }
        active.set(null)
        activeLatencyMs.set(0)

        // Сначала проверенный резерв
        val backup = mutex.withLock {
            while (verifiedBackups.isNotEmpty()) {
                val next = verifiedBackups.removeFirst()
                if (next.key != active.get()?.key) return@withLock next
            }
            null
        }
        if (backup != null) {
            val latency = withContext(Dispatchers.IO) {
                probeLatencyMs(backup, FAST_PROBE_TIMEOUT_SEC)
            }
            if (latency != null) {
                setActive(backup, latency)
                statsStore?.recordSuccess(backup, latency)
                scheduleBackgroundScan(excludeKeys = setOf(backup.key))
                return backup
            }
        }

        ensureCandidatesFromAllSources()
        return rotateParallel(
            maxProbes = MAX_PROBES_PER_SWITCH,
            concurrency = PARALLEL_PROBES,
            timeoutSec = FAST_PROBE_TIMEOUT_SEC
        )
    }

    /** Запрос всех источников → сортировка по статистике и заявленному отклику. */
    private suspend fun ensureCandidatesFromAllSources() {
        if (customMode == CustomProxyMode.ONLY) return
        val needFetch = mutex.withLock {
            if (candidates.isNotEmpty()) return
            true
        }
        if (!needFetch) return

        val fresh = withContext(Dispatchers.IO) { fetcher.fetch(source) }
        val ranked = rankCandidates(fresh)
        mutex.withLock {
            fillCandidatesLocked(ranked)
        }
    }

    private suspend fun rotateParallel(
        maxProbes: Int,
        concurrency: Int,
        timeoutSec: Long
    ): ProxyEndpoint? {
        var probed = 0
        val failedKeys = HashSet<String>()
        while (probed < maxProbes) {
            val batch = ArrayList<ProxyEndpoint>()
            mutex.withLock {
                while (batch.size < concurrency && candidates.isNotEmpty()) {
                    val next = candidates.removeFirst()
                    if (next.key !in failedKeys) batch += next
                }
            }
            if (batch.isEmpty()) {
                ensureCandidatesFromAllSources()
                val hasMore = mutex.withLock {
                    candidates.removeAll { it.key in failedKeys }
                    candidates.isNotEmpty()
                }
                if (!hasMore) break
                continue
            }
            probed += batch.size
            failedKeys.addAll(batch.map { it.key })
            val winner = probeBatchParallel(batch, concurrency, timeoutSec)
            if (winner != null) {
                // Данные уже можно грузить; фоном копит резерв и ищет быстрее
                scheduleBackgroundScan(excludeKeys = setOf(winner.key) + failedKeys)
                return winner
            }
        }

        mutex.withLock { candidates.clear() }
        ensureCandidatesFromAllSources()
        mutex.withLock { candidates.removeAll { it.key in failedKeys } }
        val extraBatch = ArrayList<ProxyEndpoint>()
        mutex.withLock {
            while (extraBatch.size < MAX_EXTRA_AFTER_STATS && candidates.isNotEmpty()) {
                extraBatch += candidates.removeFirst()
            }
        }
        val refreshed = probeBatchParallel(extraBatch, concurrency, timeoutSec)
        if (refreshed != null) {
            scheduleBackgroundScan(excludeKeys = setOf(refreshed.key))
            return refreshed
        }

        active.set(null)
        activeLatencyMs.set(0)
        return null
    }

    private suspend fun probeBatchParallel(
        endpoints: List<ProxyEndpoint>,
        concurrency: Int,
        timeoutSec: Long
    ): ProxyEndpoint? {
        if (endpoints.isEmpty()) return null
        return coroutineScope {
            for (chunk in endpoints.chunked(concurrency.coerceAtLeast(1))) {
                val results = chunk.map { ep ->
                    async(Dispatchers.IO) {
                        ep to probeLatencyMs(ep, timeoutSec)
                    }
                }.awaitAll()
                val ok = results
                    .mapNotNull { (ep, lat) -> lat?.let { ep to it } }
                    .minByOrNull { it.second }
                if (ok != null) {
                    setActive(ok.first, ok.second)
                    statsStore?.recordSuccess(ok.first, ok.second)
                    return@coroutineScope ok.first
                }
            }
            null
        }
    }

    suspend fun recordActiveSuccess(latencyMs: Long) {
        val current = active.get() ?: return
        statsStore?.recordSuccess(current, latencyMs)
    }

    private suspend fun probeCustomOnly(): ProxyEndpoint? {
        val custom = customEndpoint ?: return null
        val latency = withContext(Dispatchers.IO) {
            probeLatencyMs(custom, FAST_PROBE_TIMEOUT_SEC)
        }
        return if (latency != null) {
            setActive(custom, latency)
            statsStore?.recordSuccess(custom, latency)
            custom
        } else {
            active.set(null)
            activeLatencyMs.set(0)
            null
        }
    }

    private suspend fun probeCustomPreferred(): ProxyEndpoint? {
        val custom = customEndpoint ?: return null
        val latency = withContext(Dispatchers.IO) {
            probeLatencyMs(custom, FAST_PROBE_TIMEOUT_SEC)
        } ?: return null
        setActive(custom, latency)
        statsStore?.recordSuccess(custom, latency)
        scheduleBackgroundScan(excludeKeys = setOf(custom.key))
        return custom
    }

    private fun fillCandidatesLocked(ranked: List<ProxyEndpoint>) {
        val custom = customEndpoint.takeIf { customMode == CustomProxyMode.WITH_AUTO }
        candidates.clear()
        if (custom != null) {
            candidates.add(custom)
            ranked.filter { it.key != custom.key }.forEach { candidates.add(it) }
        } else {
            candidates.addAll(ranked)
        }
    }

    /**
     * Все источники вместе:
     * 1) успехи endpoint из статистики,
     * 2) страны со статистикой,
     * 3) заявленный отклик источника (меньше — раньше).
     */
    private fun rankCandidates(fresh: List<ProxyEndpoint>): List<ProxyEndpoint> {
        val store = statsStore
        val known = store?.knownGoodEndpoints(MAX_KNOWN_FIRST).orEmpty()
        val merged = LinkedHashMap<String, ProxyEndpoint>()

        fun putBetter(ep: ProxyEndpoint) {
            val prev = merged[ep.key]
            if (prev == null) {
                merged[ep.key] = ep
                return
            }
            val prevRep = prev.reportedLatencyMs ?: Long.MAX_VALUE
            val newRep = ep.reportedLatencyMs ?: Long.MAX_VALUE
            merged[ep.key] = when {
                newRep < prevRep -> ep.copy(
                    countryCode = ep.countryCode ?: prev.countryCode
                )
                prevRep < newRep -> prev.copy(
                    countryCode = prev.countryCode ?: ep.countryCode,
                    reportedLatencyMs = prev.reportedLatencyMs ?: ep.reportedLatencyMs
                )
                else -> prev.copy(
                    countryCode = prev.countryCode ?: ep.countryCode,
                    reportedLatencyMs = prev.reportedLatencyMs ?: ep.reportedLatencyMs
                )
            }
        }

        known.forEach(::putBetter)
        fresh.forEach(::putBetter)

        return merged.values.sortedWith(
            compareByDescending<ProxyEndpoint> { store?.scoreForEndpoint(it.key) ?: 0.0 }
                .thenByDescending { store?.scoreFor(it.countryCode) ?: 0.0 }
                .thenBy {
                    store?.avgLatencyForEndpoint(it.key)
                        ?: it.reportedLatencyMs
                        ?: Long.MAX_VALUE / 2
                }
        )
    }

    /**
     * Фоновая проверка: формирует резерв рабочих; при гораздо лучшем — тихая смена.
     */
    private fun scheduleBackgroundScan(excludeKeys: Set<String>) {
        if (customMode == CustomProxyMode.ONLY) return
        val scope = backgroundScope ?: return
        if (!scanning.compareAndSet(false, true)) return
        backgroundScanJob?.cancel()
        backgroundScanJob = scope.launch {
            try {
                val snapshot = mutex.withLock {
                    candidates.filter { it.key !in excludeKeys }.take(MAX_BACKGROUND_PROBES)
                }
                if (snapshot.isEmpty()) return@launch

                val found = ArrayList<Pair<ProxyEndpoint, Long>>()
                for (chunk in snapshot.chunked(PARALLEL_PROBES)) {
                    if (!enabled) break
                    val results = coroutineScope {
                        chunk.map { ep ->
                            async(Dispatchers.IO) { ep to probeLatencyMs(ep) }
                        }.awaitAll()
                    }
                    for ((ep, latency) in results) {
                        if (latency != null) {
                            statsStore?.recordSuccess(ep, latency)
                            found += ep to latency
                            considerBetter(ep, latency)
                        }
                    }
                }
                if (found.isEmpty()) return@launch

                val sorted = found.sortedBy { it.second }
                mutex.withLock {
                    val foundKeys = sorted.map { it.first.key }.toSet()
                    candidates.removeAll { it.key in foundKeys }
                    verifiedBackups.removeAll { it.key in foundKeys }
                    // Резерв: от быстрых к медленным
                    for (i in sorted.indices.reversed()) {
                        val ep = sorted[i].first
                        if (ep.key != active.get()?.key) {
                            verifiedBackups.addFirst(ep)
                        }
                    }
                    while (verifiedBackups.size > MAX_VERIFIED_BACKUPS) {
                        verifiedBackups.removeLast()
                    }
                    // Также в голову очереди кандидатов
                    for (i in sorted.indices.reversed()) {
                        candidates.addFirst(sorted[i].first)
                    }
                }
            } finally {
                scanning.set(false)
            }
        }
    }

    private fun probeLatencyMs(
        endpoint: ProxyEndpoint,
        timeoutSec: Long = responseTimeoutSec()
    ): Long? {
        val sec = timeoutSec.coerceAtLeast(1L)
        return try {
            val started = System.nanoTime()
            val builder = OkHttpClient.Builder()
                .proxy(endpoint.toJavaProxy())
                .connectTimeout(sec, TimeUnit.SECONDS)
                .readTimeout(sec, TimeUnit.SECONDS)
                .writeTimeout(sec, TimeUnit.SECONDS)
                .callTimeout(sec, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
            val user = endpoint.username?.takeIf { it.isNotBlank() }
            if (user != null && endpoint.type == ProxyEndpoint.Type.HTTP) {
                val credential = okhttp3.Credentials.basic(user, endpoint.password.orEmpty())
                builder.proxyAuthenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") != null) {
                        null
                    } else {
                        response.request.newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build()
                    }
                }
            }
            val client = builder.build()
            val request = Request.Builder()
                .url("https://www.youtube.com/generate_204")
                .header("User-Agent", "Tubik/1.0")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code !in 200..399) return null
                ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun resetSessionLimits() {
        candidates.clear()
    }

    companion object {
        private const val MAX_PROBES_PER_SWITCH = 16
        private const val MAX_EXTRA_AFTER_STATS = 16
        private const val MAX_KNOWN_FIRST = 40
        private const val MAX_BACKGROUND_PROBES = 48
        private const val MAX_VERIFIED_BACKUPS = 20
        private const val PARALLEL_PROBES = 6
        private const val FAST_PROBE_TIMEOUT_SEC = 2L

        @Volatile
        private var instance: ProxyPool? = null

        fun get(): ProxyPool {
            return instance ?: synchronized(this) {
                instance ?: ProxyPool().also { instance = it }
            }
        }
    }
}
