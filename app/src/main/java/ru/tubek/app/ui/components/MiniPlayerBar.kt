package ru.tubek.app.ui.components

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import ru.tubek.app.network.CoilLoader
import ru.tubek.app.player.PlayerController
import ru.tubek.app.ui.viewmodel.NowPlayingUiState

@Composable
fun MiniPlayerBar(
    state: NowPlayingUiState,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onClose: () -> Unit,
    onApplyBetterConnection: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (state.betterConnectionAvailable && onApplyBetterConnection != null) {
                Text(
                    text = "Лучшее качество связи — нажмите",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onApplyBetterConnection)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clickable(onClick = onExpand)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(96.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                useController = false
                                player = PlayerController.get(ctx).player
                            }
                        },
                        update = { view ->
                            view.player = PlayerController.get(view.context).player
                        },
                        onRelease = { view -> view.player = null },
                        modifier = Modifier.matchParentSize()
                    )
                }
                Spacer(modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.item.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = state.item.uploader,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
                IconButton(onClick = onPlayPause) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Пауза" else "Играть"
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть")
                }
            }
        }
    }
}

@Composable
fun ProxiedAsyncImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val context = LocalContext.current
    val model = ImageRequest.Builder(context)
        .data(url)
        .crossfade(true)
        .build()
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        imageLoader = CoilLoader.get(context),
        contentScale = ContentScale.Crop,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)
    )
}
