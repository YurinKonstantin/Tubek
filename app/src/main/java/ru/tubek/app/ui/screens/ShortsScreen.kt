package ru.tubek.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import ru.tubek.app.player.PlayerController
import ru.tubek.app.ui.viewmodel.ShortsUiState
import ru.tubek.app.youtube.VideoItem

@Composable
fun ShortsScreen(
    state: ShortsUiState,
    isPlaying: Boolean,
    onRefresh: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onTogglePlayPause: () -> Unit,
    onOpenVideo: (VideoItem) -> Unit,
    onEnter: () -> Unit,
    onLeave: () -> Unit
) {
    DisposableEffect(Unit) {
        onEnter()
        onDispose { onLeave() }
    }

    LaunchedEffect(Unit) {
        if (!state.hasContent && !state.isLoading) {
            onRefresh()
        }
    }

    when {
        state.isLoading && !state.hasContent -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        text = state.connectionStatus ?: "Загружаем Shorts…",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp, start = 24.dp, end = 24.dp)
                    )
                }
            }
        }

        state.error != null && !state.hasContent -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.error,
                        color = Color(0xFFFF8A80),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    TextButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White)
                        Text("Обновить", color = Color.White, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        else -> {
            val pagerState = rememberPagerState(
                initialPage = state.currentIndex.coerceIn(0, (state.items.size - 1).coerceAtLeast(0)),
                pageCount = { state.items.size }
            )

            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.settledPage }
                    .distinctUntilChanged()
                    .collect { page -> onPageChanged(page) }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                VerticalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1
                ) { page ->
                    val item = state.items[page]
                    val isCurrent = page == pagerState.settledPage
                    ShortPage(
                        item = item,
                        isCurrent = isCurrent,
                        isPlaying = isPlaying && isCurrent,
                        isResolving = state.isResolving && isCurrent,
                        onTogglePlayPause = onTogglePlayPause,
                        onOpenVideo = { onOpenVideo(item) }
                    )
                }

                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Обновить",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortPage(
    item: VideoItem,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isResolving: Boolean,
    onTogglePlayPause: () -> Unit,
    onOpenVideo: () -> Unit
) {
    val context = LocalContext.current
    val playerController = remember { PlayerController.get(context) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(onClick = onTogglePlayPause)
    ) {
        AsyncImage(
            model = item.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        if (isCurrent) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        player = playerController.player
                    }
                },
                update = { view ->
                    view.player = playerController.player
                },
                onRelease = { view ->
                    view.player = null
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                    )
                )
                .padding(horizontal = 16.dp, vertical = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.uploader.ifBlank { "Канал" },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.title,
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onOpenVideo) {
                    Icon(
                        Icons.Default.OpenInNew,
                        contentDescription = "Открыть видео",
                        tint = Color.White
                    )
                }
            }
        }

        when {
            isResolving -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }
            isCurrent && !isPlaying -> {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Воспроизвести",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}
