package com.ayuemin.ilmar.chat

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal class ChatModePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("ilmar_chat_modes", Context.MODE_PRIVATE)
    fun enabled(id: Long, mode: String) = prefs.getBoolean(id.toString() + "_" + mode, false)
    fun setEnabled(id: Long, mode: String, enabled: Boolean) {
        prefs.edit().putBoolean(id.toString() + "_" + mode, enabled).apply()
    }
}

@Composable
internal fun ChatModeMenu(id: Long, preferences: ChatModePreferences, onDismiss: () -> Unit) {
    var selected by remember(id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Режимы чата") },
        text = {
            Column {
                listOf("Агент", "Поиск", "Размышление").forEach { name ->
                    var checked by remember(id, name) { mutableStateOf(preferences.enabled(id, name)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, modifier = Modifier.weight(1f).clickable { selected = name }.padding(vertical = 12.dp))
                        Switch(checked = checked, onCheckedChange = {
                            checked = it
                            preferences.setEnabled(id, name, it)
                        })
                    }
                }
                TextButton(onClick = { selected = "Инструменты" }) { Text("Инструменты ›") }
                if (selected.isNotEmpty()) {
                    HorizontalDivider()
                    Text(when (selected) {
                        "Инструменты" -> "Инструменты ещё не подключены. Здесь будет выбор доступных для чата ресурсов."
                        "Размышление" -> "Глубина зависит от модели. Настройки появятся после проверки API провайдера."
                        "Поиск" -> "Настройки поискового движка появятся после интеграции поиска."
                        else -> "Настройки агента появятся после реализации безопасного исполнения."
                    }, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}
