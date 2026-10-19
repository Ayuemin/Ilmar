package com.ayuemin.ilmar.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ChatDrawer(
    chats: List<ChatSession>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit
) {
    ModalDrawerSheet {
        Spacer(Modifier.height(20.dp))
        Text("Ilmar", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(16.dp))
        NavigationDrawerItem(
            label = { Text("Новый чат") },
            selected = false,
            onClick = onNew,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Диалоги", style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 24.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(chats, key = { it.id }) { chat ->
                NavigationDrawerItem(
                    label = { Text(chat.title, maxLines = 1) },
                    selected = chat.id == selectedId,
                    onClick = { onSelect(chat.id) },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
        HorizontalDivider()
        NavigationDrawerItem(
            label = { Text("Настройки") },
            selected = false,
            onClick = onSettings,
            modifier = Modifier.padding(12.dp)
        )
    }
}
