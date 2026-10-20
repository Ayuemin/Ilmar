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
            snapshot?.checkpoint?.let { Text("Контрольная точка Compact:\n$it", style = MaterialTheme.typography.bodySmall) }
            Text("Версии сводки (последние 20)", style = MaterialTheme.typography.labelLarge)
            snapshot?.capsules.orEmpty().forEach { capsule ->
                Text("#${capsule.id}${if (capsule.active) " · активна" else ""} · ≈ ${capsule.before} → ${capsule.after} токенов")
                Text(capsule.text, style = MaterialTheme.typography.bodySmall)
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
            if (results.isNotBlank()) Text(results, style = MaterialTheme.typography.bodySmall)
            if (working || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}
