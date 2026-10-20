package com.ayuemin.ilmar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import com.ayuemin.ilmar.ui.IlmarPanelShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("ilmar_ui", android.content.Context.MODE_PRIVATE) }
    var compactMessages by remember { mutableStateOf(prefs.getBoolean("compact_messages", false)) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        TextButton(onClick = onBack) { Text("← Назад") }
        Spacer(Modifier.height(12.dp))
        Text("Настройки", style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface)
        Text("Ilmar · управление приложением",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(28.dp))
        Text("ПОДКЛЮЧЕНИЯ И ДАННЫЕ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        SettingsSection("Модель и провайдер",
            "Подключение API ещё не реализовано. Выбор модели станет доступен после настройки безопасного хранения ключей.")
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = IlmarPanelShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Компактные сообщения", style = MaterialTheme.typography.titleMedium)
                    Text("Уменьшить отступы в переписке",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = compactMessages, onCheckedChange = {
                    compactMessages = it
                    prefs.edit().putBoolean("compact_messages", it).apply()
                })
            }
        }
        SettingsSection("Чаты и память",
            "История хранится на устройстве в SQLite. Удаление и экспорт пока недоступны.")
        SettingsSection("Инструменты и разрешения",
            "Управление инструментами появится в следующих версиях.")
        SettingsSection("О приложении", "Ilmar · 0.1.0-dev")
    }
}

@Composable
private fun SettingsSection(title: String, description: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = IlmarPanelShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFBFC0CD))
        }
    }
}
