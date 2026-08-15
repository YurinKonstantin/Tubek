package ru.tubek.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.tubek.app.data.WatchHistoryEntity
import ru.tubek.app.ui.components.CompactVideoRow
import ru.tubek.app.youtube.VideoItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    history: List<WatchHistoryEntity>,
    signedIn: Boolean,
    onBack: () -> Unit,
    onOpenVideo: (WatchHistoryEntity) -> Unit,
    onDelete: (WatchHistoryEntity) -> Unit,
    onClear: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (signedIn) "Понравившиеся" else "История",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (history.isNotEmpty() && !signedIn) {
                        TextButton(onClick = onClear) { Text("Очистить") }
                    }
                }
            )
        }
    ) { padding ->
        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (signedIn) {
                        "Нет понравившихся видео на YouTube"
                    } else {
                        "История просмотров пуста"
                    },
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(history, key = { it.videoId }) { item ->
                    Box {
                        CompactVideoRow(
                            item = VideoItem(
                                id = item.videoId,
                                title = item.title,
                                uploader = item.uploader,
                                thumbnailUrl = item.thumbnailUrl,
                                durationSeconds = item.durationSeconds,
                                url = item.videoUrl,
                                uploaderUrl = item.uploaderUrl
                            ),
                            onClick = { onOpenVideo(item) }
                        )
                        if (!signedIn) {
                            IconButton(
                                onClick = { onDelete(item) },
                                modifier = Modifier.align(Alignment.TopEnd)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Удалить")
                            }
                        }
                    }
                }
            }
        }
    }
}
