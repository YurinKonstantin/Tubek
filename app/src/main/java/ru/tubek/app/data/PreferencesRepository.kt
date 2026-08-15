package ru.tubek.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.tubek.app.proxy.CustomProxyMode
import ru.tubek.app.proxy.ProxySource
import java.util.Locale

private val Context.dataStore by preferencesDataStore(name = "tubek_prefs")

class PreferencesRepository(private val context: Context) {

    private val consentKey = booleanPreferencesKey("consent_accepted_v1")
    private val proxyEnabledKey = booleanPreferencesKey("proxy_enabled_v1")
    private val proxyTimeoutSecKey = intPreferencesKey("proxy_timeout_sec_v1")
    private val proxySourceKey = stringPreferencesKey("proxy_source_v1")
    private val notifyNewVideosKey = booleanPreferencesKey("notify_new_videos_v1")
    private val audioOnlyOnBackgroundKey = booleanPreferencesKey("audio_only_on_background_v1")
    /** AUTO или ISO-2 код страны (US, RU, …). */
    private val contentCountryKey = stringPreferencesKey("content_country_v1")
    private val recentSearchesKey = stringPreferencesKey("recent_searches_v1")
    private val preferredPlaybackHeightKey = intPreferencesKey("preferred_playback_height_v1")
    private val customProxyAddressKey = stringPreferencesKey("custom_proxy_address_v1")
    private val customProxyModeKey = stringPreferencesKey("custom_proxy_mode_v1")
    private val preferredAudioLanguageKey = stringPreferencesKey("preferred_audio_language_v1")

    val consentAccepted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[consentKey] == true
    }

    val proxyEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[proxyEnabledKey] != false
    }

    val proxyTimeoutSec: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[proxyTimeoutSecKey] ?: DEFAULT_PROXY_TIMEOUT_SEC).coerceIn(MIN_PROXY_TIMEOUT_SEC, MAX_PROXY_TIMEOUT_SEC)
    }

    val proxySource: Flow<ProxySource> = context.dataStore.data.map { prefs ->
        ProxySource.fromId(prefs[proxySourceKey])
    }

    val customProxyAddress: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[customProxyAddressKey].orEmpty()
    }

    val customProxyMode: Flow<CustomProxyMode> = context.dataStore.data.map { prefs ->
        CustomProxyMode.fromId(prefs[customProxyModeKey])
    }

    val notifyNewVideos: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[notifyNewVideosKey] != false
    }

    val audioOnlyOnBackground: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[audioOnlyOnBackgroundKey] != false
    }

    val contentCountryPreference: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[contentCountryKey] ?: Countries.AUTO
    }

    val recentSearches: Flow<List<String>> = context.dataStore.data.map { prefs ->
        decodeRecentSearches(prefs[recentSearchesKey])
    }

    val preferredPlaybackHeight: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[preferredPlaybackHeightKey] ?: DEFAULT_PLAYBACK_HEIGHT
    }

    /** AUTO или ISO-639 язык (ru, en, …). */
    val preferredAudioLanguage: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[preferredAudioLanguageKey] ?: AUDIO_LANGUAGE_AUTO
    }

    suspend fun acceptConsent() {
        context.dataStore.edit { prefs ->
            prefs[consentKey] = true
        }
    }

    suspend fun setProxyEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[proxyEnabledKey] = enabled
        }
    }

    suspend fun setProxyTimeoutSec(seconds: Int) {
        context.dataStore.edit { prefs ->
            prefs[proxyTimeoutSecKey] =
                seconds.coerceIn(MIN_PROXY_TIMEOUT_SEC, MAX_PROXY_TIMEOUT_SEC)
        }
    }

    suspend fun setProxySource(source: ProxySource) {
        context.dataStore.edit { prefs ->
            prefs[proxySourceKey] = source.id
        }
    }

    suspend fun setCustomProxyAddress(address: String) {
        context.dataStore.edit { prefs ->
            prefs[customProxyAddressKey] = address.trim()
        }
    }

    suspend fun setCustomProxyMode(mode: CustomProxyMode) {
        context.dataStore.edit { prefs ->
            prefs[customProxyModeKey] = mode.id
        }
    }

    suspend fun setNotifyNewVideos(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[notifyNewVideosKey] = enabled
        }
    }

    suspend fun setAudioOnlyOnBackground(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[audioOnlyOnBackgroundKey] = enabled
        }
    }

    suspend fun setContentCountryPreference(code: String) {
        context.dataStore.edit { prefs ->
            prefs[contentCountryKey] = code.uppercase()
        }
    }

    suspend fun addRecentSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = decodeRecentSearches(prefs[recentSearchesKey])
            val updated = (listOf(trimmed) + current.filter { !it.equals(trimmed, ignoreCase = true) })
                .take(MAX_RECENT_SEARCHES)
            prefs[recentSearchesKey] = encodeRecentSearches(updated)
        }
    }

    suspend fun removeRecentSearch(query: String) {
        context.dataStore.edit { prefs ->
            val updated = decodeRecentSearches(prefs[recentSearchesKey])
                .filterNot { it.equals(query, ignoreCase = true) }
            prefs[recentSearchesKey] = encodeRecentSearches(updated)
        }
    }

    suspend fun clearRecentSearches() {
        context.dataStore.edit { prefs ->
            prefs[recentSearchesKey] = ""
        }
    }

    suspend fun setPreferredPlaybackHeight(height: Int) {
        context.dataStore.edit { prefs ->
            prefs[preferredPlaybackHeightKey] = height.coerceIn(144, 4320)
        }
    }

    suspend fun setPreferredAudioLanguage(code: String) {
        context.dataStore.edit { prefs ->
            prefs[preferredAudioLanguageKey] = code.trim().ifBlank { AUDIO_LANGUAGE_AUTO }
        }
    }

    fun resolveAudioLanguageCode(preference: String): String {
        return if (preference.equals(AUDIO_LANGUAGE_AUTO, ignoreCase = true) || preference.isBlank()) {
            Locale.getDefault().language.takeIf { it.length >= 2 } ?: "en"
        } else {
            preference.lowercase()
        }
    }

    fun resolveContentCountryCode(preference: String): String {
        return if (preference.equals(Countries.AUTO, ignoreCase = true) || preference.isBlank()) {
            CountryDetector.detect(context)
        } else {
            preference.uppercase()
        }
    }

    companion object {
        const val DEFAULT_PROXY_TIMEOUT_SEC = 5
        const val MIN_PROXY_TIMEOUT_SEC = 2
        const val MAX_PROXY_TIMEOUT_SEC = 30
        const val MAX_RECENT_SEARCHES = 20
        const val DEFAULT_PLAYBACK_HEIGHT = 360
        const val AUDIO_LANGUAGE_AUTO = "AUTO"

        private fun encodeRecentSearches(items: List<String>): String =
            items.joinToString("\n") { it.replace("\n", " ").trim() }.trim()

        private fun decodeRecentSearches(raw: String?): List<String> =
            raw.orEmpty()
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
    }
}
