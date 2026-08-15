package ru.tubek.app.proxy

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private val Context.proxyStatsStore by preferencesDataStore(name = "tubik_proxy_stats")

data class CountryProxyStat(
    val countryCode: String,
    val successCount: Int,
    val totalLatencyMs: Long
) {
    val avgLatencyMs: Long
        get() = if (successCount <= 0) Long.MAX_VALUE else totalLatencyMs / successCount

    val score: Double
        get() {
            if (successCount <= 0) return 0.0
            val latencySec = (avgLatencyMs.coerceAtLeast(1).toDouble()) / 1000.0
            return successCount.toDouble() / latencySec
        }
}

data class EndpointProxyStat(
    val key: String,
    val host: String,
    val port: Int,
    val type: String,
    val countryCode: String?,
    val source: String,
    val successCount: Int,
    val totalLatencyMs: Long
) {
    val avgLatencyMs: Long
        get() = if (successCount <= 0) Long.MAX_VALUE else totalLatencyMs / successCount

    val score: Double
        get() {
            if (successCount <= 0) return 0.0
            val latencySec = (avgLatencyMs.coerceAtLeast(1).toDouble()) / 1000.0
            return successCount.toDouble() / latencySec
        }

    fun toEndpoint(): ProxyEndpoint {
        val t = when (type.uppercase(Locale.US)) {
            "SOCKS5", "SOCKS" -> ProxyEndpoint.Type.SOCKS5
            else -> ProxyEndpoint.Type.HTTP
        }
        return ProxyEndpoint(host, port, t, source.ifBlank { "known" }, countryCode)
    }
}

/**
 * Статистика успешных прокси: по странам и по конкретным endpoint.
 * Рабочие endpoint при следующей смене идут первыми.
 */
class ProxyStatsStore(private val context: Context) {

    private val countryKey = stringPreferencesKey("country_stats_v1")
    private val endpointKey = stringPreferencesKey("endpoint_stats_v1")
    private val countryMemory = ConcurrentHashMap<String, CountryProxyStat>()
    private val endpointMemory = ConcurrentHashMap<String, EndpointProxyStat>()

    val statsFlow: Flow<List<CountryProxyStat>> = context.proxyStatsStore.data.map { prefs ->
        parseCountries(prefs[countryKey]).values.sortedByDescending { it.score }
    }

    suspend fun loadIntoMemory() {
        val prefs = context.proxyStatsStore.data.first()
        countryMemory.clear()
        countryMemory.putAll(parseCountries(prefs[countryKey]))
        endpointMemory.clear()
        endpointMemory.putAll(parseEndpoints(prefs[endpointKey]))
    }

    fun snapshot(): List<CountryProxyStat> =
        countryMemory.values.sortedByDescending { it.score }

    fun preferredCountryCodes(): List<String> =
        snapshot().map { it.countryCode }

    fun scoreFor(countryCode: String?): Double {
        if (countryCode.isNullOrBlank()) return 0.0
        return countryMemory[countryCode.uppercase(Locale.US)]?.score ?: 0.0
    }

    fun scoreForEndpoint(key: String): Double =
        endpointMemory[key]?.score ?: 0.0

    fun avgLatencyForEndpoint(key: String): Long? =
        endpointMemory[key]?.takeIf { it.successCount > 0 }?.avgLatencyMs

    /** Известные рабочие прокси, от лучших к худшим. */
    fun knownGoodEndpoints(limit: Int = 40): List<ProxyEndpoint> =
        endpointMemory.values
            .sortedByDescending { it.score }
            .take(limit)
            .map { it.toEndpoint() }

    suspend fun recordSuccess(endpoint: ProxyEndpoint, latencyMs: Long) {
        recordCountrySuccess(endpoint.countryCode, latencyMs)
        recordEndpointSuccess(endpoint, latencyMs)
    }

    private suspend fun recordCountrySuccess(countryCode: String?, latencyMs: Long) {
        val code = countryCode?.takeIf { it.length == 2 }?.uppercase(Locale.US) ?: return
        val latency = latencyMs.coerceAtLeast(1)
        val prev = countryMemory[code]
        countryMemory[code] = CountryProxyStat(
            countryCode = code,
            successCount = (prev?.successCount ?: 0) + 1,
            totalLatencyMs = (prev?.totalLatencyMs ?: 0L) + latency
        )
        persistCountries()
    }

    private suspend fun recordEndpointSuccess(endpoint: ProxyEndpoint, latencyMs: Long) {
        val latency = latencyMs.coerceAtLeast(1)
        val key = endpoint.key
        val prev = endpointMemory[key]
        endpointMemory[key] = EndpointProxyStat(
            key = key,
            host = endpoint.host,
            port = endpoint.port,
            type = endpoint.type.name,
            countryCode = endpoint.countryCode,
            source = endpoint.source,
            successCount = (prev?.successCount ?: 0) + 1,
            totalLatencyMs = (prev?.totalLatencyMs ?: 0L) + latency
        )
        // Ограничиваем историю
        if (endpointMemory.size > MAX_ENDPOINTS) {
            val drop = endpointMemory.values
                .sortedBy { it.score }
                .take(endpointMemory.size - MAX_ENDPOINTS)
            drop.forEach { endpointMemory.remove(it.key) }
        }
        persistEndpoints()
    }

    private suspend fun persistCountries() {
        val json = JSONObject()
        countryMemory.values.forEach { stat ->
            json.put(
                stat.countryCode,
                JSONObject()
                    .put("successCount", stat.successCount)
                    .put("totalLatencyMs", stat.totalLatencyMs)
            )
        }
        context.proxyStatsStore.edit { prefs ->
            prefs[countryKey] = json.toString()
        }
    }

    private suspend fun persistEndpoints() {
        val json = JSONObject()
        endpointMemory.values.forEach { stat ->
            json.put(
                stat.key,
                JSONObject()
                    .put("host", stat.host)
                    .put("port", stat.port)
                    .put("type", stat.type)
                    .put("countryCode", stat.countryCode ?: "")
                    .put("source", stat.source)
                    .put("successCount", stat.successCount)
                    .put("totalLatencyMs", stat.totalLatencyMs)
            )
        }
        context.proxyStatsStore.edit { prefs ->
            prefs[endpointKey] = json.toString()
        }
    }

    private fun parseCountries(raw: String?): Map<String, CountryProxyStat> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            val map = LinkedHashMap<String, CountryProxyStat>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val code = keys.next()
                val obj = root.optJSONObject(code) ?: continue
                map[code] = CountryProxyStat(
                    countryCode = code,
                    successCount = obj.optInt("successCount", 0),
                    totalLatencyMs = obj.optLong("totalLatencyMs", 0L)
                )
            }
            map
        }.getOrDefault(emptyMap())
    }

    private fun parseEndpoints(raw: String?): Map<String, EndpointProxyStat> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            val map = LinkedHashMap<String, EndpointProxyStat>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = root.optJSONObject(key) ?: continue
                val host = obj.optString("host")
                val port = obj.optInt("port", -1)
                if (host.isBlank() || port <= 0) continue
                map[key] = EndpointProxyStat(
                    key = key,
                    host = host,
                    port = port,
                    type = obj.optString("type", "HTTP"),
                    countryCode = obj.optString("countryCode").takeIf { it.length == 2 },
                    source = obj.optString("source", "known"),
                    successCount = obj.optInt("successCount", 0),
                    totalLatencyMs = obj.optLong("totalLatencyMs", 0L)
                )
            }
            map
        }.getOrDefault(emptyMap())
    }

    companion object {
        private const val MAX_ENDPOINTS = 80
    }
}
