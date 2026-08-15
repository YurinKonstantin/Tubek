package ru.tubek.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.tubek.app.data.Countries
import ru.tubek.app.ui.components.AppBarTitleWithStatus
import ru.tubek.app.ui.components.FeedVideoCard
import ru.tubek.app.ui.components.ForceSwitchProxyButton
import ru.tubek.app.ui.components.ShortsShelfRow
import ru.tubek.app.ui.viewmodel.FeedUiState
import ru.tubek.app.youtube.VideoItem

private sealed interface FeedListItem {
    data class Header(val title: String) : FeedListItem
    data class Video(val item: VideoItem, val key: String) : FeedListItem
    data class ShortsShelf(val items: List<VideoItem>, val key: String) : FeedListItem
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    state: FeedUiState,
    onRefresh: () -> Unit,
    onOpenVideo: (VideoItem) -> Unit,
    onOpenSearch: () -> Unit,
    proxyEnabled: Boolean = false,
    isSwitchingProxy: Boolean = false,
    onForceSwitchProxy: () -> Unit = {},
    headerStatus: String? = null
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    AppBarTitleWithStatus(
                        title = "Tubik",
                        status = headerStatus ?: state.connectionStatus,
                        emphasizeTitle = true
                    )
                },
                actions = {
                    ForceSwitchProxyButton(
                        enabled = proxyEnabled,
                        switching = isSwitchingProxy,
                        onClick = onForceSwitchProxy
                    )
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Default.Search, contentDescription = "Поиск")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isLoading && state.hasContent,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isLoading && !state.hasContent -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                state.error != null && !state.hasContent -> {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.error, color = MaterialTheme.colorScheme.error)
                            Text(
                                text = "Потяните вниз или нажмите обновить.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }

                else -> {
                    val rows = remember(
                        state.fromSubscriptions,
                        state.recommended,
                        state.trending,
                        state.shorts,
                        state.contentCountryCode
                    ) {
                        buildFeedRows(state)
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(rows, key = { row ->
                            when (row) {
                                is FeedListItem.Header -> "h-${row.title}"
                                is FeedListItem.Video -> row.key
                                is FeedListItem.ShortsShelf -> row.key
                            }
                        }) { row ->
                            when (row) {
                                is FeedListItem.Header -> SectionTitle(row.title)
                                is FeedListItem.Video -> {
                                    FeedVideoCard(
                                        item = row.item,
                                        onClick = { onOpenVideo(row.item) }
                                    )
                                }
                                is FeedListItem.ShortsShelf -> {
                                    ShortsShelfRow(
                                        items = row.items,
                                        onOpenShort = onOpenVideo
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildFeedRows(state: FeedUiState): List<FeedListItem> {
    val rows = mutableListOf<FeedListItem>()

    var videoCount = 0
    var shelfIndex = 0
    val shorts = state.shorts

    fun maybeInsertShortsShelf() {
        if (shorts.isEmpty()) return
        if (videoCount == 0 || videoCount % 4 != 0) return
        val shelfSize = minOf(10, shorts.size)
        val start = (shelfIndex * shelfSize) % shorts.size
        val chunk = List(shelfSize) { offset ->
            shorts[(start + offset) % shorts.size]
        }.distinctBy { it.id }
        if (chunk.isNotEmpty()) {
            rows += FeedListItem.ShortsShelf(
                items = chunk,
                key = "shorts-shelf-$shelfIndex-$start"
            )
            shelfIndex++
        }
    }

    fun appendSection(title: String, videos: List<VideoItem>, keyPrefix: String) {
        if (videos.isEmpty()) return
        rows += FeedListItem.Header(title)
        videos.forEach { item ->
            rows += FeedListItem.Video(item, "$keyPrefix-${item.id}")
            videoCount++
            maybeInsertShortsShelf()
        }
    }

    appendSection("Подписки", state.fromSubscriptions, "sub")
    appendSection("Для вас", state.recommended, "rec")
    val trendingTitle = state.contentCountryCode?.let { code ->
        "В тренде · ${Countries.nameFor(code)}"
    } ?: "В тренде"
    appendSection(trendingTitle, state.trending, "trend")

    return rows
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
    )
}
