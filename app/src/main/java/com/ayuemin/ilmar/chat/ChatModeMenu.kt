package com.ayuemin.ilmar.chat

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal class ChatModePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("ilmar_chat_modes", Context.MODE_PRIVATE)
    fun enabled(id: Long, mode: String) = prefs.getBoolean("${id}_$mode", false)
    fun setEnabled(id: Long, mode: String, enabled: Boolean) { prefs.edit().putBoolean("${id}_$mode", enabled).apply() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatModeMenu(id: Long, preferences: ChatModePreferences, onDismiss: () -> Unit) {
    var expanded by remember { mutableStateOf<String?>(null) }
    val tools = ToolRegistry.catalogue()
    val available = ToolRegistry.availableIds().toSet()
    var applied by remember(id) { mutableStateOf(available.filter { preferences.enabled(id, "tool_$it") }.toSet()) }
    var chosen by remember(id) { mutableStateOf(applied) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(horizontal = 20.dp)) {
            Text("Режимы чата", style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(listOf("Агент", "Поиск", "Размышление")) { name ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { expanded = if (expanded == name) null else name }, modifier = Modifier.weight(1f)) { Text(name) }
                        Switch(checked = false, onCheckedChange = null, enabled = false)
                    }
                    if (expanded == name) Text(when (name) {
                        "Агент" -> "Сейчас доступен чат. Режим агента появится после подключения исполнителей и проверки разрешений."
                        "Поиск" -> "Поиск не подключён."
                        else -> "Параметры размышления пока не поддерживаются адаптером."
                    }, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                }
                item {
                    TextButton(onClick = { expanded = if (expanded == "Инструменты") null else "Инструменты" }) { Text("Инструменты") }
                }
                if (expanded == "Инструменты") {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = available.isNotEmpty() && chosen.containsAll(available), enabled = available.isNotEmpty(),
                                onCheckedChange = { chosen = if (it) available else emptySet() })
                            Text("Выбрать всё")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = chosen.isNotEmpty() && chosen.all { it in available }, onClick = {
                                tools.forEach { preferences.setEnabled(id, "tool_${it.id}", it.id in chosen) }; applied = chosen
                            }) { Text("Включить") }
                            OutlinedButton(enabled = applied.isNotEmpty(), onClick = {
                                tools.forEach { preferences.setEnabled(id, "tool_${it.id}", false) }; applied = emptySet(); chosen = emptySet()
                            }) { Text("Выключить все") }
                        }
                    }
                    items(tools, key = { it.id }) { tool ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = tool.id in chosen, enabled = tool.id in available, onCheckedChange = {
                                chosen = if (it) chosen + tool.id else chosen - tool.id
                            })
                            TextButton(onClick = { expanded = tool.id }) { Text(tool.id) }
                            if (tool.id !in available) Text("Не подключён", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else if (expanded in tools.map { it.id }) {
                    item { Text("Настройка и исполнитель пока отсутствуют. Подключения будут управляться в общих настройках.") }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
