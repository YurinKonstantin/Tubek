package ru.tubek.app.ui.screens

import android.content.Intent
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import ru.tubek.app.data.DownloadRecord
import ru.tubek.app.ui.components.AppBarTitleWithStatus
import ru.tubek.app.ui.components.ForceSwitchProxyButton
import ru.tubek.app.ui.viewmodel.AuthState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    downloads: List<DownloadRecord>,
    authState: AuthState,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenVideoPage: (DownloadRecord) -> Unit,
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    AppBarTitleWithStatus(
                        title = "Библиотека",
                        status = headerStatus
                    )
                },
                actions = {
                    ForceSwitchProxyButton(
                        enabled = proxyEnabled,
                        switching = isSwitchingProxy,
                        onClick = onForceSwitchProxy
                    )
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item {
                LibraryMenuRow(
                    icon = Icons.Default.Person,
                    title = when (authState) {
                        is AuthState.Guest -> "Войти через Google"
                        is AuthState.SignedIn -> authState.displayName
                    },
                    subtitle = when (authState) {
                        is AuthState.Guest -> "Подписки и понравившиеся с YouTube"
                        is AuthState.SignedIn -> "Нажмите, чтобы выйти"
                    },
                    onClick = onLoginClick
                )
                LibraryMenuRow(
                    icon = Icons.Default.History,
                    title = if (authState is AuthState.SignedIn) "Понравившиеся" else "История",
                    onClick = onOpenHistory
                )
                LibraryMenuRow(
                    icon = Icons.Default.Settings,
                    title = "Настройки",
                    onClick = onOpenSettings
                )
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
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Загрузки", fontWeight = FontWeight.SemiBold)
                    if (downloads.isNotEmpty()) {
                        TextButton(onClick = onClearDownloads) { Text("Очистить") }
                    }
                }
            }

            if (downloads.isEmpty()) {
                item {
                    Text(
                        text = "Скачанных файлов пока нет",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
            } else {
                items(downloads, key = { it.id }) { record ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (record.videoUrl.isNotBlank()) onOpenVideoPage(record)
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                record.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "${record.qualityLabel} · ${record.uploader}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                            )
                        }
                        IconButton(
                            onClick = {
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
                            }
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Открыть файл")
                        }
                        IconButton(onClick = { onDelete(record) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить")
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, contentDescription = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
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
