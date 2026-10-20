package com.ayuemin.ilmar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import com.ayuemin.ilmar.ui.IlmarPanelShape
import com.ayuemin.ilmar.security.CredentialStore
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
    val credentials = remember(context) { CredentialStore(context) }
    var endpoint by remember { mutableStateOf(prefs.getString("api_endpoint", "https://api.openai.com/v1/chat/completions") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("api_model", "gpt-4o-mini") ?: "") }
    var apiKeyDraft by remember { mutableStateOf("") }
    var keySaved by remember { mutableStateOf(credentials.hasApiKey()) }
    var compactMessages by remember { mutableStateOf(prefs.getBoolean("compact_messages", false)) }
    var expanded by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←") }
            Text("Настройки", style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(24.dp))
        SettingsCategory("Подключения", "Произвольный HTTPS API, модель и ключ", "◎",
            expanded == "Подключения", { expanded = if (expanded == "Подключения") null else "Подключения" }) {
        OutlinedTextField(value = endpoint, onValueChange = { endpoint = it },
            label = { Text("Адрес chat/completions (HTTPS)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = model, onValueChange = { model = it },
            label = { Text("ID модели") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = endpoint.startsWith("https://") && model.isNotBlank(), onClick = {
            prefs.edit().putString("api_endpoint", endpoint.trim())
                .putString("api_model", model.trim()).apply()
        }) { Text("Сохранить параметры модели") }
        OutlinedTextField(
            value = apiKeyDraft, onValueChange = { apiKeyDraft = it },
            label = { Text("API-ключ провайдера") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text(if (keySaved) "Ключ сохранён в защищённом хранилище"
            else "Ключ пока не сохранён",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = apiKeyDraft.isNotBlank(), onClick = {
                credentials.saveApiKey(apiKeyDraft.trim())
                apiKeyDraft = ""
                keySaved = credentials.hasApiKey()
            }) { Text("Сохранить") }
            OutlinedButton(enabled = keySaved, onClick = {
                credentials.clearApiKey()
                apiKeyDraft = ""
                keySaved = false
            }) { Text("Удалить ключ") }
        }
        }
        SettingsCategory("Модели", "Выбор модели и параметры генерации", "◈",
            expanded == "Модели", { expanded = if (expanded == "Модели") null else "Модели" }) {
            Text("Модель и адрес API задаются в разделе «Подключения».",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsCategory("Память и контекст", "История чатов и контекст", "◷",
            expanded == "Память и контекст", { expanded = if (expanded == "Память и контекст") null else "Память и контекст" }) {
            Text("Чаты хранятся локально в SQLite. Управление памятью появится позже.")
        }
        SettingsCategory("Инструменты", "Доступные инструменты и разрешения", "⚒",
            expanded == "Инструменты", { expanded = if (expanded == "Инструменты") null else "Инструменты" }) {
            Text("Подключение инструментов находится в разработке.")
        }
        SettingsCategory("Интерфейс", "Внешний вид и сообщения", "◉",
            expanded == "Интерфейс", { expanded = if (expanded == "Интерфейс") null else "Интерфейс" }) {
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
        }
        SettingsCategory("Диагностика", "Версия и состояние приложения", "⚙",
            expanded == "Диагностика", { expanded = if (expanded == "Диагностика") null else "Диагностика" }) {
            Text("Ilmar · 0.1.0-dev")
        }
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
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsCategory(
    title: String, subtitle: String, symbol: String,
    expanded: Boolean, onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = IlmarPanelShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
    ) {
        Column {
            Surface(onClick = onClick, color = Color.Transparent, shape = IlmarPanelShape) {
                Row(Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Surface(shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.size(48.dp)) {
                        Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Text(symbol, color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text(if (expanded) "⌄" else "›",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.headlineSmall)
                }
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    content()
                }
            }
        }
    }
}
