package com.ayuemin.ilmar.memory

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ayuemin.ilmar.model.ProviderRegistry

@Composable
internal fun EmbeddingSettings() {
    val context = LocalContext.current
    val preferences = remember { EmbeddingPreferences(context) }
    val initial = remember { preferences.load() }
    val registry = remember { ProviderRegistry(context) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var provider by remember { mutableStateOf(initial.provider) }
    var model by remember { mutableStateOf(initial.model) }
    var endpoint by remember { mutableStateOf(initial.endpoint) }
    var message by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Поиск по смыслу в архиве (RAG)", style = MaterialTheme.typography.titleMedium)
        Text("Полные сообщения остаются локально. Дополнительный индекс ищет близкие по смыслу фрагменты. Без него работает SQLite FTS. Индекс и поиск по нему не заменяют карточку состояния и последние сообщения.", style = MaterialTheme.typography.bodySmall)
        Row { Text("API-эмбеддинги", Modifier.weight(1f)); Switch(enabled, { enabled = it }) }
        Text("При включении текст старых сообщений и вложений передаётся выбранному провайдеру для эмбеддингов; запрос поиска тоже передаётся API. Возможны отдельные расходы, не входящие в бюджет генерации. Можно оставить выключенным.", style = MaterialTheme.typography.bodySmall)
        registry.snapshot().providers.forEach { connection ->
            Row { RadioButton(provider == connection.id, { provider = connection.id }); TextButton(onClick = { provider = connection.id }) { Text(connection.name) } }
        }
        if (registry.snapshot().providers.isEmpty()) Text("Сначала добавь API-подключение в настройках.")
        OutlinedTextField(model, { model = it }, label = { Text("ID модели эмбеддингов") }, singleLine = true)
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Полный HTTPS URL /embeddings; пусто — автоматически") })
        Text("Нужен OpenAI-совместимый API эмбеддингов, например text-embedding-3-small. Ключ берётся из выбранного подключения. Индексация архива запускается в фоне при открытии чата и после ответа; прогресс виден в карточке памяти. Изменение модели создаёт отдельный индекс.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            try {
                val config = EmbeddingConfig(enabled, provider, model.trim(), endpoint.trim())
                if (enabled) config.endpoint(registry.snapshot().providers.first { it.id == provider }.endpoint)
                preferences.save(config); message = "Настройки поиска сохранены. Открой нужный чат для индексации."
            } catch (e: Exception) { message = e.message ?: "Проверь настройки эмбеддингов" }
        }) { Text("Сохранить настройки RAG") }
        if (message.isNotBlank()) Text(message)
    }
}
