package ru.tubek.app.proxy

data class ProxyEndpoint(
    val host: String,
    val port: Int,
    val type: Type,
    val source: String,
    /** ISO-2, если известна. */
    val countryCode: String? = null,
    /** Заявленная источником задержка (мс), если есть. */
    val reportedLatencyMs: Long? = null,
    val username: String? = null,
    val password: String? = null
) {
    enum class Type { HTTP, SOCKS5 }

    val key: String get() = "${type.name.lowercase()}://$host:$port"

    val hasCredentials: Boolean
        get() = !username.isNullOrBlank()

    fun toJavaProxy(): java.net.Proxy {
        val proxyType = when (type) {
            Type.HTTP -> java.net.Proxy.Type.HTTP
            Type.SOCKS5 -> java.net.Proxy.Type.SOCKS
        }
        return java.net.Proxy(proxyType, java.net.InetSocketAddress(host, port))
    }
}
