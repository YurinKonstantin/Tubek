package ru.tubek.app.proxy

/**
 * Режим пользовательского прокси.
 */
enum class CustomProxyMode(val id: String, val labelRu: String) {
    OFF("off", "Не использовать"),
    ONLY("only", "Только свой"),
    WITH_AUTO("with_auto", "Свой + автопереключение");

    companion object {
        fun fromId(id: String?): CustomProxyMode =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: OFF
    }
}

/**
 * Разбор адреса: `host:port`, `user:pass@host:port`, `http://host:port`, `socks5://user:pass@host:port`.
 */
object CustomProxyParser {
    fun parse(
        raw: String,
        usernameOverride: String? = null,
        passwordOverride: String? = null
    ): ProxyEndpoint? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        var type = ProxyEndpoint.Type.HTTP
        var rest = trimmed

        val lower = trimmed.lowercase()
        when {
            lower.startsWith("socks5://") -> {
                type = ProxyEndpoint.Type.SOCKS5
                rest = trimmed.substringAfter("://")
            }
            lower.startsWith("socks://") -> {
                type = ProxyEndpoint.Type.SOCKS5
                rest = trimmed.substringAfter("://")
            }
            lower.startsWith("http://") -> {
                type = ProxyEndpoint.Type.HTTP
                rest = trimmed.substringAfter("://")
            }
            lower.startsWith("https://") -> {
                type = ProxyEndpoint.Type.HTTP
                rest = trimmed.substringAfter("://")
            }
        }

        rest = rest.substringBefore('/').substringBefore('?').trim()

        var userFromUrl: String? = null
        var passFromUrl: String? = null
        val at = rest.lastIndexOf('@')
        if (at > 0) {
            val creds = rest.substring(0, at)
            rest = rest.substring(at + 1)
            val colon = creds.indexOf(':')
            if (colon >= 0) {
                userFromUrl = creds.substring(0, colon).ifBlank { null }
                passFromUrl = creds.substring(colon + 1)
            } else {
                userFromUrl = creds.ifBlank { null }
            }
        }

        val host: String
        val port: Int
        if (rest.startsWith("[")) {
            val close = rest.indexOf(']')
            if (close <= 1) return null
            host = rest.substring(1, close)
            val portPart = rest.substring(close + 1).removePrefix(":").trim()
            port = portPart.toIntOrNull() ?: return null
        } else {
            val colon = rest.lastIndexOf(':')
            if (colon <= 0 || colon >= rest.length - 1) return null
            host = rest.substring(0, colon).trim()
            port = rest.substring(colon + 1).trim().toIntOrNull() ?: return null
        }
        if (port !in 1..65535 || host.isBlank()) return null

        val username = usernameOverride?.trim()?.takeIf { it.isNotEmpty() } ?: userFromUrl
        val password = passwordOverride?.takeIf { !username.isNullOrBlank() } ?: passFromUrl

        return ProxyEndpoint(
            host = host,
            port = port,
            type = type,
            source = "custom",
            username = username,
            password = password
        )
    }

    fun format(endpoint: ProxyEndpoint): String {
        val scheme = when (endpoint.type) {
            ProxyEndpoint.Type.HTTP -> "http"
            ProxyEndpoint.Type.SOCKS5 -> "socks5"
        }
        return "$scheme://${endpoint.host}:${endpoint.port}"
    }
}
