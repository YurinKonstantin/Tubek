package ru.tubek.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import ru.tubek.app.player.PlayerController
import ru.tubek.app.ui.components.CompactVideoRow
import ru.tubek.app.ui.components.ProxiedAsyncImage
import ru.tubek.app.ui.viewmodel.DetailUiState
import ru.tubek.app.youtube.PlaybackOption
import ru.tubek.app.youtube.StreamOption
import ru.tubek.app.youtube.VideoDetails
import ru.tubek.app.youtube.VideoItem
import ru.tubek.app.youtube.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDetailScreen(
    state: DetailUiState,
    onBack: () -> Unit,
    onSelectPlayback: (PlaybackOption) -> Unit,
    onSelectAudioLanguage: (String) -> Unit = {},
    onSelectDownload: (StreamOption) -> Unit,
    onToggleSubscribe: () -> Unit,
    onShowDownloadSheet: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onDownloadMessageShown: () -> Unit,
    onOpenRelated: (VideoItem) -> Unit = {},
    onContinueWatching: () -> Unit = {},
    onStartFromBeginning: () -> Unit = {},
    onApplyBetterConnection: () -> Unit = {},
    onOpenChannel: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val view = LocalView.current
    var fullscreen by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onDownload()
        } else {
            Toast.makeText(context, "Без уведомлений прогресс не будет показан", Toast.LENGTH_SHORT).show()
            onDownload()
        }
    }

    // Экран не гаснет во время просмотра
    DisposableEffect(Unit) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            view.keepScreenOn = previous
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(state.downloadStarted) {
        if (state.downloadStarted) {
            Toast.makeText(
                context,
                "Загрузка началась. Файл: Download/Tubik",
                Toast.LENGTH_LONG
            ).show()
            onDownloadMessageShown()
        }
    }

    if (fullscreen) {
        FullscreenPlayerDialog(onExit = { fullscreen = false })
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Смотреть") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            state.error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                }
            }

            else -> {
                val details = state.details
                if (details == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Нет данных")
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .background(Color.Black)
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        layoutParams = FrameLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                        useController = true
                                        keepScreenOn = true
                                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        player = PlayerController.get(ctx).player
                                    }
                                },
                                update = { view ->
                                    view.player = PlayerController.get(view.context).player
                                    view.keepScreenOn = true
                                },
                                onRelease = { view ->
                                    view.player = null
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                            IconButton(
                                onClick = { fullscreen = true },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Fullscreen,
                                    contentDescription = "Полный экран",
                                    tint = Color.White
                                )
                            }
                        }

                        if (state.betterConnectionAvailable) {
                            TextButton(
                                onClick = onApplyBetterConnection,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Переключиться на лучшее качество связи")
                            }
                        }

                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = details.item.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (!details.channelUrl.isNullOrBlank() ||
                                            !details.channelId.isNullOrBlank()
                                        ) {
                                            Modifier.clickable {
                                                onOpenChannel(
                                                    details.channelUrl
                                                        ?: details.channelId.orEmpty()
                                                )
                                            }
                                        } else {
                                            Modifier
                                        }
                                    )
                            ) {
                                val avatar = details.channelAvatarUrl
                                    ?: details.item.channelAvatarUrl
                                if (!avatar.isNullOrBlank()) {
                                    ProxiedAsyncImage(
                                        url = avatar,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = details.item.uploader.take(1).uppercase()
                                                .ifBlank { "T" },
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = details.item.uploader,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = buildString {
                                            details.viewCount?.let { append(formatViews(it)) }
                                            details.item.durationSeconds.formatDuration()
                                                .takeIf { it.isNotEmpty() }
                                                ?.let {
                                                    if (isNotEmpty()) append(" · ")
                                                    append(it)
                                                }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                                if (details.channelId != null) {
                                    if (state.isSubscribed) {
                                        OutlinedButton(onClick = onToggleSubscribe) {
                                            Text("Отписка")
                                        }
                                    } else {
                                        Button(onClick = onToggleSubscribe) {
                                            Text("Подписка")
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(12.dp))
                            DetailActionIconRow(
                                details = details,
                                selectedPlayback = state.selectedPlayback,
                                onSelectPlayback = onSelectPlayback,
                                onSelectAudioLanguage = onSelectAudioLanguage,
                                onShowDownloadSheet = { onShowDownloadSheet(true) }
                            )

                            if (details.description.isNotBlank()) {
                                Spacer(Modifier.height(12.dp))
                                var descriptionExpanded by remember(details.item.id) {
                                    mutableStateOf(false)
                                }
                                Text(
                                    text = details.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                                    maxLines = if (descriptionExpanded) Int.MAX_VALUE else 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { descriptionExpanded = !descriptionExpanded }
                                )
                                Text(
                                    text = if (descriptionExpanded) "Свернуть" else "Ещё",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .padding(top = 4.dp)
                                        .clickable { descriptionExpanded = !descriptionExpanded }
                                )
                            }

                            if (details.related.isNotEmpty()) {
                                Spacer(Modifier.height(16.dp))
                                Text("Похожие", fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(8.dp))
                                details.related.forEach { item ->
                                    CompactVideoRow(item = item, onClick = { onOpenRelated(item) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.showResumeDialog && state.resumePositionMs != null) {
        val positionLabel = formatPositionMs(state.resumePositionMs!!)
        AlertDialog(
            onDismissRequest = onContinueWatching,
            title = { Text("Продолжить просмотр?") },
            text = {
                Text("Вы остановились на $positionLabel. Продолжить с этого места?")
            },
            confirmButton = {
                TextButton(onClick = onContinueWatching) {
                    Text("Продолжить")
                }
            },
            dismissButton = {
                TextButton(onClick = onStartFromBeginning) {
                    Text("С начала")
                }
            }
        )
    }

    val downloadDetails = state.details
    if (state.showDownloadSheet && downloadDetails != null) {
        AlertDialog(
            onDismissRequest = { onShowDownloadSheet(false) },
            title = { Text("Скачать") },
            text = {
                Column {
                    Text(
                        text = "Только готовые файлы со звуком (muxed). Высокие качества могут быть недоступны.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(8.dp))
                    downloadDetails.streams.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    width = if (state.selectedDownload == option) 2.dp else 1.dp,
                                    color = if (state.selectedDownload == option) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { onSelectDownload(option) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = state.selectedDownload == option,
                                onClick = { onSelectDownload(option) }
                            )
                            Text(option.label)
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            onDownload()
                        }
                    }
                ) {
                    Text("Скачать")
                }
            },
            dismissButton = {
                TextButton(onClick = { onShowDownloadSheet(false) }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun DetailActionIconRow(
    details: VideoDetails,
    selectedPlayback: PlaybackOption?,
    onSelectPlayback: (PlaybackOption) -> Unit,
    onSelectAudioLanguage: (String) -> Unit,
    onShowDownloadSheet: () -> Unit
) {
    val audioLanguages = details.audioLanguages
    val videoOptions = details.playbackOptions.filter { !it.isAudioOnly }
    val audioOption = details.playbackOptions.firstOrNull { it.isAudioOnly }
    var langExpanded by remember(details.item.id) { mutableStateOf(false) }
    var qualityExpanded by remember(details.item.id) { mutableStateOf(false) }
    val selectedLang = audioLanguages.firstOrNull {
        it.code.equals(details.selectedAudioLanguage, ignoreCase = true)
    } ?: audioLanguages.firstOrNull()
    val selectedQuality = selectedPlayback
        ?: videoOptions.firstOrNull()
        ?: audioOption

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top
    ) {
        if (audioLanguages.size > 1) {
            Box {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { langExpanded = true }) {
                        Icon(Icons.Default.Translate, contentDescription = "Язык звука")
                    }
                    Text(
                        text = selectedLang?.label ?: "Язык",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(72.dp)
                    )
                }
                DropdownMenu(
                    expanded = langExpanded,
                    onDismissRequest = { langExpanded = false }
                ) {
                    audioLanguages.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(lang.label) },
                            onClick = {
                                langExpanded = false
                                onSelectAudioLanguage(lang.code)
                            }
                        )
                    }
                }
            }
        }

        if (videoOptions.isNotEmpty() || audioOption != null) {
            Box {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { qualityExpanded = true }) {
                        Icon(Icons.Default.HighQuality, contentDescription = "Качество")
                    }
                    Text(
                        text = selectedQuality?.label ?: "Качество",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(72.dp)
                    )
                }
                DropdownMenu(
                    expanded = qualityExpanded,
                    onDismissRequest = { qualityExpanded = false }
                ) {
                    videoOptions.forEach { opt ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    buildString {
                                        append(opt.label)
                                        if (!opt.audioUrl.isNullOrBlank()) {
                                            append(" · видео+звук")
                                        }
                                    }
                                )
                            },
                            onClick = {
                                qualityExpanded = false
                                onSelectPlayback(opt)
                            }
                        )
                    }
                    if (audioOption != null) {
                        DropdownMenuItem(
                            text = { Text(audioOption.label) },
                            onClick = {
                                qualityExpanded = false
                                onSelectPlayback(audioOption)
                            }
                        )
                    }
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onShowDownloadSheet) {
                Icon(Icons.Default.Download, contentDescription = "Скачать")
            }
            Text(
                text = "Скачать",
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(72.dp)
            )
        }
    }
}

@Composable
private fun FullscreenPlayerDialog(onExit: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity

    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        BackHandler(onBack = onExit)

        DisposableEffect(Unit) {
            val previousOrientation = activity?.requestedOrientation
                ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            val window = activity?.window
            val controller = window?.let {
                WindowCompat.setDecorFitsSystemWindows(it, false)
                WindowInsetsControllerCompat(it, it.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose {
                activity?.requestedOrientation = previousOrientation
                controller?.show(WindowInsetsCompat.Type.systemBars())
                window?.let { WindowCompat.setDecorFitsSystemWindows(it, true) }
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        useController = true
                        keepScreenOn = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        player = PlayerController.get(ctx).player
                    }
                },
                update = { view ->
                    view.player = PlayerController.get(view.context).player
                    view.keepScreenOn = true
                },
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxSize()
            )
            IconButton(
                onClick = onExit,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
            ) {
                Icon(
                    Icons.Default.FullscreenExit,
                    contentDescription = "Выйти из полного экрана",
                    tint = Color.White
                )
            }
        }
    }
}

private fun formatViews(count: Long): String {
    return when {
        count >= 1_000_000 -> "%.1f млн просмотров".format(count / 1_000_000.0)
        count >= 1_000 -> "%.1f тыс. просмотров".format(count / 1_000.0)
        else -> "$count просмотров"
    }
}

private fun formatPositionMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%d:%02d".format(m, s)
    }
}
