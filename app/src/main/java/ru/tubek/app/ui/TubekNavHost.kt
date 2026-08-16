package ru.tubek.app.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import ru.tubek.app.data.DownloadRecord
import ru.tubek.app.data.SubscriptionEntity
import ru.tubek.app.data.WatchHistoryEntity
import ru.tubek.app.ui.components.MiniPlayerBar
import ru.tubek.app.ui.screens.ChannelScreen
import ru.tubek.app.ui.screens.ConsentScreen
import ru.tubek.app.ui.screens.DownloadsHelpScreen
import ru.tubek.app.ui.screens.FeedScreen
import ru.tubek.app.ui.screens.HistoryScreen
import ru.tubek.app.ui.screens.LegalScreen
import ru.tubek.app.ui.screens.LibraryScreen
import ru.tubek.app.ui.screens.SearchScreen
import ru.tubek.app.ui.screens.SettingsScreen
import ru.tubek.app.ui.screens.ShortsScreen
import ru.tubek.app.ui.screens.SubscriptionsScreen
import ru.tubek.app.ui.screens.VideoDetailScreen
import ru.tubek.app.ui.viewmodel.AppViewModel
import ru.tubek.app.ui.viewmodel.AuthState
import ru.tubek.app.ui.viewmodel.FeedUiState
import ru.tubek.app.ui.viewmodel.NowPlayingUiState
import ru.tubek.app.ui.viewmodel.SearchUiState
import ru.tubek.app.ui.viewmodel.SettingsUiState
import ru.tubek.app.ui.viewmodel.ShortsUiState
import ru.tubek.app.youtube.SignInPrepareResult
import ru.tubek.app.youtube.YoutubeAuthManager
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object Routes {
    const val Consent = "consent"
    const val Main = "main"
    const val Feed = "feed"
    const val Shorts = "shorts"
    const val Subscriptions = "subscriptions"
    const val Search = "search"
    const val Library = "library"
    const val History = "history"
    const val Settings = "settings"
    const val Detail = "detail/{url}"
    const val Channel = "channel/{channel}"
    const val Legal = "legal/{type}"
    const val DownloadsHelp = "downloads_help"

    fun detail(url: String): String {
        val encoded = URLEncoder.encode(url, StandardCharsets.UTF_8.toString())
        return "detail/$encoded"
    }

    fun channel(channelUrlOrId: String): String {
        val encoded = URLEncoder.encode(channelUrlOrId, StandardCharsets.UTF_8.toString())
        return "channel/$encoded"
    }

    fun legal(type: String): String = "legal/$type"
}

private data class TabItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

