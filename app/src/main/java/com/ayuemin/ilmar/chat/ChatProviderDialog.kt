package com.ayuemin.ilmar.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ayuemin.ilmar.model.ProviderRegistry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatProviderDialog(chatId: Long, registry: ProviderRegistry,
    onSettings: () -> Unit, onSelected: () -> Unit, onDismiss: () -> Unit) {
    val data = registry.snapshot()
    var query by remember { mutableStateOf("") }
    val selected = registry.modelForChat(chatId)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(horizontal = 20.dp)) {
            Text("Выбрать модель", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(query, { query = it }, label = { Text("Поиск моделей и подключений") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
            if (data.models.isEmpty()) {
                Text("Нет настроенных моделей")
                TextButton(onClick = { onDismiss(); onSettings() }) { Text("Добавить подключение") }
            } else {
                TextButton(onClick = { registry.select(chatId, null); onSelected(); onDismiss() }) {
                    Text("По умолчанию")
                }
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(data.models.filter { model ->
                        model.name.contains(query, true) || data.providers.any {
                            it.id == model.providerId && it.name.contains(query, true)
                        }
                    }, key = { it.id }) { model ->
                        val connection = data.providers.find { it.id == model.providerId }
                        val ready = connection != null && registry.hasKey(model.providerId) && ProviderRegistry.validEndpoint(connection.endpoint)
                        Row(Modifier.fillMaxWidth().clickable(enabled = ready) {
                            registry.select(chatId, model.id); onSelected(); onDismiss()
                        }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selected?.id == model.id, enabled = ready, onClick = {
                                registry.select(chatId, model.id); onSelected(); onDismiss()
                            })
                            Column(Modifier.weight(1f)) {
                                Text(model.name)
                                Text((connection?.name ?: "Подключение недоступно") + if (ready) "" else " · требуется настройка",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            TextButton(onClick = { onDismiss(); onSettings() }) { Text("Управление подключениями") }
            Spacer(Modifier.height(20.dp))
        }
    }
}
