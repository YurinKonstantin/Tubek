package ru.tubek.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.tubek.app.data.Countries
import ru.tubek.app.data.PreferencesRepository
import ru.tubek.app.proxy.CustomProxyMode
import ru.tubek.app.proxy.ProxySource
import ru.tubek.app.ui.viewmodel.SettingsUiState
import java.util.Locale

private val PROXY_TIMEOUT_OPTIONS = listOf(2, 3, 5, 8, 10, 15, 20, 30)

private val AUDIO_LANGUAGE_OPTIONS = listOf(
    PreferencesRepository.AUDIO_LANGUAGE_AUTO,
    "ru", "en", "uk", "be", "kk", "de", "fr", "es", "tr",
    "pl", "it", "pt", "ar", "zh", "ja", "ko"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onProxyEnabled: (Boolean) -> Unit,
    onProxyTimeoutSec: (Int) -> Unit,
    onProxySource: (ProxySource) -> Unit,
    onForceSwitchProxy: () -> Unit,
    onApplyCustomProxy: (address: String, mode: CustomProxyMode) -> Unit,
    onPreferredAudioLanguage: (String) -> Unit,
    onNotifyNewVideos: (Boolean) -> Unit,
    onAudioOnlyOnBackground: (Boolean) -> Unit,
    onContentCountry: (String) -> Unit
) {
    var customAddressDraft by remember(state.customProxyAddress) {
        mutableStateOf(state.customProxyAddress)
    }
    var customModeDraft by remember(state.customProxyMode) {
        mutableStateOf(state.customProxyMode)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки", fontWeight = FontWeight.Bold) },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("Страна контента", fontWeight = FontWeight.SemiBold)
            Text(
                text = "Тренды на главной. Автоопределение: ${Countries.nameFor(state.detectedCountryCode)} (${state.detectedCountryCode})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            CountryDropdown(
                preference = state.countryPreference,
                detectedCode = state.detectedCountryCode,
                onSelect = onContentCountry
            )
            Text(
                text = "Сейчас для ленты: ${Countries.nameFor(state.effectiveCountryCode)} (${state.effectiveCountryCode})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(Modifier.height(16.dp))
            Text("Язык контента и звука", fontWeight = FontWeight.SemiBold)
            Text(
                text = "Названия, описания и звуковая дорожка. По умолчанию — язык устройства.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            SettingsDropdown(
                label = "Предпочтительный язык",
                selectedLabel = audioLanguageLabel(state.preferredAudioLanguage),
                enabled = true,
                options = AUDIO_LANGUAGE_OPTIONS.map { it to audioLanguageLabel(it) },
                onSelect = onPreferredAudioLanguage
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Прокси", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            SettingSwitch(
                title = "Использовать прокси",
                subtitle = if (state.proxyEnabled) {
                    "Вкл. Сейчас: ${state.proxyStatus}"
                } else {
                    "Выкл. — только прямое подключение"
                },
                checked = state.proxyEnabled,
                onCheckedChange = onProxyEnabled
            )
            Spacer(Modifier.height(12.dp))
            SettingsDropdown(
                label = "Автопереключение (сек)",
                selectedLabel = "${state.proxyTimeoutSec} сек",
                enabled = state.proxyEnabled,
                options = PROXY_TIMEOUT_OPTIONS.map { it to "$it сек" },
                onSelect = { onProxyTimeoutSec(it) }
            )
            Spacer(Modifier.height(12.dp))
            SettingsDropdown(
                label = "Источник прокси",
                selectedLabel = state.proxySource.labelRu,
                enabled = state.proxyEnabled && customModeDraft != CustomProxyMode.ONLY,
                options = ProxySource.entries.map { it to it.labelRu },
                onSelect = onProxySource
            )
            Spacer(Modifier.height(16.dp))
            Text("Свой прокси", fontWeight = FontWeight.SemiBold)
            Text(
                text = "host:port, http://host:port или socks5://host:port",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            OutlinedTextField(
                value = customAddressDraft,
                onValueChange = { customAddressDraft = it },
                enabled = state.proxyEnabled,
                label = { Text("Адрес") },
                placeholder = { Text("1.2.3.4:8080") },
                singleLine = true,
                isError = state.customProxyError != null,
                supportingText = state.customProxyError?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            SettingsDropdown(
                label = "Режим своего прокси",
                selectedLabel = customModeDraft.labelRu,
                enabled = state.proxyEnabled,
                options = CustomProxyMode.entries.map { it to it.labelRu },
                onSelect = { customModeDraft = it }
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { onApplyCustomProxy(customAddressDraft, customModeDraft) },
                enabled = state.proxyEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Применить свой прокси")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onForceSwitchProxy,
                enabled = state.proxyEnabled && !state.isSwitchingProxy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.isSwitchingProxy) "Ищем прокси…" else "Сменить прокси сейчас")
            }
            if (state.proxyStatsSummary.isNotBlank()) {
                Text(
                    text = "Предпочтение: ${state.proxyStatsSummary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SettingSwitch(
                title = "Уведомления о новых видео",
                subtitle = "Проверка подписанных каналов",
                checked = state.notifyNewVideos,
                onCheckedChange = onNotifyNewVideos
            )
            Spacer(Modifier.height(12.dp))
            SettingSwitch(
                title = "Только звук при сворачивании",
                subtitle = "Экономия трафика в фоне",
                checked = state.audioOnlyOnBackground,
                onCheckedChange = onAudioOnlyOnBackground
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountryDropdown(
    preference: String,
    detectedCode: String,
    onSelect: (String) -> Unit
) {
    val options = buildList {
        add(Countries.AUTO to "Авто (${Countries.nameFor(detectedCode)})")
        Countries.manual.forEach { add(it.code to "${it.nameRu} (${it.code})") }
    }
    val selectedLabel = when {
        preference.equals(Countries.AUTO, ignoreCase = true) ->
            "Авто (${Countries.nameFor(detectedCode)})"
        else -> "${Countries.nameFor(preference)} (${preference.uppercase()})"
    }
    SettingsDropdown(
        label = "Страна",
        selectedLabel = selectedLabel,
        enabled = true,
        options = options,
        onSelect = onSelect
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SettingsDropdown(
    label: String,
    selectedLabel: String,
    enabled: Boolean,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it }
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled)
                .fillMaxWidth()
        )
        // ExposedDropdownMenu — метод scope ExposedDropdownMenuBox, не отдельный импорт
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (value, title) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun audioLanguageLabel(code: String): String {
    if (code.equals(PreferencesRepository.AUDIO_LANGUAGE_AUTO, ignoreCase = true)) {
        val device = Locale.getDefault().language
        val name = Locale(device).getDisplayLanguage(Locale("ru"))
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString() }
        return "Авто ($name)"
    }
    val name = Locale(code).getDisplayLanguage(Locale("ru"))
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString() }
        .ifBlank { code.uppercase(Locale.US) }
    return "$name (${code.lowercase(Locale.US)})"
}
