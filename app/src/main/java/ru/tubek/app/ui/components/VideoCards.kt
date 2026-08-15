package ru.tubek.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.tubek.app.youtube.VideoItem
import ru.tubek.app.youtube.formatDuration

@Composable
fun FeedVideoCard(
    item: VideoItem,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(bottom = 16.dp)
    ) {
        Box {
            ProxiedAsyncImage(
                url = item.thumbnailUrl,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            )
            val duration = item.durationSeconds.formatDuration()
            if (duration.isNotEmpty()) {
                Text(
                    text = duration,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item.uploader.take(1).uppercase().ifBlank { "T" },
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.uploader,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
                )
            }
        }
    }
}

@Composable
fun CompactVideoRow(
    item: VideoItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            ProxiedAsyncImage(
                url = item.thumbnailUrl,
                modifier = Modifier
                    .width(148.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
            )
            val duration = item.durationSeconds.formatDuration()
            if (duration.isNotEmpty()) {
                Text(
                    text = duration,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.uploader,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
            )
        }
    }
}

@Composable
fun ShortsShelfRow(
    items: List<VideoItem>,
    onOpenShort: (VideoItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(
            text = "Shorts",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(items, key = { it.id }) { item ->
                ShortsShelfCard(item = item, onClick = { onOpenShort(item) })
            }
        }
    }
}

@Composable
private fun ShortsShelfCard(
    item: VideoItem,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(128.dp)
            .clickable(onClick = onClick)
    ) {
        Box {
            ProxiedAsyncImage(
                url = item.thumbnailUrl,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(12.dp))
            )
            Text(
                text = "Shorts",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            val duration = item.durationSeconds.formatDuration()
            if (duration.isNotEmpty()) {
                Text(
                    text = duration,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = item.uploader,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
    }
}
