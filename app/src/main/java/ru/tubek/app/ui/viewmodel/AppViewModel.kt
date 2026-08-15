package ru.tubek.app.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.tubek.app.data.Countries
import ru.tubek.app.data.CountryDetector
import ru.tubek.app.data.DownloadHistoryRepository
import ru.tubek.app.data.DownloadRecord
import ru.tubek.app.data.PreferencesRepository
import ru.tubek.app.data.SubscriptionEntity
import ru.tubek.app.data.SubscriptionRepository
import ru.tubek.app.data.WatchHistoryEntity
import ru.tubek.app.data.WatchHistoryRepository
import ru.tubek.app.download.DownloadWorker
import ru.tubek.app.feed.HomeFeed
import ru.tubek.app.feed.HomeFeedMixer
import ru.tubek.app.feed.ShortsFeedMixer
import ru.tubek.app.player.PlaybackService
import ru.tubek.app.player.PlayerController
import ru.tubek.app.proxy.CustomProxyMode
import ru.tubek.app.proxy.CustomProxyParser
import ru.tubek.app.proxy.ProxyPool
import ru.tubek.app.proxy.ProxySource
import ru.tubek.app.proxy.ProxyStatsStore
import ru.tubek.app.proxy.withProxyFallback
import ru.tubek.app.youtube.PlaybackOption
import ru.tubek.app.youtube.StreamOption
import ru.tubek.app.youtube.VideoDetails
import ru.tubek.app.youtube.VideoItem
import ru.tubek.app.youtube.YoutubeRepository
import ru.tubek.app.youtube.YoutubeService
import androidx.media3.common.PlaybackException

data class FeedUiState(
    val isLoading: Boolean = false,
    val fromSubscriptions: List<VideoItem> = emptyList(),
    val recommended: List<VideoItem> = emptyList(),
    val trending: List<VideoItem> = emptyList(),
    val shorts: List<VideoItem> = emptyList(),
    val contentCountryCode: String? = null,
    val error: String? = null,
    /** Подсказка про поиск прокси / переподключение. */
    val connectionStatus: String? = null
) {
    val hasContent: Boolean
        get() = fromSubscriptions.isNotEmpty() || recommended.isNotEmpty() || trending.isNotEmpty()
}

data class ShortsUiState(
    val isLoading: Boolean = false,
    val isResolving: Boolean = false,
    val items: List<VideoItem> = emptyList(),
    val currentIndex: Int = 0,
    val error: String? = null,
    val connectionStatus: String? = null
) {
    val hasContent: Boolean get() = items.isNotEmpty()
    val currentItem: VideoItem? get() = items.getOrNull(currentIndex)
}

data class SearchUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val results: List<VideoItem> = emptyList(),
    val recentQueries: List<String> = emptyList(),
    val error: String? = null,
    val message: String? = null
)

data class NowPlayingUiState(
    val item: VideoItem,
    val videoUrl: String,
    val isPlaying: Boolean,
    val selectedPlayback: PlaybackOption?,
    val betterConnectionAvailable: Boolean = false
)

data class DetailUiState(
    val isLoading: Boolean = false,
    val details: VideoDetails? = null,
    val selectedPlayback: PlaybackOption? = null,
    val selectedDownload: StreamOption? = null,
    val isSubscribed: Boolean = false,
    val showDownloadSheet: Boolean = false,
    val error: String? = null,
    val downloadStarted: Boolean = false,
    val resumePositionMs: Long? = null,
    val showResumeDialog: Boolean = false,
    val betterConnectionAvailable: Boolean = false
)

data class SettingsUiState(
    val proxyEnabled: Boolean = true,
    val proxyTimeoutSec: Int = PreferencesRepository.DEFAULT_PROXY_TIMEOUT_SEC,
    val proxySource: ProxySource = ProxySource.ALL,
    val customProxyAddress: String = "",
    val customProxyMode: CustomProxyMode = CustomProxyMode.OFF,
    val customProxyError: String? = null,
    val preferredAudioLanguage: String = PreferencesRepository.AUDIO_LANGUAGE_AUTO,
    val notifyNewVideos: Boolean = true,
    val audioOnlyOnBackground: Boolean = true,
    val proxyStatus: String = "Напрямую",
    val proxyStatsSummary: String = "",
    /** AUTO или ISO-2. */
    val countryPreference: String = Countries.AUTO,
    /** Фактически используемый код (после автоопределения). */
    val effectiveCountryCode: String = "US",
    val detectedCountryCode: String = "US",
    val isSwitchingProxy: Boolean = false
)

sealed class AuthState {
    data object Guest : AuthState()
    data class SignedIn(val displayName: String) : AuthState()
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = PreferencesRepository(application)
    private val repository = YoutubeRepository()
    private val downloadHistory = DownloadHistoryRepository(application)
    private val subscriptionsRepo = SubscriptionRepository(application)
    private val watchHistoryRepo = WatchHistoryRepository(application)
    private val feedMixer = HomeFeedMixer(repository)
    private val shortsFeedMixer = ShortsFeedMixer(repository)
    private val playerController = PlayerController.get(application)
    private val proxyPool = ProxyPool.get()
    private val proxyStats = ProxyStatsStore(application)

    private data class PlaybackSession(
        val item: VideoItem,
        val option: PlaybackOption,
        val videoUrl: String
    )

    private var playbackSession: PlaybackSession? = null
    private var playbackProxyRetries = 0
    @Volatile
    private var handlingPlaybackError = false
    @Volatile
    private var audioOnlyForBackground = false
    @Volatile
    private var preferredPlaybackHeight = PreferencesRepository.DEFAULT_PLAYBACK_HEIGHT
    private var preferredAudioLanguagePref = PreferencesRepository.AUDIO_LANGUAGE_AUTO
    private var shortsActive = false
    private var shortsPlayJob: Job? = null

