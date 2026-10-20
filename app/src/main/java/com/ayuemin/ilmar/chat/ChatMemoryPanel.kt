package com.ayuemin.ilmar.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ayuemin.ilmar.memory.MemorySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ChatMemoryPanel(chat: Long, controller: ChatViewModel, busy: Boolean, onDismiss: () -> Unit) {
    val chatState by controller.state.collectAsState()
    LaunchedEffect(chat) { controller.indexArchive(chat) }
    var revision by remember { mutableIntStateOf(0) }
    val snapshot by produceState<MemorySnapshot?>(null, chat, revision, busy) { value = withContext(Dispatchers.IO) { controller.memorySnapshot(chat) } }
    var rules by remember(chat) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember(chat) { mutableStateOf("") }
    var results by remember(chat) { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun mutate(action: () -> Unit) {
        working = true
        scope.launch { try { withContext(Dispatchers.IO) { action() }; revision++; error = null }
            catch (_: Exception) { error = "Не удалось изменить рабочую память" } finally { working = false } }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Память и контекст чата") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Сводки не заменяют исходную переписку. Compact использует выбранную модель и оплачивается отдельно; стоимость видна в статистике.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(rules ?: snapshot?.rules.orEmpty(), { rules = it }, enabled = !busy && !working,
                label = { Text("Неизменяемые правила и решения") }, minLines = 3)
            Text("Правила передаются дословно и не переписываются моделью при сжатии.", style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !busy && !working && snapshot != null, onClick = { val text = rules ?: snapshot!!.rules; mutate { controller.setRules(chat, text) } }) { Text("Сохранить правила") }
            Button(enabled = !busy && !working, onClick = { controller.compact(chat) }) { Text("Сжать сейчас / повторно") }
            snapshot?.checkpoint?.let { raw ->
                val checkpoint = remember(raw) { org.json.JSONObject(raw) }
                Text("Контрольная точка: ${checkpoint.optInt("next")} из ${checkpoint.optInt("total")} участков. Явное повторение продолжит с сохранённого места, если исходники и настройки не изменились.", style = MaterialTheme.typography.bodySmall)
            }
            Text("Карточка состояния", style = MaterialTheme.typography.titleMedium)
            Text("Цель, прогресс и следующий шаг — в активной сводке ниже. Утверждённые решения закрепляй в правилах отдельно. Исходные сообщения сохраняются полностью.", style = MaterialTheme.typography.bodySmall)
            chatState.archiveStatus[chat]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row {
                TextButton(onClick = { controller.indexArchive(chat) }) { Text("Обновить индекс") }
                TextButton(onClick = { controller.stopArchive(chat) }) { Text("Остановить индекс") }
            }
            val embeddingUsage = controller.embeddingUsage(chat)
            val embeddingTokens = if (embeddingUsage.isNull("input_tokens")) "неизвестно" else embeddingUsage.optString("input_tokens")
            val embeddingCost = if (embeddingUsage.isNull("known_cost_usd")) "неизвестна" else embeddingUsage.optString("known_cost_usd") + " USD"
            Text("Эмбеддинги: запросов ${embeddingUsage.optInt("calls")}, входных токенов $embeddingTokens; известная стоимость $embeddingCost${if (embeddingUsage.optBoolean("incomplete_cost")) " · часть расходов неизвестна" else ""}", style = MaterialTheme.typography.bodySmall)
            if (snapshot?.capsules.isNullOrEmpty()) Text("Карточка ещё не создана: Compact сработает по мере заполнения окна или вручную.")
            Text("Версии сводки (последние 20)", style = MaterialTheme.typography.labelLarge)
            snapshot?.capsules.orEmpty().forEach { capsule ->
                Text("#${capsule.id}${if (capsule.active) " · активна" else ""} · ≈ ${capsule.before} → ${capsule.after} токенов")
                val readable = remember(capsule.text) {
                    val json = org.json.JSONObject(capsule.text)
                    listOf("goal" to "Цель", "plan" to "План", "facts" to "Факты", "decisions" to "Решения", "progress" to "Прогресс", "files" to "Файлы и версии",
                        "tests" to "Проверки", "errors" to "Ошибки", "next" to "Следующий шаг", "uncertain" to "Неопределённость").mapNotNull { (key, title) ->
                        val values = json.optJSONArray(key)
                        if (values == null || values.length() == 0) null else title + ": " + (0 until values.length()).joinToString("; ") { values.getString(it) }
                    }.joinToString("\n")
                }
                Text(readable, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(enabled = !busy && !working && !capsule.active, onClick = { mutate { controller.activateCapsule(chat, capsule.id) } }) { Text("Использовать") }
                    TextButton(enabled = !busy && !working, onClick = { mutate { controller.deleteCapsule(chat, capsule.id) } }) { Text("Удалить сводку") }
                }
            }
            OutlinedTextField(query, { query = it }, label = { Text("Поиск в полной истории") })
            TextButton(enabled = query.isNotBlank() && !working, onClick = { working = true; scope.launch {
                try { results = withContext(Dispatchers.IO) { controller.memorySearch(chat, query) }.ifBlank { "Совпадений нет" } }
                catch (_: Exception) { error = "Не удалось выполнить поиск" } finally { working = false }
            } }) { Text("Найти на устройстве") }
            TextButton(enabled = query.isNotBlank() && !working, onClick = { working = true; scope.launch {
                try { results = controller.semanticSearch(chat, query).ifBlank { "Семантических совпадений нет: проверь настройки RAG и готовность индекса. Локальный поиск доступен отдельно." } }
                catch (_: Exception) { error = "Не удалось выполнить поиск по смыслу" } finally { working = false }
            } }) { Text("Найти по смыслу (API)") }
            if (results.isNotBlank()) androidx.compose.foundation.text.selection.SelectionContainer { Text(results, style = MaterialTheme.typography.bodySmall) }
            if (working || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}
