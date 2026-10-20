package com.ayuemin.ilmar.memory

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun ContextSettings() {
    val context = LocalContext.current
    val preferences = remember { ContextPreferences(context) }
    val initial = remember { preferences.load() }
    var window by remember { mutableStateOf(initial.window.toString()) }
    var output by remember { mutableStateOf(initial.output.toString()) }
    var recent by remember { mutableStateOf(initial.recent.toString()) }
    var tokens by remember { mutableStateOf(initial.taskTokens.toString()) }
    var usd by remember { mutableStateOf(initial.taskUsd.orEmpty()) }
    var automatic by remember { mutableStateOf(initial.automatic) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Полная история остаётся на устройстве. В модель передаются отдельная сводка, неизменяемые правила, последние сообщения и найденные фрагменты истории.")
        Row { Text("Автоматический Compact при 80%", Modifier.weight(1f)); Switch(automatic, { automatic = it }) }
        OutlinedTextField(window, { window = it }, label = { Text("Локальный предел контекста, токены") }, singleLine = true)
        Text("Предел ограничивается реальным окном из каталога. Если каталог его не сообщил, используется это значение. После обновления APK обнови каталог.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(output, { output = it }, label = { Text("Резерв и максимальный ответ, токены") }, singleLine = true)
        Text("Лимит вывода отправляется только при поддержке API. В остальных случаях резерв остаётся оценкой.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(recent, { recent = it }, label = { Text("Последние сообщения дословно, 0–20") }, singleLine = true)
        OutlinedTextField(tokens, { tokens = it }, label = { Text("Бюджет токенов задачи, включая Compact") }, singleLine = true)
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
    }
}
