package ru.tubek.app.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.tubek.app.ui.components.CompactVideoRow
import ru.tubek.app.ui.viewmodel.SearchUiState
import ru.tubek.app.youtube.VideoItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: SearchUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onRecentClick: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
    onOpenVideo: (VideoItem) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val showRecent = !state.isLoading && state.results.isEmpty() && state.recentQueries.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Поиск", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Ссылка или запрос") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    IconButton(
                        onClick = {
                            val text = clipboard.getText()?.text
                            if (!text.isNullOrBlank()) onQueryChange(text)
                        }
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Вставить")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onSubmit, shape = RoundedCornerShape(12.dp)) {
                    Text("Найти / Открыть")
                }
            }
            Text(
                text = "Нужен доступ к YouTube",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                modifier = Modifier.padding(vertical = 6.dp)
            )

            when {
                state.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                state.results.isNotEmpty() -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.results, key = { it.id }) { item ->
                            CompactVideoRow(item = item, onClick = { onOpenVideo(item) })
                        }
                    }
                }

                showRecent -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Недавние запросы",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                TextButton(onClick = onClearRecent) {
                                    Text("Очистить")
                                }
                            }
                        }
                        if (state.error != null) {
                            item {
                                Text(
                                    text = state.error,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                        }
                        items(state.recentQueries, key = { it }) { query ->
                            RecentSearchRow(
                                query = query,
                                onClick = { onRecentClick(query) },
                                onRemove = { onRemoveRecent(query) }
                            )
                        }
                    }
                }

                state.error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(state.error, color = MaterialTheme.colorScheme.error)
                    }
                }

                else -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Введите запрос или вставьте ссылку на видео",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSearchRow(
    query: String,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.History,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
        )
        Text(
            text = query,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Удалить",
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
            )
        }
    }
}
