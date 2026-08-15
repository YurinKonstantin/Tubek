package ru.tubek.app.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

class ProxyListFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    /** Всегда запрашивает выбранные источники (для ALL — все параллельно) и объединяет. */
    suspend fun fetch(source: ProxySource): List<ProxyEndpoint> = withContext(Dispatchers.IO) {
        when (source) {
            ProxySource.ALL -> fetchAllMerged()
            ProxySource.PROXYSCRAPE -> runCatching { fetchProxyScrape() }.getOrDefault(emptyList())
            ProxySource.GEONODE -> runCatching { fetchGeonode() }.getOrDefault(emptyList())
            ProxySource.BEST_PROXIES -> runCatching { fetchBestProxies() }.getOrDefault(emptyList())
            ProxySource.GEONIX -> runCatching { fetchGeonix() }.getOrDefault(emptyList())
        }
    }

    private suspend fun fetchAllMerged(): List<ProxyEndpoint> = coroutineScope {
        listOf(
            async { runCatching { fetchProxyScrape() }.getOrDefault(emptyList()) },
            async { runCatching { fetchGeonode() }.getOrDefault(emptyList()) },
            async { runCatching { fetchBestProxies() }.getOrDefault(emptyList()) },
            async { runCatching { fetchGeonix() }.getOrDefault(emptyList()) }
        ).awaitAll()
            .flatten()
            .groupBy { it.key }
            .map { (_, copies) ->
                // Один ключ из разных источников — берём с лучшим (меньшим) откликом
                copies.minByOrNull { it.reportedLatencyMs ?: Long.MAX_VALUE } ?: copies.first()
            }
    }

    private fun fetchProxyScrape(): List<ProxyEndpoint> {
        val url =
            "https://api.proxyscrape.com/v4/free-proxy-list/get" +
                "?request=display_proxies&protocol=http,socks5&timeout=5000" +
                "&anonymity=elite,anonymous&format=json"
        val body = httpGet(url) ?: return emptyList()
        val root = JSONObject(body)
        val proxies = root.optJSONArray("proxies") ?: return emptyList()
        val result = ArrayList<ProxyEndpoint>()
        for (i in 0 until minOf(proxies.length(), 200)) {
            val item = proxies.optJSONObject(i) ?: continue
            val host = item.optString("ip").ifBlank { item.optString("proxy_address") }
            val port = item.optInt("port", -1)
            if (host.isBlank() || port <= 0) continue
            val protocol = item.optString("protocol").lowercase()
            val type = when {
                protocol.contains("socks") -> ProxyEndpoint.Type.SOCKS5
                else -> ProxyEndpoint.Type.HTTP
            }
            val country = item.optJSONObject("ip_data")
                ?.optString("countryCode")
                ?.takeIf { it.length == 2 }
                ?.uppercase(Locale.US)
            val latency = readLatencyMs(
                item,
                "timeout", "average", "avg", "latency", "response_time",
                "responseTime", "speed", "ping"
            )
            result += ProxyEndpoint(host, port, type, "proxyscrape", country, latency)
        }
        return result
    }

    private fun fetchGeonode(): List<ProxyEndpoint> {
        val url =
            "https://proxylist.geonode.com/api/proxy-list" +
                "?limit=200&page=1&sort_by=latency&sort_type=asc"
        val body = httpGet(url) ?: return emptyList()
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        val result = ArrayList<ProxyEndpoint>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val host = item.optString("ip")
            val port = item.optInt("port", -1)
            if (host.isBlank() || port <= 0) continue
            val protocols = item.optJSONArray("protocols")
            val protocol = protocols?.optString(0)?.lowercase().orEmpty()
            val type = when {
                protocol.contains("socks5") || protocol.contains("socks4") -> ProxyEndpoint.Type.SOCKS5
                else -> ProxyEndpoint.Type.HTTP
            }
            val country = item.optString("country").takeIf { it.length == 2 }?.uppercase(Locale.US)
            val latency = readLatencyMs(
                item,
                "latency", "responseTime", "avgResponseTime", "speed", "ping", "timeout"
            )
            result += ProxyEndpoint(host, port, type, "geonode", country, latency)
        }
        return result
    }

    /** Бесплатный тестовый ключ developer — до 10 адресов за запрос. */
    private fun fetchBestProxies(): List<ProxyEndpoint> {
        val url =
            "https://api.best-proxies.ru/proxylist.json" +
                "?key=developer&type=http,https,socks4,socks5&limit=10"
        val body = httpGet(url) ?: return emptyList()
        val array = JSONArray(body)
        val result = ArrayList<ProxyEndpoint>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val host = item.optString("ip")
            val port = item.optInt("port", -1)
            if (host.isBlank() || port <= 0) continue
            val type = when {
                item.optInt("socks5", 0) == 1 || item.optInt("socks4", 0) == 1 ->
                    ProxyEndpoint.Type.SOCKS5
                else -> ProxyEndpoint.Type.HTTP
            }
            val country = item.optString("country_code")
                .takeIf { it.length == 2 }
                ?.uppercase(Locale.US)
            val latency = readLatencyMs(
                item,
                "timeout", "ping", "latency", "response_time", "speed"
            )
            result += ProxyEndpoint(host, port, type, "best-proxies", country, latency)
        }
        return result
    }

    /**
     * free.geonix.com — экспорт списка IP:PORT (без капчи, если isCaptchaActive=false).
     */
    private fun fetchGeonix(): List<ProxyEndpoint> {
        val captchaBody = httpGet("https://free.geonix.com/api/front/main/captcha/info")
            ?: return emptyList()
        val captchaKey = JSONObject(captchaBody).optString("captchaKey")
        val payload = JSONObject()
            .put("captchaKey", captchaKey)
            .put("countries", JSONArray())
            .put("proxyProtocols", JSONArray())
            .put("proxyTypes", JSONArray())
            .toString()
        val exportBody = httpPost(
            url = "https://free.geonix.com/api/front/main/proxy/export",
            json = payload
        ) ?: return emptyList()

        val array = JSONArray(exportBody)
        val result = ArrayList<ProxyEndpoint>()
        val limit = minOf(array.length(), 200)
        for (i in 0 until limit) {
            val raw = array.optString(i).trim()
            val sep = raw.lastIndexOf(':')
            if (sep <= 0 || sep >= raw.length - 1) continue
            val host = raw.substring(0, sep)
            val port = raw.substring(sep + 1).toIntOrNull() ?: continue
            if (host.isBlank() || port <= 0) continue
            result += ProxyEndpoint(host, port, ProxyEndpoint.Type.HTTP, "geonix", null)
        }

        enrichGeonixMeta(result)
        return result
    }

    private fun enrichGeonixMeta(endpoints: MutableList<ProxyEndpoint>) {
        if (endpoints.isEmpty()) return
        val payload = JSONObject()
            .put("page", 0)
            .put("size", minOf(endpoints.size, 100))
            .put("countries", JSONArray())
            .put("proxyProtocols", JSONArray())
            .put("proxyTypes", JSONArray())
            .toString()
        val body = httpPost(
            url = "https://free.geonix.com/api/front/main/pagination/filtration",
            json = payload
        ) ?: return
        val content = JSONObject(body).optJSONArray("content") ?: return
        data class Meta(
            val type: ProxyEndpoint.Type,
            val country: String?,
            val latency: Long?
        )
        val byIp = HashMap<String, Meta>()
        for (i in 0 until content.length()) {
            val item = content.optJSONObject(i) ?: continue
            val ip = item.optString("ip")
            if (ip.isBlank()) continue
            val protocol = item.optString("proxyType").lowercase(Locale.US)
            val type = when {
                protocol.contains("socks") -> ProxyEndpoint.Type.SOCKS5
                else -> ProxyEndpoint.Type.HTTP
            }
            val countryName = item.optString("country")
            val latency = readLatencyMs(
                item,
                "responseTime", "latency", "ping", "speed", "timeout", "avgResponseTime"
            )
            byIp[ip] = Meta(type, countryNameToCode(countryName), latency)
        }
        for (i in endpoints.indices) {
            val ep = endpoints[i]
            val meta = byIp[ep.host] ?: continue
            endpoints[i] = ep.copy(
                type = meta.type,
                countryCode = meta.country,
                reportedLatencyMs = meta.latency ?: ep.reportedLatencyMs
            )
        }
    }

    private fun readLatencyMs(item: JSONObject, vararg keys: String): Long? {
        for (key in keys) {
            if (!item.has(key) || item.isNull(key)) continue
            val raw = item.opt(key) ?: continue
            val value = when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.trim().replace(',', '.').toDoubleOrNull() ?: continue
                else -> continue
            }
            if (value <= 0) continue
            // Если похоже на секунды (< 30 и дробное/маленькое) — в мс
            val ms = when {
                value < 30.0 && key.contains("sec", ignoreCase = true) -> (value * 1000).toLong()
                value < 10.0 && !key.contains("timeout", ignoreCase = true) -> (value * 1000).toLong()
                else -> value.toLong()
            }
            if (ms in 1..60_000) return ms
        }
        return null
    }

    private fun countryNameToCode(name: String): String? {
        if (name.length == 2) return name.uppercase(Locale.US)
        val map = mapOf(
            "united states" to "US",
            "united states of america" to "US",
            "russia" to "RU",
            "russian federation" to "RU",
            "germany" to "DE",
            "france" to "FR",
            "united kingdom" to "GB",
            "china" to "CN",
            "netherlands" to "NL",
            "singapore" to "SG",
            "poland" to "PL",
            "ukraine" to "UA",
            "turkey" to "TR",
            "india" to "IN",
            "brazil" to "BR",
            "japan" to "JP",
            "hong kong" to "HK",
            "indonesia" to "ID",
            "thailand" to "TH",
            "taiwan" to "TW",
            "uae" to "AE",
            "united arab emirates" to "AE"
        )
        return map[name.lowercase(Locale.US)]
    }

    private fun httpGet(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    private fun httpPost(url: String, json: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", "https://free.geonix.com")
            .header("Referer", "https://free.geonix.com/")
            .header("Content-Type", "application/json")
            .post(json.toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
