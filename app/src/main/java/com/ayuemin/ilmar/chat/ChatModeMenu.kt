package com.ayuemin.ilmar.chat

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal class ChatModePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("ilmar_chat_modes", Context.MODE_PRIVATE)
    fun enabled(id: Long, mode: String) = prefs.getBoolean(id.toString() + "_" + mode, false)
    fun choice(id: Long, name: String): String = prefs.getString(id.toString() + "_" + name, "Автоматически") ?: "Автоматически"
    fun setChoice(id: Long, name: String, value: String) {
        prefs.edit().putString(id.toString() + "_" + name, value).apply()
    }
    fun setEnabled(id: Long, mode: String, enabled: Boolean) {
        prefs.edit().putBoolean(id.toString() + "_" + mode, enabled).apply()
    }
}

@Composable
internal fun ChatModeMenu(id: Long, preferences: ChatModePreferences, onDismiss: () -> Unit) {
    var selected by remember(id) { mutableStateOf("") }
    var depth by remember(id) { mutableStateOf(preferences.choice(id, "depth")) }
    var searchEngine by remember(id) { mutableStateOf(preferences.choice(id, "search_engine")) }
    var toolsExpanded by remember(id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Режимы чата") },
        text = {
            Column(Modifier.heightIn(max = 510.dp).verticalScroll(rememberScrollState())) {
                listOf("Агент", "Поиск", "Размышление").forEach { name ->
                    var checked by remember(id, name) { mutableStateOf(preferences.enabled(id, name)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { selected = name }, modifier = Modifier.weight(1f)) { Text("$name ›") }
                        Switch(checked = checked, onCheckedChange = {
                            checked = it
                            preferences.setEnabled(id, name, it)
                        })
                    }
                if (selected == name) {
                    HorizontalDivider()
                    if (selected == "Размышление") {
                        Text("Предпочтительная глубина (пока не передаётся API)", style = MaterialTheme.typography.labelMedium)
                        listOf("Автоматически", "Низкая", "Средняя", "Высокая").forEach { value ->
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    depth = value; preferences.setChoice(id, "depth", value)
                                }) {
                                RadioButton(selected = depth == value, onClick = {
                                    depth = value; preferences.setChoice(id, "depth", value)
                                })
                                Text(value)
                            }
                        }
                    }
                    if (selected == "Поиск") {
                        Text("Источник поиска (пока не подключён)", style = MaterialTheme.typography.labelMedium)
                        listOf("Автоматически", "Провайдер", "Локальный браузер").forEach { value ->
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    searchEngine = value; preferences.setChoice(id, "search_engine", value)
                                }) {
                                RadioButton(selected = searchEngine == value, onClick = {
                                    searchEngine = value; preferences.setChoice(id, "search_engine", value)
                                })
                                Text(value)
                            }
                        }
                    }
                    Text(when (selected) {
                        "SSH" -> "SSH-серверы настраиваются глобально. Здесь будет выбор сервера для текущего чата."
                        "Shell" -> "Shell ещё не подключён. Разрешения будут управляться глобально."
                        "Браузер" -> "Браузер ещё не подключён. Здесь будет выбор настроенного режима."
                        "Размышление" -> "Глубина зависит от модели. Настройки появятся после проверки API провайдера."
                        "Поиск" -> "Настройки поискового движка появятся после интеграции поиска."
                        else -> "Настройки агента появятся после реализации безопасного исполнения."
                    }, style = MaterialTheme.typography.bodySmall)
                }
                }
                TextButton(onClick = { toolsExpanded = !toolsExpanded; selected = "" }) {
                    Text(if (toolsExpanded) "Инструменты ⌄" else "Инструменты ›")
                }
                if (toolsExpanded) {
                    ToolSelectionPanel(id, preferences, onDetails = { selected = it })
                }

            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

@Composable
private fun ToolSelectionPanel(
    id: Long,
    preferences: ChatModePreferences,
    onDetails: (String) -> Unit
) {
    val tools = ToolRegistry.catalogue().map { it.id }
    val available = ToolRegistry.availableIds()
    var chosen by remember(id) { mutableStateOf(tools.filter { preferences.enabled(id, "tool_" + it) }.toSet()) }
    var applied by remember(id) { mutableStateOf(chosen) }
    val allSelected = tools.isNotEmpty() && tools.all { it in chosen }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = allSelected, onCheckedChange = { value ->
                chosen = if (value) tools.toSet() else emptySet()
            })
            Text("Все", modifier = Modifier.weight(1f))
            Button(
                enabled = applied.isNotEmpty() || (chosen.isNotEmpty() && chosen.all { it in available }),
                onClick = {
                    if (applied.isNotEmpty()) {
                        tools.forEach { preferences.setEnabled(id, "tool_" + it, false) }
                        applied = emptySet()
                        chosen = emptySet()
                    } else {
                        tools.forEach { preferences.setEnabled(id, "tool_" + it, it in chosen) }
                        applied = chosen
                    }
                }
            ) { Text(if (applied.isNotEmpty()) "Выключить" else "Включить") }
        }
        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
        tools.forEach { name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = name in chosen,
                    onCheckedChange = { value ->
                        chosen = if (value) chosen + name else chosen - name
                        if (applied.isNotEmpty() && value && name in available) {
                            preferences.setEnabled(id, "tool_" + name, true)
                            applied = applied + name
                        }
                    }
                )
                OutlinedButton(onClick = { onDetails(name) }, modifier = Modifier.weight(1f)) {
                    Text("$name ›")
                }
                if (name in applied) Text("✓", color = MaterialTheme.colorScheme.primary)
            }
        }
        }
        Text("Доступных к исполнению инструментов: " + available.size + ". Выбор не предоставляет разрешения: их проверяет исполнитель перед каждым действием.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
