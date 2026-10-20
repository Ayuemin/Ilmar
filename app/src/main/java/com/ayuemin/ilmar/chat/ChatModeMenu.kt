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
    fun choice(id: Long, key: String) = prefs.getString("${id}_$key", "auto") ?: "auto"
    fun setChoice(id: Long, key: String, value: String) { prefs.edit().putString("${id}_$key", value).apply() }
    fun setEnabled(id: Long, mode: String, enabled: Boolean) { prefs.edit().putBoolean("${id}_$mode", enabled).apply() }
}

/** Umnik actions/toggle/tools layout, using only Ilmar's real installed capabilities. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatModeMenu(id: Long, preferences: ChatModePreferences,
    onAttachText: () -> Unit, onImage: () -> Unit, onPhoto: () -> Unit, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val registry = remember { com.ayuemin.ilmar.model.ProviderRegistry(context) }
    val model = registry.modelForChat(id)
    val connection = registry.snapshot().providers.find { it.id == model?.providerId }
    val capabilities = model?.let { registry.capabilities(it.id) } ?: com.ayuemin.ilmar.model.ModelCapabilities()
    val canReason = connection?.let { capabilities.supportsReasoning(it.endpoint) } == true
    var reasoning by remember(id, model?.id) { mutableStateOf(preferences.enabled(id, "Размышление") && canReason) }
    var effort by remember(id, model?.id) { mutableStateOf(preferences.choice(id, "reasoning_effort")) }
    var reasoningSettings by remember { mutableStateOf(false) }
    val broker = remember { com.ayuemin.ilmar.agent.ToolPermissionBroker(context) }
    var toolRevision by remember { mutableIntStateOf(0) }
    val canAgent = remember(toolRevision, id, model?.id) { capabilities.tools && ToolRegistry.authorized(id, preferences, broker).isNotEmpty() }
    var agent by remember(id, model?.id) { mutableStateOf(preferences.enabled(id, "Агент") && canAgent) }
    var search by remember(id) { mutableStateOf(preferences.enabled(id, "Поиск")) }
    var searchSettings by remember { mutableStateOf(false) }
    var toolsExpanded by remember { mutableStateOf(false) }
    val tools = ToolRegistry.catalogue()
    val available = ToolRegistry.availableIds().filter { broker.granted(it) }.toSet()
    var chosen by remember(id) { mutableStateOf(available.filter { preferences.enabled(id, "tool_$it") }.toSet()) }
    var applied by remember(id) { mutableStateOf(chosen) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp).padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ComposerActionTile(Icons.Outlined.AttachFile, "Файл", true, Modifier.weight(1f)) { onDismiss(); onAttachText() }
                ComposerActionTile(Icons.Outlined.CameraAlt, "Фото", true, Modifier.weight(1f)) { onDismiss(); onPhoto() }
                ComposerActionTile(Icons.Outlined.Image, "Изображение", true, Modifier.weight(1f)) { onDismiss(); onImage() }
            }
            Text("Файлы: текст UTF-8, DOCX, XLSX, PDF до 12 страниц. Фото и PDF требуют модели с входом image. До 8 вложений по 20 МБ (текст — 256 КБ); отправка только по кнопке.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ComposerToggleTile(Icons.Outlined.Psychology, if (reasoning) 2 else 0, 3, levelDescription = "Размышление",
                    checked = reasoning, enabled = canReason, modifier = Modifier.weight(1f),
                    onOpenSettings = { reasoningSettings = !reasoningSettings }, onCheckedChange = {
                        reasoning = it; preferences.setEnabled(id, "Размышление", it)
                    })
                ComposerToggleTile(Icons.Outlined.Language, if (search) 3 else 0, 4, levelDescription = "Поиск в интернете",
                    checked = search, enabled = true, modifier = Modifier.weight(1f),
                    onOpenSettings = { searchSettings = !searchSettings }, onCheckedChange = {
                        search = it; preferences.setEnabled(id, "Поиск", it)
                        if (it) searchSettings = true
                    })
                ComposerToggleTile(Icons.Outlined.SmartToy, if (agent) 5 else 0, 5, indicatorText = "АГЕНТ", levelDescription = "Агент",
                    checked = agent, enabled = canAgent, modifier = Modifier.weight(1f), onCheckedChange = {
                        agent = it; preferences.setEnabled(id, "Агент", it)
                    })
            }
            if (searchSettings) com.ayuemin.ilmar.browser.SearchControls(id, preferences, connection?.id, model?.id, onDismiss)
            if (reasoningSettings && canReason) {
                Text("Глубина размышления", style = MaterialTheme.typography.labelLarge)
                (listOf("auto") + capabilities.efforts).distinct().forEach { value ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = effort == value, onClick = { effort = value; preferences.setChoice(id, "reasoning_effort", value) })
                        Text(if (value == "auto") "Автоматически" else value)
                    }
                }
            }
            Text(if (!capabilities.known) "Каталог не сообщил возможности модели. Обнови список в разделе «Модели»."
                else "Reasoning: ${if (canReason) "доступен" else "не подтверждён"}. Tool calling: ${if (capabilities.tools) "поддерживается" else "не подтверждён"}.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!canAgent) Text("Для агента нужны поддержка tools, установленный инструмент, разрешение в настройках и выбор для чата.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ComposerSectionHeader(Icons.Outlined.Storage, "Инструменты", toolsExpanded) { toolsExpanded = !toolsExpanded }
            if (toolsExpanded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = available.isNotEmpty() && chosen.containsAll(available), enabled = available.isNotEmpty(),
                        onCheckedChange = { chosen = if (it) available else emptySet() })
                    Text("Выбрать всё", modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Button(enabled = chosen.isNotEmpty(), onClick = {
                        tools.forEach { preferences.setEnabled(id, "tool_${it.id}", it.id in chosen) }; applied = chosen; toolRevision++
                    }) { Text("Включить", maxLines = 1) }
                }
                tools.forEach { tool ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = tool.id in chosen, enabled = tool.id in available,
                            onCheckedChange = { chosen = if (it) chosen + tool.id else chosen - tool.id })
                        Text(tool.title, Modifier.weight(1f))
                        if (tool.id !in available) Text(if (!tool.executorRegistered || !tool.configured) "Не подключён" else "Нет разрешения", style = MaterialTheme.typography.labelSmall)
                    }
                }
                OutlinedButton(enabled = applied.isNotEmpty(), onClick = {
                    tools.forEach { preferences.setEnabled(id, "tool_${it.id}", false) }; applied = emptySet(); chosen = emptySet(); toolRevision++
                }) { Text("Выключить все") }
            }
        }
    }
}