@Composable
fun TubekNavHost(
    consentAccepted: Boolean?,
    initialUrl: String?,
    onAcceptConsent: () -> Unit,
    viewModel: AppViewModel
) {
    if (consentAccepted == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val rootNav = rememberNavController()
    val start = if (consentAccepted) Routes.Main else Routes.Consent
    val searchState by viewModel.search.collectAsStateWithLifecycle()
    val detailState by viewModel.detail.collectAsStateWithLifecycle()
    val channelState by viewModel.channel.collectAsStateWithLifecycle()
    val feedState by viewModel.feed.collectAsStateWithLifecycle()
    val shortsState by viewModel.shorts.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val watchHistory by viewModel.watchHistory.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val headerStatus by viewModel.headerStatus.collectAsStateWithLifecycle()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val activity = context as Activity

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.completeGoogleSignIn(result.data)
        } else {
            Toast.makeText(context, "Вход отменён", Toast.LENGTH_SHORT).show()
        }
    }

    fun startGoogleSignIn() {
        scope.launch {
            runCatching { viewModel.prepareGoogleSignIn(activity) }
                .onSuccess { prep ->
                    when (prep) {
                        is SignInPrepareResult.SignedIn ->
                            viewModel.onGoogleSignInCompleted(prep.session.displayName)
                        is SignInPrepareResult.NeedsUserConsent -> {
                            signInLauncher.launch(
                                IntentSenderRequest.Builder(prep.pendingIntent.intentSender).build()
                            )
                        }
                    }
                }
                .onFailure { error ->
                    Toast.makeText(
                        context,
                        YoutubeAuthManager.friendlyError(error),
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                Lifecycle.Event.ON_START -> viewModel.onAppForegrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(consentAccepted, initialUrl) {
        if (consentAccepted && !initialUrl.isNullOrBlank()) {
            rootNav.navigate(Routes.detail(initialUrl)) {
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(searchState.message) {
        val message = searchState.message
        if (message?.startsWith("open:") == true) {
            val url = message.removePrefix("open:")
            viewModel.clearSearchMessage()
            rootNav.navigate(Routes.detail(url))
        }
    }

    NavHost(navController = rootNav, startDestination = start) {
        composable(Routes.Consent) {
            ConsentScreen(
                onAccept = {
                    onAcceptConsent()
                    rootNav.navigate(Routes.Main) {
                        popUpTo(Routes.Consent) { inclusive = true }
                    }
                },
                onOpenPrivacy = { rootNav.navigate(Routes.legal("privacy")) },
                onOpenDisclaimer = { rootNav.navigate(Routes.legal("disclaimer")) }
            )
        }

        composable(Routes.Main) {
            MainShell(
                feedState = feedState,
                shortsState = shortsState,
                searchState = searchState,
                downloads = downloads,
                watchHistory = watchHistory,
                subscriptions = subscriptions,
                authState = authState,
                nowPlaying = nowPlaying,
                settings = settings,
                headerStatus = headerStatus,
                onRefreshFeed = viewModel::loadFeed,
                onRefreshShorts = viewModel::loadShorts,
                onShortsPageChanged = viewModel::onShortsPageChanged,
                onEnterShorts = viewModel::enterShorts,
                onLeaveShorts = viewModel::leaveShorts,
                onQueryChange = viewModel::onQueryChange,
                onSubmitSearch = viewModel::submitSearch,
                onRecentSearch = viewModel::runRecentSearch,
                onRemoveRecentSearch = viewModel::removeRecentSearch,
                onClearRecentSearches = viewModel::clearRecentSearches,
                onOpenVideo = { url -> rootNav.navigate(Routes.detail(url)) },
                onExpandNowPlaying = {
                    nowPlaying?.videoUrl?.let { rootNav.navigate(Routes.detail(it)) }
                },
                onPlayPauseNowPlaying = viewModel::togglePlayPause,
                onCloseNowPlaying = viewModel::closeNowPlaying,
                onApplyBetterConnection = viewModel::applyBetterConnection,
                onScreenSwitch = viewModel::onLeavePlayerScreen,
                onDeleteDownload = viewModel::deleteDownload,
                onClearDownloads = viewModel::clearDownloadHistory,
                onOpenLegal = { rootNav.navigate(Routes.legal("privacy")) },
                onOpenHelp = { rootNav.navigate(Routes.DownloadsHelp) },
                onOpenHistory = { rootNav.navigate(Routes.History) },
                onOpenSettings = { rootNav.navigate(Routes.Settings) },
                onToggleNotify = { sub, enabled -> viewModel.setChannelNotify(sub.channelId, enabled) },
                onUnsubscribe = { viewModel.unsubscribe(it.channelId) },
                onOpenChannel = { sub ->
                    rootNav.navigate(Routes.channel(sub.channelUrl.ifBlank { sub.channelId }))
                },
                onForceSwitchProxy = viewModel::forceSwitchProxy,
                onLoginClick = {
                    if (authState is AuthState.SignedIn) {
                        viewModel.signOut()
                    } else {
                        startGoogleSignIn()
                    }
                }
            )
        }

        composable(
            route = Routes.Detail,
            arguments = listOf(navArgument("url") { type = NavType.StringType })
        ) { entry ->
            val encoded = entry.arguments?.getString("url").orEmpty()
            val url = URLDecoder.decode(encoded, StandardCharsets.UTF_8.toString())
            LaunchedEffect(url) {
                viewModel.loadDetails(url)
            }
            VideoDetailScreen(
                state = detailState,
                onBack = {
                    viewModel.onLeavePlayerScreen()
                    rootNav.popBackStack()
                },
                onSelectPlayback = viewModel::selectPlayback,
                onSelectAudioLanguage = viewModel::selectAudioLanguage,
                onSelectDownload = viewModel::selectDownloadStream,
                onToggleSubscribe = viewModel::toggleSubscribe,
                onShowDownloadSheet = viewModel::setShowDownloadSheet,
                onDownload = viewModel::startDownload,
                onDownloadMessageShown = viewModel::clearDownloadFlag,
                onOpenRelated = { item ->
                    rootNav.navigate(Routes.detail(item.url))
                },
                onContinueWatching = viewModel::continueWatching,
                onStartFromBeginning = viewModel::startFromBeginning,
                onApplyBetterConnection = viewModel::applyBetterConnection,
                onOpenChannel = { channelUrlOrId ->
                    rootNav.navigate(Routes.channel(channelUrlOrId))
                }
            )
        }

        composable(
            route = Routes.Channel,
            arguments = listOf(navArgument("channel") { type = NavType.StringType })
        ) { entry ->
            val encoded = entry.arguments?.getString("channel").orEmpty()
            val channelKey = URLDecoder.decode(encoded, StandardCharsets.UTF_8.toString())
            LaunchedEffect(channelKey) {
                viewModel.loadChannel(channelKey)
            }
            ChannelScreen(
                state = channelState,
                onBack = { rootNav.popBackStack() },
                onToggleSubscribe = viewModel::toggleChannelSubscribe,
                onOpenVideo = { item -> rootNav.navigate(Routes.detail(item.url)) }
            )
        }

        composable(Routes.History) {
            Box(modifier = Modifier.fillMaxSize()) {
                HistoryScreen(
                    history = watchHistory,
                    signedIn = authState is AuthState.SignedIn,
                    onBack = { rootNav.popBackStack() },
                    onOpenVideo = { item: WatchHistoryEntity ->
                        rootNav.navigate(Routes.detail(item.videoUrl))
                    },
                    onDelete = { viewModel.deleteWatchHistoryItem(it.videoId) },
                    onClear = viewModel::clearWatchHistory
                )
                nowPlaying?.let { playing ->
                    MiniPlayerBar(
                        state = playing,
                        onExpand = { rootNav.navigate(Routes.detail(playing.videoUrl)) },
                        onPlayPause = viewModel::togglePlayPause,
                        onClose = viewModel::closeNowPlaying,
                        onApplyBetterConnection = viewModel::applyBetterConnection,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }

        composable(Routes.Settings) {
            Box(modifier = Modifier.fillMaxSize()) {
                SettingsScreen(
                    state = settings,
                    headerStatus = headerStatus,
                    onBack = { rootNav.popBackStack() },
                    onProxyEnabled = viewModel::setProxyEnabled,
                    onProxyTimeoutSec = viewModel::setProxyTimeoutSec,
                    onProxySource = viewModel::setProxySource,
                    onForceSwitchProxy = viewModel::forceSwitchProxy,
                    onApplyCustomProxy = viewModel::applyCustomProxyFromSettings,
                    onPreferredAudioLanguage = viewModel::setPreferredAudioLanguage,
                    onNotifyNewVideos = viewModel::setNotifyNewVideos,
                    onAudioOnlyOnBackground = viewModel::setAudioOnlyOnBackground,
                    onContentCountry = viewModel::setContentCountry
                )
                nowPlaying?.let { playing ->
                    MiniPlayerBar(
                        state = playing,
                        onExpand = { rootNav.navigate(Routes.detail(playing.videoUrl)) },
                        onPlayPause = viewModel::togglePlayPause,
                        onClose = viewModel::closeNowPlaying,
                        onApplyBetterConnection = viewModel::applyBetterConnection,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }

        composable(
            route = Routes.Legal,
            arguments = listOf(navArgument("type") { type = NavType.StringType })
        ) { entry ->
            val type = entry.arguments?.getString("type").orEmpty()
            LegalScreen(type = type, onBack = { rootNav.popBackStack() })
        }

        composable(Routes.DownloadsHelp) {
            DownloadsHelpScreen(onBack = { rootNav.popBackStack() })
        }
    }
}

@Composable
private fun MainShell(
    feedState: FeedUiState,
    shortsState: ShortsUiState,
    searchState: SearchUiState,
    downloads: List<DownloadRecord>,
    watchHistory: List<WatchHistoryEntity>,
    subscriptions: List<SubscriptionEntity>,
    authState: AuthState,
    nowPlaying: NowPlayingUiState?,
    settings: SettingsUiState,
    headerStatus: String?,
    onRefreshFeed: () -> Unit,
    onRefreshShorts: () -> Unit,
    onShortsPageChanged: (Int) -> Unit,
    onEnterShorts: () -> Unit,
    onLeaveShorts: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onRecentSearch: (String) -> Unit,
    onRemoveRecentSearch: (String) -> Unit,
    onClearRecentSearches: () -> Unit,
    onOpenVideo: (String) -> Unit,
    onExpandNowPlaying: () -> Unit,
    onPlayPauseNowPlaying: () -> Unit,
    onCloseNowPlaying: () -> Unit,
    onApplyBetterConnection: () -> Unit,
    onScreenSwitch: () -> Unit,
    onDeleteDownload: (DownloadRecord) -> Unit,
    onClearDownloads: () -> Unit,
    onOpenLegal: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleNotify: (SubscriptionEntity, Boolean) -> Unit,
    onUnsubscribe: (SubscriptionEntity) -> Unit,
    onOpenChannel: (SubscriptionEntity) -> Unit,
    onForceSwitchProxy: () -> Unit,
    onLoginClick: () -> Unit
) {
    val tabNav = rememberNavController()
    val tabs = listOf(
        TabItem(Routes.Feed, "Главная", Icons.Default.Home),
        TabItem(Routes.Shorts, "Shorts", Icons.Default.Videocam),
        TabItem(Routes.Subscriptions, "Подписки", Icons.Default.Subscriptions),
        TabItem(Routes.Library, "Вы", Icons.Default.VideoLibrary)
    )
    val backStack by tabNav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val hideMiniPlayer = currentRoute == Routes.Shorts

    Scaffold(
        bottomBar = {
            Column {
                if (!hideMiniPlayer) {
                    nowPlaying?.let { playing ->
                        MiniPlayerBar(
                            state = playing,
                            onExpand = onExpandNowPlaying,
                            onPlayPause = onPlayPauseNowPlaying,
                            onClose = onCloseNowPlaying,
                            onApplyBetterConnection = onApplyBetterConnection
                        )
                    }
                }
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                onScreenSwitch()
                                tabNav.navigate(tab.route) {
                                    popUpTo(tabNav.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = tabNav,
            startDestination = Routes.Feed,
            // Only pad for bottom nav / mini-player. Top insets belong to each
            // screen TopAppBar — otherwise the title sits too far below the status bar.
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding())
        ) {
            composable(Routes.Feed) {
                FeedScreen(
                    state = feedState,
                    onRefresh = onRefreshFeed,
                    onOpenVideo = { onOpenVideo(it.url) },
                    onOpenSearch = {
                        tabNav.navigate(Routes.Search) {
                            launchSingleTop = true
                        }
                    },
                    proxyEnabled = settings.proxyEnabled,
                    isSwitchingProxy = settings.isSwitchingProxy,
                    onForceSwitchProxy = onForceSwitchProxy,
                    headerStatus = headerStatus
                )
            }
            composable(Routes.Shorts) {
                ShortsScreen(
                    state = shortsState,
                    isPlaying = nowPlaying?.isPlaying == true,
                    onRefresh = onRefreshShorts,
                    onPageChanged = onShortsPageChanged,
                    onTogglePlayPause = onPlayPauseNowPlaying,
                    onOpenVideo = { onOpenVideo(it.url) },
                    onEnter = onEnterShorts,
                    onLeave = onLeaveShorts
                )
            }
            composable(Routes.Subscriptions) {
                SubscriptionsScreen(
                    subscriptions = subscriptions,
                    onOpenChannel = onOpenChannel,
                    onToggleNotify = onToggleNotify,
                    onUnsubscribe = onUnsubscribe,
                    proxyEnabled = settings.proxyEnabled,
                    isSwitchingProxy = settings.isSwitchingProxy,
                    onForceSwitchProxy = onForceSwitchProxy,
                    headerStatus = headerStatus
                )
            }
            composable(Routes.Search) {
                SearchScreen(
                    state = searchState,
                    onBack = { tabNav.popBackStack() },
                    onQueryChange = onQueryChange,
                    onSubmit = onSubmitSearch,
                    onRecentClick = onRecentSearch,
                    onRemoveRecent = onRemoveRecentSearch,
                    onClearRecent = onClearRecentSearches,
                    onOpenVideo = { onOpenVideo(it.url) }
                )
            }
            composable(Routes.Library) {
                LibraryScreen(
                    downloads = downloads,
                    recentHistory = watchHistory.take(20),
                    authState = authState,
                    onOpenHistory = onOpenHistory,
                    onOpenSettings = onOpenSettings,
                    onOpenVideoPage = { record ->
                        if (record.videoUrl.isNotBlank()) onOpenVideo(record.videoUrl)
                    },
                    onOpenHistoryVideo = { entry ->
                        onOpenVideo(entry.videoUrl)
                    },
                    onDelete = onDeleteDownload,
                    onClearDownloads = onClearDownloads,
                    onOpenLegal = onOpenLegal,
                    onOpenHelp = onOpenHelp,
                    onLoginClick = onLoginClick,
                    proxyEnabled = settings.proxyEnabled,
                    isSwitchingProxy = settings.isSwitchingProxy,
                    onForceSwitchProxy = onForceSwitchProxy,
                    headerStatus = headerStatus
                )
            }
        }
    }
}