    private val _nowPlaying = MutableStateFlow<NowPlayingUiState?>(null)
    val nowPlaying: StateFlow<NowPlayingUiState?> = _nowPlaying.asStateFlow()

    val consentAccepted: StateFlow<Boolean?> = preferences.consentAccepted
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val downloads: StateFlow<List<DownloadRecord>> = downloadHistory.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val subscriptions: StateFlow<List<SubscriptionEntity>> = subscriptionsRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val watchHistory: StateFlow<List<WatchHistoryEntity>> = watchHistoryRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val authState: StateFlow<AuthState> = MutableStateFlow(AuthState.Guest)

    private val _feed = MutableStateFlow(FeedUiState())
    val feed: StateFlow<FeedUiState> = _feed.asStateFlow()

    private val _shorts = MutableStateFlow(ShortsUiState())
    val shorts: StateFlow<ShortsUiState> = _shorts.asStateFlow()

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private val _detail = MutableStateFlow(DetailUiState())
    val detail: StateFlow<DetailUiState> = _detail.asStateFlow()

    private val _settings = MutableStateFlow(SettingsUiState())
    val settings: StateFlow<SettingsUiState> = _settings.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    /** Было ли уже успешное подключение в этой сессии (для тостов при переподключении). */
    private var hadSuccessfulConnection = false

