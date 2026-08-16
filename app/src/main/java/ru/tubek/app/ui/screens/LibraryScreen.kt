package ru.tubek.app.ui.screens

import android.content.Intent
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import ru.tubek.app.data.DownloadRecord
import ru.tubek.app.data.WatchHistoryEntity
import ru.tubek.app.ui.components.AppBarTitleWithStatus
import ru.tubek.app.ui.components.ForceSwitchProxyButton
import ru.tubek.app.ui.components.ProxiedAsyncImage
import ru.tubek.app.ui.viewmodel.AuthState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    downloads: List<DownloadRecord>,
    recentHistory: List<WatchHistoryEntity> = emptyList(),
    authState: AuthState,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenVideoPage: (DownloadRecord) -> Unit,
    onOpenHistoryVideo: (WatchHistoryEntity) -> Unit = {},
    onDelete: (DownloadRecord) -> Unit,
    onClearDownloads: () -> Unit,
    onOpenLegal: () -> Unit,
    onOpenHelp: () -> Unit,
    onLoginClick: () -> Unit,
    proxyEnabled: Boolean = false,
    isSwitchingProxy: Boolean = false,
    onForceSwitchProxy: () -> Unit = {},
    headerStatus: String? = null
) {
    val context = LocalContext.current
    val signedIn = authState is AuthState.SignedIn
    val displayName = when (authState) {
        is AuthState.Guest -> "Гость"
        is AuthState.SignedIn -> authState.displayName
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    AppBarTitleWithStatus(
                        title = "Вы",
                        status = headerStatus
                    )
                },
                actions = {
                    ForceSwitchProxyButton(
                        enabled = proxyEnabled,
                        switching = isSwitchingProxy,
                        onClick = onForceSwitchProxy
                    )
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item {
                ProfileHeader(
                    name = displayName,
                    subtitle = if (signedIn) {
                        "Нажмите, чтобы выйти"
                    } else {
                        "Войти — подписки и понравившиеся"
                    },
                    onClick = onLoginClick
                )
            }

            if (recentHistory.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "История",
                        actionLabel = "Смотреть всё",
                        onAction = onOpenHistory
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        items(recentHistory, key = { it.videoId }) { entry ->
                            HistoryShelfCard(
                                title = entry.title,
                                uploader = entry.uploader,
                                thumbnailUrl = entry.thumbnailUrl,
                                onClick = { onOpenHistoryVideo(entry) }
                            )
                        }
                    }
                }
            }

            item {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                )
                LibraryMenuRow(
                    icon = Icons.Default.History,
                    title = "История",
                    onClick = onOpenHistory
                )
                if (signedIn) {
                    LibraryMenuRow(
                        icon = Icons.Default.ThumbUp,
                        title = "Понравившиеся",
                        onClick = onOpenHistory
                    )
                }
                LibraryMenuRow(
                    icon = Icons.Default.Info,
                    title = "Документы",
                    onClick = onOpenLegal
                )
                LibraryMenuRow(
                    icon = Icons.Default.Download,
                    title = "Куда сохраняются файлы",
                    onClick = onOpenHelp
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Загрузки",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (downloads.isNotEmpty()) {
                        TextButton(onClick = onClearDownloads) { Text("Очистить") }
                    }
                }
            }

            if (downloads.isEmpty()) {
                item {
                    Text(
                        text = "Скачанных файлов пока нет",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
            } else {
                items(downloads, key = { it.id }) { record ->
                    DownloadVideoRow(
                        record = record,
                        onOpenPage = { onOpenVideoPage(record) },
                        onPlayFile = {
                            val file = resolveDownloadFile(record.filePath)
                            if (file != null && file.exists()) {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    context.packageName + ".fileprovider",
                                    file
                                )
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, record.mimeType)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching { context.startActivity(intent) }
                                    .onFailure {
                                        Toast.makeText(context, "Не удалось открыть файл", Toast.LENGTH_SHORT).show()
                                    }
                            } else {
                                Toast.makeText(context, "Файл не найден", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDelete = { onDelete(record) }
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ProfileHeader(
    name: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun HistoryShelfCard(
    title: String,
    uploader: String,
    thumbnailUrl: String?,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(160.dp)
            .clickable(onClick = onClick)
    ) {
        ProxiedAsyncImage(
            url = thumbnailUrl,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
        )
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
        Text(
            uploader,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun LibraryMenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurface
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun DownloadVideoRow(
    record: DownloadRecord,
    onOpenPage: () -> Unit,
    onPlayFile: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenPage)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProxiedAsyncImage(
            url = record.thumbnailUrl,
            modifier = Modifier
                .width(140.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                record.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${record.qualityLabel} · ${record.uploader}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onPlayFile) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Открыть файл")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Удалить")
        }
    }
}

private fun resolveDownloadFile(path: String): File? {
    if (path.startsWith("Download/Tubik/")) {
        val name = path.removePrefix("Download/Tubik/")
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Tubik/$name"
        )
    }
    return File(path).takeIf { it.exists() }
}
