package com.ayuemin.ilmar.memory

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextSettings(onConnections: () -> Unit = {}) {
    val context = LocalContext.current
    val preferences = remember { ContextPreferences(context) }
    val initial = remember { preferences.load() }
    val catalog: com.ayuemin.ilmar.model.ModelCatalogViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var compactModel by remember { mutableStateOf(preferences.compactModelId) }
    var chooseCompact by remember { mutableStateOf(false) }
    if (chooseCompact) ModalBottomSheet(onDismissRequest = { chooseCompact = false }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(16.dp)) {
            Text("Провайдер и модель сокращения контекста", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { preferences.compactModelId = null; compactModel = null; chooseCompact = false }) { Text("Использовать модель текущего чата") }
            com.ayuemin.ilmar.ui.ModelCatalogBrowser(catalog, compactModel,
                onChoose = { preferences.compactModelId = it; compactModel = it; chooseCompact = false },
                onConnections = { chooseCompact = false; onConnections() }, modifier = Modifier.weight(1f))
        }
    }
    var window by remember { mutableStateOf(initial.window.toString()) }
    var output by remember { mutableStateOf(initial.output.toString()) }
    var recent by remember { mutableStateOf(initial.recent.toString()) }
    var tokens by remember { mutableStateOf(initial.taskTokens.toString()) }
    var usd by remember { mutableStateOf(initial.taskUsd.orEmpty()) }
    var automatic by remember { mutableStateOf(initial.automatic) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Полная история остаётся на устройстве. В модель передаются отдельная сводка, неизменяемые правила, последние сообщения и найденные фрагменты истории.")
        val data = catalog.registry.snapshot()
        val selected = data.models.find { it.id == compactModel }
        Text("Модель сокращения контекста", style = MaterialTheme.typography.titleMedium)
        Text(if (compactModel == null) "Модель текущего чата" else selected?.let { model ->
            "${data.providers.find { it.id == model.providerId }?.name ?: "Недоступный провайдер"} · ${model.name}"
        } ?: "Выбранная модель недоступна. Выбери другую.")
        TextButton(onClick = { chooseCompact = true }) { Text("Выбрать провайдера и модель Compact") }
        Text("Отдельная модель используется только для сжатия. Её API-ключ берётся из подключения; расходы входят в общий бюджет задачи.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { window = "0"; output = "0"; tokens = "0"; automatic = true; message = "Автоматический режим подготовлен. Нажми «Сохранить»." }) { Text("Непрерывный контекст: автоматические настройки") }
        TextButton(onClick = { window = "8192"; output = "1024"; automatic = true; message = "Подготовлено рабочее окно 8192 и вывод до 1024 токенов. Проверь и сохрани; денежный бюджет и модель Compact не меняются." }) { Text("Подготовить экономичный контекст") }
        Row { Text("Циклический Compact при 80%", Modifier.weight(1f)); Switch(automatic, { automatic = it }) }
        OutlinedTextField(window, { window = it }, label = { Text("Контекст: 0 — авто, до 32768 токенов") }, singleLine = true)
        Text("Автоматическое рабочее окно — до 32768 токенов, даже если модель поддерживает больше: это уменьшает повторную отправку длинной истории. Можно явно выбрать большее окно в пределах API. Оригиналы остаются локально.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(output, { output = it }, label = { Text("Резерв и предел ответа; 0 — автоматически") }, singleLine = true)
        Text("Лимит вывода отправляется только при поддержке API. В остальных случаях резерв остаётся оценкой.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(recent, { recent = it }, label = { Text("Предпочтительно последних сообщений дословно") }, singleLine = true)
        OutlinedTextField(tokens, { tokens = it }, label = { Text("Бюджет токенов задачи; 0 — без лимита") }, singleLine = true)
        OutlinedTextField(usd, { usd = it }, label = { Text("Локальный бюджет USD; пусто — без лимита") }, singleLine = true)
        Text("Перед запросом проверяется оценка, после — фактический usage. Для USD нужны тарифы и ограничение вывода; изображения без денежной оценки блокируются. Это локальная защита по тарифу каталога, не гарантия счёта провайдера. Для жёсткого денежного лимита установи квоту у провайдера.", style = MaterialTheme.typography.bodySmall)
        Text("Стабильный префикс сохраняется для автоматического caching OpenAI. Явный ephemeral cache для OpenRouter/Anthropic включается только при известных выгодных тарифах записи/чтения; стандартный TTL — 5 минут. Попадание в кэш подтверждается только cached_tokens.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            try {
                preferences.save(ContextPolicy(window.toInt(), output.toInt(), recent.toInt(), tokens.toInt(), usd.trim().takeIf { it.isNotEmpty() }, automatic))
                message = "Настройки сохранены"
            } catch (e: Exception) { message = if (e is NumberFormatException) "Введи целые числа для токенов и количества сообщений" else e.message ?: "Проверь настройки" }
        }) { Text("Сохранить") }
        message?.let { Text(it) }
        EmbeddingSettings()
    }
}
