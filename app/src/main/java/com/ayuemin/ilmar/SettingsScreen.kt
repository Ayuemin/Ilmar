package com.ayuemin.ilmar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ayuemin.ilmar.diagnostics.Diagnostics
import com.ayuemin.ilmar.model.ChatEndpoint
import com.ayuemin.ilmar.model.ProviderConnection
import com.ayuemin.ilmar.model.ProviderRegistry

@Composable
fun SettingsScreen(onBack: () -> Unit, initialPage: String? = null) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ilmar_ui", android.content.Context.MODE_PRIVATE) }
    val registry = remember { ProviderRegistry(context) }
    var revision by remember { mutableIntStateOf(0) }
    val data = remember(revision) { registry.snapshot() }
    var page by remember(initialPage) { mutableStateOf(initialPage) }
    var editing by remember { mutableStateOf<ProviderConnection?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var compact by remember { mutableStateOf(prefs.getBoolean("compact_messages", false)) }
    val diagnostics = remember { Diagnostics(context) }
    var events by remember { mutableStateOf(diagnostics.read()) }
    var confirmClear by remember { mutableStateOf(false) }
    if (page != null) BackHandler { page = null }
    if (editorOpen) ConnectionEditor(editing, registry, onDismiss = { editorOpen = false }, onSaved = {
        editorOpen = false; revision++
    })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Очистить диагностику?") },
        confirmButton = { TextButton(onClick = { diagnostics.clear(); events = ""; confirmClear = false }) { Text("Очистить") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } })
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (page == null) onBack() else page = null }) { Icon(Icons.Default.ArrowBack, "Назад") }
            Text(page ?: "Настройки", style = MaterialTheme.typography.titleLarge)
        }
        when (page) {
            null -> {
                SettingsCard("Подключения", "API-сервисы, ключи и зарегистрированные модели") { page = "Подключения" }
                SettingsCard("Модель по умолчанию", data.models.find { it.id == data.defaultModelId }?.name ?: "Не выбрана") { page = "Модель по умолчанию" }
                SettingsCard("Интерфейс", "Компактность сообщений") { page = "Интерфейс" }
                SettingsCard("Память и контекст", "Локальная история") { page = "Память и контекст" }
                SettingsCard("Инструменты", "Состояние подключений") { page = "Инструменты" }
                SettingsCard("Диагностика", "Версия и события запросов") { events = diagnostics.read(); page = "Диагностика" }
            }
            "Подключения" -> {
                data.providers.forEach { connection ->
                    SettingsCard(connection.name, "${data.models.count { it.providerId == connection.id }} моделей · " +
                        if (registry.hasKey(connection.id)) "ключ сохранён" else "нужен ключ") {
                        editing = connection; editorOpen = true
                    }
                }
                Button(onClick = { editing = null; editorOpen = true }) { Text("Добавить подключение") }
            }
            "Модель по умолчанию" -> {
                if (data.models.isEmpty()) Text("Сначала добавь подключение и модели.")
                data.models.forEach { model ->
                    val ready = registry.hasKey(model.providerId)
                    Surface(onClick = { registry.setDefault(model.id); revision++ }, enabled = ready,
                        color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = model.id == data.defaultModelId, enabled = ready, onClick = { registry.setDefault(model.id); revision++ })
                            Column {
                                Text(model.name)
                                Text(data.providers.find { it.id == model.providerId }?.name.orEmpty() + if (ready) "" else " · нужен ключ",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            "Интерфейс" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Компактные сообщения", Modifier.weight(1f))
                Switch(compact, { compact = it; prefs.edit().putBoolean("compact_messages", it).apply() })
            }
            "Память и контекст" -> Text("История хранится на устройстве. Очистка и удаление доступны в меню чата. Сжатие контекста и резервные копии пока не подключены.")
            "Инструменты" -> Text("Исполнители инструментов пока не установлены. Агент, SSH, Shell и браузер недоступны.")
            "Диагностика" -> {
                Text("Ilmar · ${BuildConfig.VERSION_NAME} · сборка ${BuildConfig.VERSION_CODE}")
                Text("SHA: ${BuildConfig.BUILD_SHA.take(12)}", style = MaterialTheme.typography.bodySmall)
                Text("Журнал содержит только время, код события и ID чата; последние 100 событий.")
                Text(events.ifBlank { "Событий пока нет" }, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = events.isNotBlank(), onClick = { confirmClear = true }) { Text("Очистить журнал") }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConnectionEditor(connection: ProviderConnection?, registry: ProviderRegistry,
    onDismiss: () -> Unit, onSaved: () -> Unit) {
    var name by remember { mutableStateOf(connection?.name.orEmpty()) }
    var endpoint by remember { mutableStateOf(connection?.endpoint.orEmpty()) }
    var models by remember { mutableStateOf(registry.snapshot().models.filter { it.providerId == connection?.id }.joinToString("\n") { it.name }) }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmKeyRemoval by remember { mutableStateOf(false) }
    var keyPresent by remember { mutableStateOf(connection?.let { registry.hasKey(it.id) } ?: false) }
    if (confirmKeyRemoval) AlertDialog(onDismissRequest = { confirmKeyRemoval = false }, title = { Text("Удалить ключ подключения?") },
        text = { Text("Модели этого подключения станут недоступны до добавления ключа.") },
        confirmButton = { TextButton(onClick = {
            connection?.let { registry.clearKey(it.id) }; keyPresent = false; confirmKeyRemoval = false
        }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { confirmKeyRemoval = false }) { Text("Отмена") } })
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (connection == null) "Новое подключение" else "Подключение") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("HTTPS адрес API") })
                Text("Можно указать базовый адрес, например https://openrouter.ai/api/v1, или полный адрес chat/completions.",
                    style = MaterialTheme.typography.bodySmall)
                if (ProviderRegistry.validEndpoint(endpoint)) {
                    Text("Адрес запросов: ${ChatEndpoint.resolve(endpoint)}", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(models, { models = it }, label = { Text("ID моделей — по одному на строку") }, minLines = 2)
                OutlinedTextField(key, { key = it }, label = { Text(if (keyPresent) "Новый ключ (необязательно)" else "API-ключ") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true)
                if (keyPresent) TextButton(onClick = { confirmKeyRemoval = true }) { Text("Удалить сохранённый ключ") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = {
            try { registry.saveConnection(connection?.id, name, endpoint, models.lines(), key); key = ""; onSaved() }
            catch (_: java.security.GeneralSecurityException) { error = "Не удалось сохранить ключ в защищённом хранилище" }
            catch (e: IllegalArgumentException) { error = e.message }
            catch (_: Exception) { error = "Не удалось сохранить подключение" }
        }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}
