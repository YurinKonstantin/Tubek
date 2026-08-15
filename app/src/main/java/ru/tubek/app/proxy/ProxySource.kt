package ru.tubek.app.proxy

enum class ProxySource(val id: String, val labelRu: String) {
    ALL("all", "Все источники"),
    PROXYSCRAPE("proxyscrape", "ProxyScrape"),
    GEONODE("geonode", "Geonode"),
    BEST_PROXIES("best-proxies", "Best-Proxies"),
    GEONIX("geonix", "Geonix Free");

    companion object {
        fun fromId(id: String?): ProxySource =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: ALL
    }
}
