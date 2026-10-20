package com.ayuemin.ilmar.chat

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
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

/** Umnik actions/toggle/tools layout, using only Ilmar's real installed capabilities. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatModeMenu(id: Long, preferences: ChatModePreferences,
    onAttachText: () -> Unit, onDictate: () -> Unit, onDismiss: () -> Unit) {
    var toolsExpanded by remember { mutableStateOf(false) }
    val tools = ToolRegistry.catalogue()
    val available = ToolRegistry.availableIds()
    var chosen by remember(id) { mutableStateOf(available.filter { preferences.enabled(id, "tool_$it") }.toSet()) }
    var applied by remember(id) { mutableStateOf(chosen) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp).padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ComposerActionTile(Icons.Outlined.AttachFile, "Вставить", true, Modifier.weight(1f)) { onDismiss(); onAttachText() }
                ComposerActionTile(Icons.Outlined.CameraAlt, "Камера", false, Modifier.weight(1f)) {}
                ComposerActionTile(Icons.Outlined.Image, "Создать", false, Modifier.weight(1f)) {}
            }
            Text("Вставить: текстовый файл. Камера и генерация изображений пока не подключены.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ComposerToggleTile(Icons.Outlined.Psychology, 0, 3, levelDescription = "Размышление пока не подключено",
                    checked = false, enabled = false, modifier = Modifier.weight(1f), onCheckedChange = {})
                ComposerToggleTile(Icons.Outlined.Language, 0, 4, levelDescription = "Интернет пока не подключён",
                    checked = false, enabled = false, modifier = Modifier.weight(1f), onCheckedChange = {})
                ComposerToggleTile(Icons.Outlined.SmartToy, 0, 5, indicatorText = "АГЕНТ", levelDescription = "Агент пока не подключён",
                    checked = false, enabled = false, modifier = Modifier.weight(1f), onCheckedChange = {})
            }
            Text("Размышление, интернет и агент станут доступны после подключения соответствующих возможностей.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ComposerSectionHeader(Icons.Outlined.Storage, "Инструменты", toolsExpanded) { toolsExpanded = !toolsExpanded }
            if (toolsExpanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactComposerTool(Icons.Outlined.Mic, "В текст", true, Modifier.weight(1f)) { onDismiss(); onDictate() }
                    CompactComposerTool(Icons.Outlined.Storage, "Shell", "Shell" in available, Modifier.weight(1f)) {}
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = available.isNotEmpty() && chosen.containsAll(available), enabled = available.isNotEmpty(),
                        onCheckedChange = { chosen = if (it) available else emptySet() })
                    Text("Выбрать всё")
                }
                tools.forEach { tool ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = tool.id in chosen, enabled = tool.id in available,
                            onCheckedChange = { chosen = if (it) chosen + tool.id else chosen - tool.id })
                        Text(tool.title, Modifier.weight(1f))
                        if (tool.id !in available) Text("Не подключён", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Button(enabled = chosen.isNotEmpty(), onClick = {
                    tools.forEach { preferences.setEnabled(id, "tool_${it.id}", it.id in chosen) }; applied = chosen
                }) { Text("Включить") }
                OutlinedButton(enabled = applied.isNotEmpty(), onClick = {
                    tools.forEach { preferences.setEnabled(id, "tool_${it.id}", false) }; applied = emptySet(); chosen = emptySet()
                }) { Text("Выключить все") }
            }
        }
    }
}
