package com.ayuemin.ilmar.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun ChatDrawer(
    chats: List<ChatSession>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(chats, query) {
        if (query.isBlank()) chats.asReversed()
        else chats.asReversed().filter { chat ->
            chat.title.contains(query.trim(), ignoreCase = true) ||
                chat.messages.any { it.text.contains(query.trim(), ignoreCase = true) }
        }
    }
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Spacer(Modifier.height(20.dp))
        Text("✦ Ilmar", color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(16.dp))
        NavigationDrawerItem(
            label = { Text("＋  Новый чат") },
            selected = false, onClick = { query = ""; onNew() },
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            placeholder = { Text("Поиск по чатам и сообщениям") },
            trailingIcon = {
                if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("×") }
            }
        )
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("ИСТОРИЯ · ${filtered.size}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 24.dp))
        if (filtered.isEmpty()) {
            Text("Ничего не найдено",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            items(filtered, key = { it.id }) { chat ->
                NavigationDrawerItem(
                    label = { Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = chat.id == selectedId,
                    onClick = { query = ""; onSelect(chat.id) },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
        HorizontalDivider()
        NavigationDrawerItem(
            label = { Text("⚙  Настройки") }, selected = false,
            onClick = onSettings, modifier = Modifier.padding(12.dp)
        )
    }
}