    init {
        val detected = CountryDetector.detect(application)
        _settings.value = _settings.value.copy(
            detectedCountryCode = detected,
            effectiveCountryCode = detected
        )
        // Сразу предупреждаем — прогрев прокси уже идёт в TubekApp
        _feed.value = _feed.value.copy(
            isLoading = true,
            connectionStatus = "Ищем рабочее подключение. Это может занять некоторое время…"
        )
        proxyPool.attachStats(proxyStats)
        viewModelScope.launch {
            proxyStats.loadIntoMemory()
            refreshProxyStatsSummary()
        }
        viewModelScope.launch {
            proxyStats.statsFlow.collect {
                refreshProxyStatsSummary()
            }
        }
        playerController.setErrorListener { error ->
            viewModelScope.launch {
                onPlaybackError(error)
            }
        }
        playerController.setStallListener {
            viewModelScope.launch {
                onPlaybackStall()
            }
        }
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(2_000)
                playerController.checkStallAndNotify()
            }
        }
        viewModelScope.launch {
            var wasPlaying = false
            playerController.isPlaying.collect { playing ->
                if (wasPlaying && !playing) {
                    savePlaybackPosition()
                }
                wasPlaying = playing
                _nowPlaying.value = _nowPlaying.value?.copy(isPlaying = playing)
            }
        }
        viewModelScope.launch {
            preferences.preferredPlaybackHeight.collect { height ->
                preferredPlaybackHeight = height
            }
        }
        viewModelScope.launch {
            var languageReady = false
            preferences.preferredAudioLanguage.collect { code ->
                preferredAudioLanguagePref = code
                _settings.value = _settings.value.copy(preferredAudioLanguage = code)
                val language = preferences.resolveAudioLanguageCode(code)
                YoutubeService.applyLanguage(language)
                if (languageReady) {
                    loadFeed()
                    loadShorts()
                }
                languageReady = true
            }
        }
        viewModelScope.launch {
            proxyPool.betterProxyOffer.collect { offer ->
                val available = offer != null
                _detail.value = _detail.value.copy(betterConnectionAvailable = available)
                _nowPlaying.value = _nowPlaying.value?.copy(betterConnectionAvailable = available)
            }
        }
        viewModelScope.launch {
            proxyPool.silentSwitch.collect { switch ->
                YoutubeService.rebuildProxyClients(getApplication())
                updateProxyStatus()
                // Тихая смена на более быстрый прокси во время просмотра
                val session = playbackSession
                if (session != null && playerController.wantsToPlay()) {
                    silentReconnectPlayback(session)
                }
            }
        }
        viewModelScope.launch {
            preferences.proxyEnabled.collect { enabled ->
                proxyPool.setEnabled(enabled)
                YoutubeService.rebuildProxyClients(getApplication())
                _settings.value = _settings.value.copy(
                    proxyEnabled = enabled,
                    proxyStatus = proxyPool.current()?.key ?: if (enabled) "Ожидание" else "Выключен"
                )
            }
        }
        viewModelScope.launch {
            preferences.proxyTimeoutSec.collect { seconds ->
                proxyPool.setResponseTimeoutSec(seconds)
                YoutubeService.rebuildProxyClients(getApplication())
                _settings.value = _settings.value.copy(proxyTimeoutSec = seconds)
            }
        }
        viewModelScope.launch {
            preferences.proxySource.collect { source ->
                proxyPool.setSource(source)
                if (proxyPool.isEnabled()) {
                    proxyPool.resetSessionLimits()
                }
                YoutubeService.rebuildProxyClients(getApplication())
                _settings.value = _settings.value.copy(proxySource = source)
            }
        }
        viewModelScope.launch {
            preferences.customProxyAddress.collect { address ->
                _settings.value = _settings.value.copy(customProxyAddress = address)
                applyCustomProxySettings(address, _settings.value.customProxyMode)
            }
        }
        viewModelScope.launch {
            preferences.customProxyMode.collect { mode ->
                _settings.value = _settings.value.copy(customProxyMode = mode)
                applyCustomProxySettings(_settings.value.customProxyAddress, mode)
            }
        }
        viewModelScope.launch {
            preferences.notifyNewVideos.collect { enabled ->
                _settings.value = _settings.value.copy(notifyNewVideos = enabled)
            }
        }
        viewModelScope.launch {
            preferences.audioOnlyOnBackground.collect { enabled ->
                _settings.value = _settings.value.copy(audioOnlyOnBackground = enabled)
            }
        }
        viewModelScope.launch {
            preferences.contentCountryPreference.collect { preference ->
                val effective = preferences.resolveContentCountryCode(preference)
                val language = preferences.resolveAudioLanguageCode(preferredAudioLanguagePref)
                YoutubeService.applyPreferences(language, effective)
                _settings.value = _settings.value.copy(
                    countryPreference = preference,
                    effectiveCountryCode = effective,
                    detectedCountryCode = CountryDetector.detect(getApplication())
                )
                loadFeed()
            }
        }
        viewModelScope.launch {
            preferences.recentSearches.collect { recent ->
                _search.value = _search.value.copy(recentQueries = recent)
            }
        }
        bindMediaSession()
    }

    private fun bindMediaSession() {
        val context = getApplication<Application>()
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        mediaControllerFuture = MediaController.Builder(context, token).buildAsync().also { future ->
            future.addListener({
                runCatching { future.get() }
            }, MoreExecutors.directExecutor())
        }
    }

    private fun ensurePlaybackService() {
        val context = getApplication<Application>()
        runCatching {
            context.startService(Intent(context, PlaybackService::class.java))
        }
    }

    fun acceptConsent() {
        viewModelScope.launch {
            preferences.acceptConsent()
        }
    }

    fun loadFeed() {
        viewModelScope.launch {
            val needsProxy = proxyPool.isEnabled() && proxyPool.current() == null
            if (needsProxy || !hadSuccessfulConnection) {
                setConnectionStatus(
                    "Ищем рабочее подключение. Это может занять некоторое время…"
                )
            }
            _feed.value = _feed.value.copy(isLoading = true, error = null)
            // Не сбрасываем очередь до прогрева — иначе теряем уже найденных кандидатов
            if (proxyPool.isEnabled() && proxyPool.current() == null) {
                proxyPool.ensureReady()
                YoutubeService.rebuildProxyClients(getApplication())
                updateProxyStatus()
            }
            maybeUpgradeProxyQuietly()
            runWithProxyFallback(maxTries = FEED_PROXY_TRIES) {
                val subs = subscriptionsRepo.getAll()
                val history = watchHistoryRepo.getRecent(20)
                feedMixer.build(subs, history)
            }.onSuccess { home: HomeFeed ->
                hadSuccessfulConnection = true
                setConnectionStatus(null)
                _feed.value = FeedUiState(
                    isLoading = false,
                    fromSubscriptions = home.fromSubscriptions,
                    recommended = home.recommended,
                    trending = home.trending,
                    shorts = home.shorts,
                    contentCountryCode = _settings.value.effectiveCountryCode,
                    connectionStatus = null,
                    error = if (home.fromSubscriptions.isEmpty() &&
                        home.recommended.isEmpty() &&
                        home.trending.isEmpty()
                    ) {
                        "Лента пуста. Попробуйте поиск."
                    } else {
                        null
                    }
                )
            }.onFailure { error ->
                setConnectionStatus(null)
                _feed.value = _feed.value.copy(
                    isLoading = false,
                    error = humanError(error),
                    connectionStatus = null
                )
            }
        }
    }

    fun loadShorts() {
        viewModelScope.launch {
            val needsProxy = proxyPool.isEnabled() && proxyPool.current() == null
            if (needsProxy || !hadSuccessfulConnection) {
                setConnectionStatus(
                    "Ищем рабочее подключение. Это может занять некоторое время…"
                )
            }
            _shorts.value = _shorts.value.copy(isLoading = true, error = null)
            if (proxyPool.isEnabled() && proxyPool.current() == null) {
                proxyPool.ensureReady()
                YoutubeService.rebuildProxyClients(getApplication())
                updateProxyStatus()
            }
            maybeUpgradeProxyQuietly()
            runWithProxyFallback(maxTries = FEED_PROXY_TRIES) {
                val subs = subscriptionsRepo.getAll()
                shortsFeedMixer.build(subs)
            }.onSuccess { items ->
                hadSuccessfulConnection = true
                setConnectionStatus(null)
                val previousId = _shorts.value.currentItem?.id
                val index = items.indexOfFirst { it.id == previousId }.takeIf { it >= 0 } ?: 0
                _shorts.value = ShortsUiState(
                    isLoading = false,
                    items = items,
                    currentIndex = index,
                    connectionStatus = null,
                    error = if (items.isEmpty()) "Shorts не найдены. Потяните вниз или обновите." else null
                )
                if (shortsActive && items.isNotEmpty()) {
                    playShortAt(index)
                }
            }.onFailure { error ->
                setConnectionStatus(null)
                _shorts.value = _shorts.value.copy(
                    isLoading = false,
                    error = humanError(error),
                    connectionStatus = null
                )
            }
        }
    }

    fun enterShorts() {
        shortsActive = true
        playerController.setLooping(true)
        val state = _shorts.value
        if (!state.hasContent) {
            loadShorts()
        } else {
            playShortAt(state.currentIndex)
        }
    }

    fun leaveShorts() {
        shortsActive = false
        shortsPlayJob?.cancel()
        shortsPlayJob = null
        playerController.setLooping(false)
        _shorts.value = _shorts.value.copy(isResolving = false)
    }

    fun onShortsPageChanged(index: Int) {
        val items = _shorts.value.items
        if (index !in items.indices) return
        if (index == _shorts.value.currentIndex &&
            playbackSession?.item?.id == items[index].id &&
            playerController.wantsToPlay()
        ) {
            return
        }
        _shorts.value = _shorts.value.copy(currentIndex = index)
        if (shortsActive) {
            playShortAt(index)
        }
    }

    private fun playShortAt(index: Int) {
        val item = _shorts.value.items.getOrNull(index) ?: return
        shortsPlayJob?.cancel()
        shortsPlayJob = viewModelScope.launch {
            maybeUpgradeProxyQuietly()
            savePlaybackPosition()
            _shorts.value = _shorts.value.copy(isResolving = true, error = null)
            runWithProxyFallback { repository.resolve(item.url, preferredAudioLanguage()) }
                .onSuccess { details ->
                    if (!shortsActive) return@onSuccess
                    if (_shorts.value.currentItem?.id != item.id) return@onSuccess
                    val playback = pickPlaybackOption(details.playbackOptions)
                        ?: details.playbackOptions.firstOrNull { !it.isAudioOnly }
                        ?: details.playbackOptions.firstOrNull()
                    if (playback == null) {
                        _shorts.value = _shorts.value.copy(
                            isResolving = false,
                            error = "Нет доступных потоков"
                        )
                        return@onSuccess
                    }
                    playerController.setLooping(true)
                    startPlayback(details, playback, startPositionMs = 0L)
                    _shorts.value = _shorts.value.copy(isResolving = false, error = null)
                }
                .onFailure { error ->
                    if (_shorts.value.currentItem?.id != item.id) return@onFailure
                    _shorts.value = _shorts.value.copy(
                        isResolving = false,
                        error = humanError(error)
                    )
                }
        }
    }

    fun onQueryChange(value: String) {
        _search.value = _search.value.copy(query = value, error = null, message = null)
    }

    fun clearSearchMessage() {
        _search.value = _search.value.copy(message = null, error = null)
    }

    fun submitSearch() {
        val query = _search.value.query.trim()
        if (query.isEmpty()) {
            _search.value = _search.value.copy(error = "Вставьте ссылку или введите запрос")
            return
        }
        viewModelScope.launch { preferences.addRecentSearch(query) }
        if (YoutubeRepository.looksLikeYoutubeUrl(query)) {
            _search.value = _search.value.copy(message = "open:$query", error = null)
        } else {
            search(query)
        }
    }

    fun search(query: String) {
        viewModelScope.launch {
            maybeUpgradeProxyQuietly()
            val trimmed = query.trim()
            preferences.addRecentSearch(trimmed)
            _search.value = _search.value.copy(
                isLoading = true,
                error = null,
                message = null,
                query = trimmed
            )
            runWithProxyFallback { repository.search(trimmed) }
                .onSuccess { items ->
                    _search.value = _search.value.copy(
                        isLoading = false,
                        results = items,
                        error = if (items.isEmpty()) "Ничего не найдено" else null
                    )
                }
                .onFailure { error ->
                    _search.value = _search.value.copy(
                        isLoading = false,
                        error = humanError(error)
                    )
                }
        }
    }

    fun runRecentSearch(query: String) {
        onQueryChange(query)
        if (YoutubeRepository.looksLikeYoutubeUrl(query)) {
            viewModelScope.launch { preferences.addRecentSearch(query) }
            _search.value = _search.value.copy(message = "open:$query", error = null)
        } else {
            search(query)
        }
    }

    fun removeRecentSearch(query: String) {
        viewModelScope.launch { preferences.removeRecentSearch(query) }
    }

    fun clearRecentSearches() {
        viewModelScope.launch { preferences.clearRecentSearches() }
    }

    fun loadDetails(urlOrId: String) {
        viewModelScope.launch {
            maybeUpgradeProxyQuietly()
            savePlaybackPosition()
            playerController.setLooping(false)
            _detail.value = DetailUiState(isLoading = true)
            runWithProxyFallback { repository.resolve(urlOrId, preferredAudioLanguage()) }
                .onSuccess { details ->
                    val playback = pickPlaybackOption(details.playbackOptions)
                    val subscribed = details.channelId?.let {
                        subscriptionsRepo.getById(it) != null
                    } == true
                    val saved = watchHistoryRepo.getById(details.item.id)
                    val resumeAt = saved?.positionMs?.takeIf { pos ->
                        pos >= MIN_RESUME_POSITION_MS &&
                            (details.item.durationSeconds == null ||
                                pos < (details.item.durationSeconds * 1000L) - END_RESUME_MARGIN_MS)
                    }
                    val better = proxyPool.betterProxyOffer.value != null
                    _detail.value = DetailUiState(
                        isLoading = false,
                        details = details,
                        selectedPlayback = playback,
                        selectedDownload = details.streams.firstOrNull { !it.isAudioOnly }
                            ?: details.streams.firstOrNull(),
                        isSubscribed = subscribed,
                        resumePositionMs = resumeAt,
                        showResumeDialog = resumeAt != null,
                        betterConnectionAvailable = better
                    )
                    if (playback != null && resumeAt == null) {
                        startPlayback(details, playback, startPositionMs = 0L)
                    } else if (resumeAt != null) {
                        // Не автозапуск — ждём диалог; останавливаем предыдущее видео
                        playerController.pause()
                    }
                }
                .onFailure { error ->
                    _detail.value = DetailUiState(
                        isLoading = false,
                        error = humanError(error)
                    )
                }
        }
    }

    fun continueWatching() {
        val details = _detail.value.details ?: return
        val option = _detail.value.selectedPlayback
            ?: pickPlaybackOption(details.playbackOptions)
            ?: return
        val position = _detail.value.resumePositionMs ?: 0L
        _detail.value = _detail.value.copy(showResumeDialog = false)
        startPlayback(details, option, startPositionMs = position)
    }

    fun startFromBeginning() {
        val details = _detail.value.details ?: return
        val option = _detail.value.selectedPlayback
            ?: pickPlaybackOption(details.playbackOptions)
            ?: return
        _detail.value = _detail.value.copy(showResumeDialog = false, resumePositionMs = null)
        viewModelScope.launch {
            watchHistoryRepo.updatePosition(details.item.id, 0L)
        }
        startPlayback(details, option, startPositionMs = 0L)
    }

    private fun startPlayback(
        details: VideoDetails,
        option: PlaybackOption,
        startPositionMs: Long = 0L
    ) {
        ensurePlaybackService()
        YoutubeService.rebuildProxyClients(getApplication())
        playbackSession = PlaybackSession(
            item = details.item,
            option = option,
            videoUrl = details.item.url
        )
        playbackProxyRetries = 0
        audioOnlyForBackground = false
        playerController.play(details.item, option, startPositionMs)
        _nowPlaying.value = NowPlayingUiState(
            item = details.item,
            videoUrl = details.item.url,
            isPlaying = true,
            selectedPlayback = option,
            betterConnectionAvailable = proxyPool.betterProxyOffer.value != null
        )
        viewModelScope.launch {
            watchHistoryRepo.recordWatch(details.item, details.channelUrl)
        }
        updateProxyStatus()
    }

    private suspend fun onPlaybackStall() {
        if (!playerController.wantsToPlay()) return
        onPlaybackError(
            PlaybackException(
                "playback_stall",
                null,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            )
        )
    }

    private suspend fun onPlaybackError(error: PlaybackException) {
        if (handlingPlaybackError) return
        val session = playbackSession ?: return
        if (!proxyPool.isEnabled()) {
            notifyToast("Ошибка воспроизведения: ${error.errorCodeName}")
            return
        }
        if (playbackProxyRetries >= MAX_PROXY_TRIES) {
            notifyToast("Видео не загружается. Прокси не помогли")
            return
        }

        handlingPlaybackError = true
        try {
            playbackProxyRetries++
            val position = playerController.currentPositionMs()
            setConnectionStatus("Видео не грузится. Ищем рабочий прокси…")
            if (hadSuccessfulConnection) {
                notifyToast("Видео не грузится. Ищем рабочий прокси…")
            }

            // Сначала смена прокси (текущий уже не отдал поток), затем свежие URL
            proxyPool.switchToNext()
            YoutubeService.rebuildProxyClients(getApplication())
            updateProxyStatus()

            val result = withProxyFallback(
                context = getApplication(),
                maxTries = MAX_PROXY_TRIES,
                onStatus = { msg ->
                    setConnectionStatus(msg)
                    if (hadSuccessfulConnection) notifyToast(msg)
                }
            ) {
                repository.resolve(session.videoUrl, preferredAudioLanguage())
            }
            updateProxyStatus()
            refreshProxyStatsSummary()

            result.onSuccess { details ->
                val option = pickPlaybackOption(details.playbackOptions)
                    ?: details.playbackOptions.firstOrNull()
                    ?: session.option
                playbackSession = PlaybackSession(details.item, option, details.item.url)
                _detail.value = _detail.value.copy(
                    details = details,
                    selectedPlayback = option,
                    error = null
                )
                playerController.play(details.item, option, position)
                _nowPlaying.value = NowPlayingUiState(
                    item = details.item,
                    videoUrl = details.item.url,
                    isPlaying = true,
                    selectedPlayback = option,
                    betterConnectionAvailable = proxyPool.betterProxyOffer.value != null
                )
                setConnectionStatus(null)
            }.onFailure {
                notifyToast("Видео не загружается. Прокси не помогли")
                setConnectionStatus(null)
            }
        } finally {
            handlingPlaybackError = false
        }
    }

    fun selectPlayback(option: PlaybackOption) {
        val details = _detail.value.details ?: return
        val position = playerController.currentPositionMs()
        _detail.value = _detail.value.copy(selectedPlayback = option, showResumeDialog = false)
        if (!option.isAudioOnly && option.height > 0) {
            viewModelScope.launch {
                preferences.setPreferredPlaybackHeight(option.height)
            }
        }
        startPlayback(details, option, startPositionMs = position)
    }

    fun selectAudioLanguage(languageCode: String) {
        val current = _detail.value.details ?: return
        if (current.selectedAudioLanguage.equals(languageCode, ignoreCase = true)) return
        val updated = repository.withAudioLanguage(current, languageCode)
        val position = playerController.currentPositionMs()
        val option = _detail.value.selectedPlayback?.let { selected ->
            updated.playbackOptions.firstOrNull {
                it.height == selected.height && it.isAudioOnly == selected.isAudioOnly
            }
        } ?: pickPlaybackOption(updated.playbackOptions) ?: return
        _detail.value = _detail.value.copy(
            details = updated,
            selectedPlayback = option,
            showResumeDialog = false
        )
        viewModelScope.launch {
            preferences.setPreferredAudioLanguage(languageCode)
            YoutubeService.applyLanguage(languageCode)
        }
        startPlayback(updated, option, startPositionMs = position)
    }

    fun setPreferredAudioLanguage(code: String) {
        viewModelScope.launch {
            preferences.setPreferredAudioLanguage(code)
        }
    }

    fun selectDownloadStream(option: StreamOption) {
        _detail.value = _detail.value.copy(selectedDownload = option, downloadStarted = false)
    }

    fun setShowDownloadSheet(show: Boolean) {
        _detail.value = _detail.value.copy(showDownloadSheet = show)
    }

    fun toggleSubscribe() {
        val details = _detail.value.details ?: return
        val channelId = details.channelId ?: return
        val channelUrl = details.channelUrl ?: return
        viewModelScope.launch {
            if (_detail.value.isSubscribed) {
                subscriptionsRepo.unsubscribe(channelId)
                _detail.value = _detail.value.copy(isSubscribed = false)
            } else {
                subscriptionsRepo.subscribe(
                    channelId = channelId,
                    name = details.item.uploader,
                    channelUrl = channelUrl,
                    avatarUrl = details.channelAvatarUrl
                )
                _detail.value = _detail.value.copy(isSubscribed = true)
            }
        }
    }

    fun openSubscriptionFeed(channelUrl: String, onOpenVideo: (String) -> Unit) {
        viewModelScope.launch {
            runWithProxyFallback { repository.channelVideos(channelUrl, limit = 1) }
                .onSuccess { videos ->
                    videos.firstOrNull()?.url?.let(onOpenVideo)
                }
        }
    }

    fun setChannelNotify(channelId: String, enabled: Boolean) {
        viewModelScope.launch {
            subscriptionsRepo.setNotifyEnabled(channelId, enabled)
        }
    }

    fun unsubscribe(channelId: String) {
        viewModelScope.launch {
            subscriptionsRepo.unsubscribe(channelId)
        }
    }

    fun startDownload() {
        val details = _detail.value.details ?: return
        val selected = _detail.value.selectedDownload ?: return
        val extension = when {
            selected.isAudioOnly && selected.mimeType.contains("webm") -> "webm"
            selected.isAudioOnly -> "m4a"
            selected.mimeType.contains("webm") -> "webm"
            else -> "mp4"
        }
        DownloadWorker.enqueue(
            context = getApplication(),
            url = selected.url,
            title = details.item.title,
            mimeType = selected.mimeType,
            extension = extension,
            videoId = details.item.id,
            uploader = details.item.uploader,
            thumbnailUrl = details.item.thumbnailUrl,
            qualityLabel = selected.label,
            isAudioOnly = selected.isAudioOnly,
            videoPageUrl = details.item.url
        )
        _detail.value = _detail.value.copy(downloadStarted = true, showDownloadSheet = false)
    }

    fun clearDownloadFlag() {
        _detail.value = _detail.value.copy(downloadStarted = false)
    }

    fun deleteDownload(record: DownloadRecord) {
        viewModelScope.launch {
            downloadHistory.delete(record.id)
        }
    }

    fun clearDownloadHistory() {
        viewModelScope.launch {
            downloadHistory.clear()
        }
    }

    fun clearWatchHistory() {
        viewModelScope.launch {
            watchHistoryRepo.clear()
        }
    }

    fun deleteWatchHistoryItem(videoId: String) {
        viewModelScope.launch {
            watchHistoryRepo.delete(videoId)
        }
    }

    fun setProxyEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setProxyEnabled(enabled)
            proxyPool.setEnabled(enabled)
            if (!enabled) {
                YoutubeService.rebuildProxyClients(getApplication())
            } else {
                proxyPool.resetSessionLimits()
                proxyPool.ensureReady()
                YoutubeService.rebuildProxyClients(getApplication())
            }
            updateProxyStatus()
        }
    }

    fun setProxyTimeoutSec(seconds: Int) {
        viewModelScope.launch {
            preferences.setProxyTimeoutSec(seconds)
        }
    }

    fun setProxySource(source: ProxySource) {
        viewModelScope.launch {
            preferences.setProxySource(source)
        }
    }

    fun setCustomProxyAddress(address: String) {
        viewModelScope.launch {
            preferences.setCustomProxyAddress(address)
        }
    }

    fun setCustomProxyMode(mode: CustomProxyMode) {
        viewModelScope.launch {
            preferences.setCustomProxyMode(mode)
            if (mode != CustomProxyMode.OFF &&
                CustomProxyParser.parse(_settings.value.customProxyAddress) == null
            ) {
                _settings.value = _settings.value.copy(
                    customProxyError = "Укажите адрес вида host:port или socks5://host:port"
                )
            }
        }
    }

    fun applyCustomProxyFromSettings(address: String, mode: CustomProxyMode) {
        viewModelScope.launch {
            preferences.setCustomProxyAddress(address)
            preferences.setCustomProxyMode(mode)
            val endpoint = CustomProxyParser.parse(address)
            if (mode != CustomProxyMode.OFF && endpoint == null) {
                _settings.value = _settings.value.copy(
                    customProxyError = "Неверный адрес. Пример: 1.2.3.4:8080 или socks5://1.2.3.4:1080"
                )
                notifyToast("Неверный адрес прокси")
                return@launch
            }
            _settings.value = _settings.value.copy(customProxyError = null)
            proxyPool.setCustomProxy(endpoint, mode)
            if (proxyPool.isEnabled()) {
                proxyPool.resetSessionLimits()
                proxyPool.ensureReady()
                YoutubeService.rebuildProxyClients(getApplication())
            }
            updateProxyStatus()
            when (mode) {
                CustomProxyMode.OFF -> notifyToast("Свой прокси отключён")
                CustomProxyMode.ONLY -> {
                    if (proxyPool.current() != null) {
                        notifyToast("Используется только ваш прокси")
                    } else {
                        notifyToast("Ваш прокси не отвечает")
                    }
                }
                CustomProxyMode.WITH_AUTO -> notifyToast("Свой прокси + автопереключение")
            }
        }
    }

    private fun applyCustomProxySettings(address: String, mode: CustomProxyMode) {
        val endpoint = CustomProxyParser.parse(address)
        val error = if (mode != CustomProxyMode.OFF && address.isNotBlank() && endpoint == null) {
            "Неверный адрес. Пример: 1.2.3.4:8080"
        } else {
            null
        }
        _settings.value = _settings.value.copy(customProxyError = error)
        val effectiveMode = if (endpoint == null) CustomProxyMode.OFF else mode
        proxyPool.setCustomProxy(endpoint, effectiveMode)
        YoutubeService.rebuildProxyClients(getApplication())
        updateProxyStatus()
    }

    fun forceSwitchProxy() {
        viewModelScope.launch {
            if (!proxyPool.isEnabled()) {
                notifyToast("Сначала включите прокси")
                return@launch
            }
            if (proxyPool.customMode() == CustomProxyMode.ONLY) {
                notifyToast("Режим «только свой прокси» — автосмена отключена")
                _settings.value = _settings.value.copy(isSwitchingProxy = true)
                val next = proxyPool.ensureReady()
                YoutubeService.rebuildProxyClients(getApplication())
                updateProxyStatus()
                _settings.value = _settings.value.copy(isSwitchingProxy = false)
                if (next != null) {
                    notifyToast("Ваш прокси работает: ${next.key}")
                } else {
                    notifyToast("Ваш прокси не отвечает")
                }
                return@launch
            }
            val position = playerController.currentPositionMs()
            val session = playbackSession
            _settings.value = _settings.value.copy(isSwitchingProxy = true)
            notifyToast("Принудительная смена прокси…")
            val previous = proxyPool.current()?.key
            proxyPool.resetSessionLimits()
            var next = proxyPool.switchToNext()
            if (next == null) {
                notifyToast("Список исчерпан. Загружаем новый…")
                next = proxyPool.forceRefreshListAndRotate()
            }
            YoutubeService.rebuildProxyClients(getApplication())
            updateProxyStatus()
            refreshProxyStatsSummary()
            _settings.value = _settings.value.copy(isSwitchingProxy = false)
            if (next != null) {
                val country = next.countryCode?.let { "${Countries.nameFor(it)} ($it)" } ?: "страна ?"
                val tag = if (next.source == "custom") "свой" else country
                notifyToast("Новый прокси: ${next.key} · $tag")
                if (session != null) {
                    val details = runCatching { repository.resolve(session.videoUrl, preferredAudioLanguage()) }.getOrNull()
                    if (details != null) {
                        val option = pickPlaybackOption(details.playbackOptions) ?: session.option
                        playbackSession = PlaybackSession(details.item, option, details.item.url)
                        playerController.play(details.item, option, position)
                    } else {
                        playerController.play(session.item, session.option, position)
                    }
                }
            } else {
                notifyToast("Не удалось найти рабочий прокси" + (previous?.let { " (был $it)" } ?: ""))
            }
        }
    }

    fun setContentCountry(preference: String) {
        viewModelScope.launch {
            preferences.setContentCountryPreference(preference)
        }
    }

    fun setNotifyNewVideos(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setNotifyNewVideos(enabled)
        }
    }

    fun setAudioOnlyOnBackground(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setAudioOnlyOnBackground(enabled)
        }
    }

    fun togglePlayPause() {
        playerController.playPause()
    }

    fun closeNowPlaying() {
        savePlaybackPosition()
        playerController.stopAndClear()
        playbackSession = null
        audioOnlyForBackground = false
        _nowPlaying.value = null
    }

    fun savePlaybackPosition() {
        val session = playbackSession ?: return
        val position = playerController.currentPositionMs()
        if (position <= 0L) return
        viewModelScope.launch {
            watchHistoryRepo.updatePosition(session.item.id, position)
        }
    }

    fun onLeavePlayerScreen() {
        savePlaybackPosition()
        maybeUpgradeProxyQuietlyAsync()
    }

    fun applyBetterConnection() {
        viewModelScope.launch {
            val position = playerController.currentPositionMs()
            val session = playbackSession
            val next = proxyPool.applyPendingBetter() ?: return@launch
            YoutubeService.rebuildProxyClients(getApplication())
            updateProxyStatus()
            notifyToast("Переключились на более быстрый прокси → ${next.key}")
            _detail.value = _detail.value.copy(betterConnectionAvailable = false)
            _nowPlaying.value = _nowPlaying.value?.copy(betterConnectionAvailable = false)

            if (session == null) return@launch
            val details = runCatching { repository.resolve(session.videoUrl, preferredAudioLanguage()) }.getOrNull()
            if (details != null) {
                val option = pickPlaybackOption(details.playbackOptions)
                    ?: session.option
                playbackSession = PlaybackSession(details.item, option, details.item.url)
                _detail.value = _detail.value.copy(
                    details = details,
                    selectedPlayback = option
                )
                playerController.play(details.item, option, position)
            } else {
                playerController.play(session.item, session.option, position)
            }
        }
    }

    private fun maybeUpgradeProxyQuietlyAsync() {
        viewModelScope.launch { maybeUpgradeProxyQuietly() }
    }

    private suspend fun maybeUpgradeProxyQuietly() {
        // Тихая смена выполняется через proxyPool.silentSwitch (фоновая проверка)
    }

    private suspend fun silentReconnectPlayback(session: PlaybackSession) {
        if (handlingPlaybackError) return
        handlingPlaybackError = true
        try {
            val position = playerController.currentPositionMs()
            val details = runCatching {
                repository.resolve(session.videoUrl, preferredAudioLanguage())
            }.getOrNull() ?: return
            val option = pickPlaybackOption(details.playbackOptions)
                ?: details.playbackOptions.firstOrNull()
                ?: session.option
            playbackSession = PlaybackSession(details.item, option, details.item.url)
            _detail.value = _detail.value.copy(
                details = details,
                selectedPlayback = option
            )
            playerController.play(details.item, option, position)
            _nowPlaying.value = _nowPlaying.value?.copy(
                item = details.item,
                videoUrl = details.item.url,
                selectedPlayback = option,
                isPlaying = true
            )
        } finally {
            handlingPlaybackError = false
        }
    }

    private fun pickPlaybackOption(options: List<PlaybackOption>): PlaybackOption? {
        val videos = options.filter { !it.isAudioOnly }
        if (videos.isEmpty()) return options.firstOrNull()
        val preferred = preferredPlaybackHeight
        videos.firstOrNull { it.height == preferred }?.let { return it }
        videos.filter { it.height >= preferred }.minByOrNull { it.height }?.let { return it }
        return videos.maxByOrNull { it.height }
    }

    private fun preferredAudioLanguage(): String =
        preferences.resolveAudioLanguageCode(preferredAudioLanguagePref)

    fun onAppBackgrounded() {
        viewModelScope.launch {
            savePlaybackPosition()
            // Важно: playWhenReady, а не isPlaying — иначе после паузы switchToAudioOnly
            // снова вызывает play() и звук «оживает» в фоне.
            if (!playerController.wantsToPlay()) {
                playerController.pause()
                return@launch
            }
            if (!preferences.audioOnlyOnBackground.first()) return@launch
            val session = playbackSession ?: return@launch
            if (session.option.isAudioOnly) return@launch
            val details = _detail.value.details
            val audio = details?.playbackOptions?.firstOrNull { it.isAudioOnly }
                ?: return@launch
            val audioUrl = audio.audioUrl ?: return@launch
            audioOnlyForBackground = true
            playerController.switchToAudioOnly(audioUrl, session.item)
        }
    }

    fun onAppForegrounded() {
        viewModelScope.launch {
            if (!audioOnlyForBackground) return@launch
            val session = playbackSession ?: return@launch
            if (session.option.isAudioOnly) return@launch
            audioOnlyForBackground = false
            val position = playerController.currentPositionMs()
            YoutubeService.rebuildProxyClients(getApplication())
            playerController.play(session.item, session.option, position)
            _nowPlaying.value = NowPlayingUiState(
                item = session.item,
                videoUrl = session.videoUrl,
                isPlaying = true,
                selectedPlayback = session.option,
                betterConnectionAvailable = proxyPool.betterProxyOffer.value != null
            )
        }
    }

    private suspend fun <T> runWithProxyFallback(
        maxTries: Int = MAX_PROXY_TRIES,
        block: suspend () -> T
    ): Result<T> {
        val toastOnStatus = hadSuccessfulConnection
        val result = withProxyFallback(
            context = getApplication(),
            maxTries = maxTries,
            onStatus = { message ->
                setConnectionStatus(message)
                // При первом поиске — только баннер; тосты — при переподключении после сбоя
                if (toastOnStatus) {
                    notifyToast(message)
                }
            },
            block = block
        )
        if (result.isSuccess) {
            hadSuccessfulConnection = true
            setConnectionStatus(null)
        }
        updateProxyStatus()
        refreshProxyStatsSummary()
        return result
    }

    private fun setConnectionStatus(message: String?) {
        _feed.value = _feed.value.copy(connectionStatus = message)
        _shorts.value = _shorts.value.copy(connectionStatus = message)
    }

    private fun refreshProxyStatsSummary() {
        val known = proxyStats.knownGoodEndpoints(limit = 100).size
        val top = proxyStats.snapshot().take(3)
        val summary = buildString {
            if (known > 0) append("Рабочих IP: $known")
            if (top.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(
                    top.joinToString(" · ") { stat ->
                        "${Countries.nameFor(stat.countryCode)} ${stat.avgLatencyMs} мс ×${stat.successCount}"
                    }
                )
            }
            if (isEmpty()) append("Пока нет статистики")
        }
        _settings.value = _settings.value.copy(proxyStatsSummary = summary)
    }

    private suspend fun notifyToast(message: String) {
        _toasts.emit(message)
    }

    companion object {
        private const val MAX_PROXY_TRIES = 6
        private const val FEED_PROXY_TRIES = 12
        private const val MIN_RESUME_POSITION_MS = 15_000L
        private const val END_RESUME_MARGIN_MS = 30_000L
    }

    private fun updateProxyStatus() {
        val current = proxyPool.current()
        val mode = proxyPool.customMode()
        _settings.value = _settings.value.copy(
            proxyStatus = when {
                !proxyPool.isEnabled() -> "Выключен"
                current != null && current.source == "custom" ->
                    "свой · ${current.key}"
                current != null && mode == CustomProxyMode.WITH_AUTO ->
                    "${current.key} (+авто)"
                current != null -> current.key
                mode == CustomProxyMode.ONLY -> "Свой прокси недоступен"
                else -> "Напрямую"
            }
        )
    }

    private fun humanError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("Unable to resolve host", ignoreCase = true) ->
                "Нет соединения с интернетом"
            message.contains("429") || message.contains("ReCaptcha", ignoreCase = true) ->
                "YouTube временно ограничивает запросы. Попробуйте позже"
            message.contains("VideoUnavailable", ignoreCase = true) ->
                "Видео недоступно"
            message.isBlank() -> "Не удалось получить данные. Попробуйте ещё раз"
            else -> message
        }
    }

    override fun onCleared() {
        playerController.setErrorListener(null)
        playerController.setStallListener(null)
        mediaControllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}

class AppViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
            return AppViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
