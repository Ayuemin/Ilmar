package com.ayuemin.ilmar.chat

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.KeyboardType

@Composable
internal fun ChatProviderDialog(
    chatId: Long,
    modes: ChatModePreferences,
    global: SharedPreferences,
    onDismiss: () -> Unit
) {
    var endpoint by remember(chatId) {
        mutableStateOf(modes.choice(chatId, "api_endpoint").takeUnless { it == "Автоматически" }
            ?: global.getString("api_endpoint", "https://api.openai.com/v1/chat/completions").orEmpty())
    }
    var model by remember(chatId) {
        mutableStateOf(modes.choice(chatId, "api_model").takeUnless { it == "Автоматически" }
            ?: global.getString("api_model", "gpt-4o-mini").orEmpty())
    }
    val valid = endpoint.startsWith("https://", ignoreCase = true) && model.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Провайдер и модель чата") },
        text = {
            Column {
                Text("Параметры только текущего чата. API-ключ используется из общих защищённых настроек.")
                OutlinedTextField(value = endpoint, onValueChange = { endpoint = it },
                    label = { Text("HTTPS endpoint") }, singleLine = true)
                OutlinedTextField(value = model, onValueChange = { model = it },
                    label = { Text("Модель") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                modes.setChoice(chatId, "api_endpoint", endpoint.trim())
                modes.setChoice(chatId, "api_model", model.trim())
                onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = {
                modes.setChoice(chatId, "api_endpoint", "Автоматически")
                modes.setChoice(chatId, "api_model", "Автоматически")
                onDismiss()
            }) { Text("Общие настройки") }
        }
    )
}
