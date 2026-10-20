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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuemin.ilmar.model.ModelCatalogViewModel
import com.ayuemin.ilmar.ui.ModelCatalogBrowser
import com.ayuemin.ilmar.model.ChatEndpoint
import com.ayuemin.ilmar.model.ProviderConnection
import com.ayuemin.ilmar.model.ProviderRegistry

@Composable
fun SettingsScreen(onBack: () -> Unit, initialPage: String? = null) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ilmar_ui", android.content.Context.MODE_PRIVATE) }
    val registry = remember { ProviderRegistry(context) }
    val catalog: ModelCatalogViewModel = viewModel()
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
        editorOpen = false; revision++; catalog.changed()
    })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Очистить диагностику?") },
        confirmButton = { TextButton(onClick = { diagnostics.clear(); events = ""; confirmClear = false }) { Text("Очистить") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } })
    if (page == "Модели") {
        Column(Modifier.fillMaxSize().imePadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { page = null }) { Icon(Icons.Default.ArrowBack, "Назад") }
                Text("Модели", style = MaterialTheme.typography.titleLarge)
            }
            Text("Выбери модель по умолчанию или добавь её в избранное.", style = MaterialTheme.typography.bodySmall)
            val catalogState by catalog.state.collectAsState()
            ModelCatalogBrowser(catalog, remember(catalogState.revision) { catalog.registry.snapshot().defaultModelId },
                onChoose = { catalog.registry.setDefault(it); catalog.changed(); revision++ },
                onConnections = { page = "Подключения" }, modifier = Modifier.weight(1f))
        }
        return
    }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (page == null) onBack() else page = null }) { Icon(Icons.Default.ArrowBack, "Назад") }
            Text(page ?: "Настройки", style = MaterialTheme.typography.titleLarge)
        }
        when (page) {
            null -> {
                SettingsCard("Подключения", "Провайдеры, адреса API и ключи") { page = "Подключения" }
                SettingsCard("Модели", "Каталоги провайдеров, избранное и модель по умолчанию") { page = "Модели" }
                SettingsCard("Озвучка", "Системный голос или модель провайдера") { page = "Озвучка" }
                SettingsCard("Интерфейс", "Компактность сообщений") { page = "Интерфейс" }
                SettingsCard("Память и контекст", "История, Compact, кэш и бюджеты") { page = "Память и контекст" }
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
            "Озвучка" -> com.ayuemin.ilmar.speech.SpeechSettings()
            "Интерфейс" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Компактные сообщения", Modifier.weight(1f))
                Switch(compact, { compact = it; prefs.edit().putBoolean("compact_messages", it).apply() })
            }
            "Память и контекст" -> com.ayuemin.ilmar.memory.ContextSettings(onConnections = { page = "Подключения" })
            "Инструменты" -> {
                com.ayuemin.ilmar.tools.ToolSettings()
                val broker = remember { com.ayuemin.ilmar.agent.ToolPermissionBroker(context) }
                val tools = com.ayuemin.ilmar.chat.ToolRegistry.catalogue().filter { it.executorRegistered }
                if (tools.isEmpty()) Text("Исполнители пока не установлены. SSH, Shell и браузер недоступны.")
                tools.forEach { tool ->
                    var granted by remember(tool.id) { mutableStateOf(broker.granted(tool.id)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tool.title, Modifier.weight(1f))
                        Switch(granted, { granted = it; broker.setGranted(tool.id, it) })
                    }
                }
            }
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
                OutlinedTextField(key, { key = it }, label = { Text(if (keyPresent) "Новый ключ (необязательно)" else "API-ключ") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true)
                if (keyPresent) TextButton(onClick = { confirmKeyRemoval = true }) { Text("Удалить сохранённый ключ") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = {
            try { registry.saveProvider(connection?.id, name, endpoint, key); key = ""; onSaved() }
            catch (_: java.security.GeneralSecurityException) { error = "Не удалось сохранить ключ в защищённом хранилище" }
            catch (e: IllegalArgumentException) { error = e.message }
            catch (_: Exception) { error = "Не удалось сохранить подключение" }
        }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}
