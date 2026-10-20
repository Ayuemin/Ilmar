package com.ayuemin.ilmar.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun ChatDrawer(
    chats: List<ChatSession>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    onNew: () -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onSettings: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var menuId by remember { mutableStateOf<Long?>(null) }
    var renameId by remember { mutableStateOf<Long?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var deleteId by remember { mutableStateOf<Long?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val favorites = remember(context) { context.getSharedPreferences("ilmar_favorites", android.content.Context.MODE_PRIVATE) }
    var favoriteVersion by remember { mutableIntStateOf(0) }
    if (renameId != null) AlertDialog(onDismissRequest = { renameId = null },
        title = { Text("Переименовать чат") },
        text = { OutlinedTextField(value = renameDraft, onValueChange = { renameDraft = it }, singleLine = true) },
        confirmButton = { TextButton(enabled = renameDraft.isNotBlank(), onClick = {
            renameId?.let { onRename(it, renameDraft.trim()) }; renameId = null
        }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = { renameId = null }) { Text("Отмена") } })
    if (deleteId != null) AlertDialog(onDismissRequest = { deleteId = null },
        title = { Text("Удалить чат?") },
        text = { Text("История будет удалена без возможности восстановления.") },
        confirmButton = { TextButton(onClick = { deleteId?.let(onDelete); deleteId = null }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Отмена") } })
    val filtered = remember(chats, query) {
        if (query.isBlank()) chats.asReversed()
        else chats.asReversed().filter { chat ->
            chat.title.contains(query.trim(), ignoreCase = true) ||
                chat.messages.any { it.text.contains(query.trim(), ignoreCase = true) }
        }
    }
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Ilmar", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Text("⌕", style = MaterialTheme.typography.headlineMedium) }
        }
        Spacer(Modifier.height(16.dp))
        NavigationDrawerItem(
            label = { Text("⊞  Новый чат") },
            selected = false, onClick = { query = ""; onNew() },
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        if (searching) OutlinedTextField(
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
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    NavigationDrawerItem(
                        label = { Text((if (favorites.getBoolean(chat.id.toString(), false)) "★ " else "") + chat.title,
                            maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        selected = chat.id == selectedId,
                        onClick = { query = ""; onSelect(chat.id) },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp)
                    )
                    Box {
                        IconButton(onClick = { menuId = chat.id }) { Text("⋮") }
                        DropdownMenu(expanded = menuId == chat.id, onDismissRequest = { menuId = null }) {
                            DropdownMenuItem(text = { Text("Переименовать") }, onClick = {
                                menuId = null; renameId = chat.id; renameDraft = chat.title
                            })
                            DropdownMenuItem(text = { Text(if (favorites.getBoolean(chat.id.toString(), false)) "Убрать из избранного" else "В избранное") },
                                onClick = {
                                    val key = chat.id.toString()
                                    favorites.edit().putBoolean(key, !favorites.getBoolean(key, false)).apply()
                                    favoriteVersion++; menuId = null
                                })
                            DropdownMenuItem(text = { Text("Удалить") }, onClick = {
                                menuId = null; deleteId = chat.id
                            })
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        NavigationDrawerItem(
            label = { Text("⚙  Настройки", color = MaterialTheme.colorScheme.primary) }, selected = false,
            onClick = onSettings, modifier = Modifier.padding(12.dp)
        )
    }
}
