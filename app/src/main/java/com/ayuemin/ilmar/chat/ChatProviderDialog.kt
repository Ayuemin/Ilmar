package com.ayuemin.ilmar.chat

import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Quick selection only. Connection details and credentials are configured globally.
 * Until the multi-provider registry is implemented, only the saved global model is available.
 */
@Composable
internal fun ChatProviderDialog(
    chatId: Long,
    modes: ChatModePreferences,
    global: SharedPreferences,
    onDismiss: () -> Unit
) {
    val configuredEndpoint = global.getString("api_endpoint", "").orEmpty()
    val configuredModel = global.getString("api_model", "").orEmpty()
    val configured = configuredEndpoint.startsWith("https://", ignoreCase = true) &&
        configuredModel.isNotBlank()
    val currentModel = modes.choice(chatId, "api_model")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выбрать модель") },
        text = {
            Column {
                if (!configured) {
                    Text("Нет настроенных моделей. Добавь подключение в общих настройках приложения.")
                } else {
                    Text("Настроенные модели", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                modes.setChoice(chatId, "api_endpoint", configuredEndpoint)
                                modes.setChoice(chatId, "api_model", configuredModel)
                                onDismiss()
                            }.padding(16.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(configuredModel, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    configuredEndpoint.substringAfter("://").substringBefore("/"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (currentModel == configuredModel || currentModel == "Автоматически") {
                                Text("✓")
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Пока доступна одна глобальная конфигурация. Реестр провайдеров и моделей появится в следующем этапе.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        dismissButton = {
            TextButton(onClick = {
                modes.setChoice(chatId, "api_endpoint", "Автоматически")
                modes.setChoice(chatId, "api_model", "Автоматически")
                onDismiss()
            }) { Text("По умолчанию") }
        }
    )
}
