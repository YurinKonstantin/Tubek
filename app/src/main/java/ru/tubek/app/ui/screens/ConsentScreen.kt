package ru.tubek.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment

@Composable
fun ConsentScreen(
    onAccept: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenDisclaimer: () -> Unit
) {
    var privacyChecked by rememberSaveable { mutableStateOf(false) }
    var disclaimerChecked by rememberSaveable { mutableStateOf(false) }
    val canContinue = privacyChecked && disclaimerChecked

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Tubik",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Перед первым запуском подтвердите согласие с документами.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Приложение предназначено для распространения в RuStore. Любые дальнейшие действия в приложении вы совершаете на свой страх и риск.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
        )
        Text(
            text = "Важно: для работы приложения должен быть доступен ресурс YouTube. Если YouTube заблокирован или недоступен в вашей сети, поиск и скачивание работать не будут.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = privacyChecked, onCheckedChange = { privacyChecked = it })
            Text(
                text = "Я ознакомился(лась) с Политикой конфиденциальности",
                modifier = Modifier.weight(1f)
            )
        }
        TextButton(onClick = onOpenPrivacy) {
            Text("Открыть политику конфиденциальности")
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = disclaimerChecked, onCheckedChange = { disclaimerChecked = it })
            Text(
                text = "Я принимаю Отказ от ответственности",
                modifier = Modifier.weight(1f)
            )
        }
        TextButton(onClick = onOpenDisclaimer) {
            Text("Открыть отказ от ответственности")
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onAccept,
            enabled = canContinue,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Принимаю и продолжить")
        }

        Text(
            text = "Без согласия функции приложения недоступны.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
    }
}
