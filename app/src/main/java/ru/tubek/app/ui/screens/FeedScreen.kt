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
import ru.tubek.app.ui.components.FeedVideoCard
import ru.tubek.app.ui.components.ShortsShelfRow
import ru.tubek.app.ui.viewmodel.FeedUiState
import ru.tubek.app.youtube.VideoItem

private sealed interface FeedListItem {
    data class Header(val title: String) : FeedListItem
    data class Video(val item: VideoItem, val key: String) : FeedListItem
    data class ShortsShelf(val items: List<VideoItem>, val key: String) : FeedListItem
    data class Status(val text: String) : FeedListItem
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    state: FeedUiState,
    onRefresh: () -> Unit,
    onOpenVideo: (VideoItem) -> Unit,
    onOpenSearch: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Tubik", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                },
                actions = {
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
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = state.connectionStatus
                                    ?: "Ищем рабочее подключение. Это может занять некоторое время…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                                modifier = Modifier.padding(top = 16.dp)
                            )
                        }
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
                        state.contentCountryCode,
                        state.connectionStatus
                    ) {
                        buildFeedRows(state)
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(rows, key = { row ->
                            when (row) {
                                is FeedListItem.Header -> "h-${row.title}"
                                is FeedListItem.Video -> row.key
                                is FeedListItem.ShortsShelf -> row.key
                                is FeedListItem.Status -> "status"
                            }
                        }) { row ->
                            when (row) {
                                is FeedListItem.Status -> {
                                    Text(
                                        text = row.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
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
    state.connectionStatus?.let { rows += FeedListItem.Status(it) }

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
