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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.tubek.app.data.SubscriptionEntity
import ru.tubek.app.ui.components.AppBarTitleWithStatus
import ru.tubek.app.ui.components.ForceSwitchProxyButton
import ru.tubek.app.ui.components.ProxiedAsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    subscriptions: List<SubscriptionEntity>,
    onOpenChannel: (SubscriptionEntity) -> Unit,
    onToggleNotify: (SubscriptionEntity, Boolean) -> Unit,
    onUnsubscribe: (SubscriptionEntity) -> Unit,
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
                        title = "Подписки",
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
        if (subscriptions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Пока нет подписок. Откройте видео и нажмите «Подписаться».",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(subscriptions, key = { it.channelId }) { sub ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenChannel(sub) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (!sub.avatarUrl.isNullOrBlank()) {
                            ProxiedAsyncImage(
                                url = sub.avatarUrl,
                                contentDescription = sub.name,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = sub.name.take(1).uppercase().ifBlank { "C" },
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(sub.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (sub.notifyEnabled) "Уведомления включены" else "Уведомления выкл.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        IconButton(onClick = { onToggleNotify(sub, !sub.notifyEnabled) }) {
                            Icon(
                                if (sub.notifyEnabled) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                                contentDescription = "Уведомления"
                            )
                        }
                        TextButton(onClick = { onUnsubscribe(sub) }) {
                            Text("Отписка")
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}
