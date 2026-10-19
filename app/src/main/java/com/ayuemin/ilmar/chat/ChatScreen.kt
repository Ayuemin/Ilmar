package com.ayuemin.ilmar.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val store = remember(context) { ChatStore(context.applicationContext) }
    var chats by remember { mutableStateOf(store.load().ifEmpty {
        store.create(1L, "Новый чат")
        listOf(ChatSession(1L, "Новый чат"))
    }) }
    var selectedId by remember { mutableStateOf(chats.first().id) }
    var draft by remember { mutableStateOf("") }
    val selected = chats.first { it.id == selectedId }
    Column(Modifier.fillMaxSize().background(Color(0xFF14151C)).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Ilmar", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = {
                val id = (chats.maxOfOrNull { it.id } ?: 0L) + 1L
                store.create(id, "Новый чат")
                chats = chats + ChatSession(id, "Новый чат")
                selectedId = id
                draft = ""
            }) { Text("+ Чат") }
        }
        LazyColumn(Modifier.heightIn(max = 160.dp)) {
            items(chats, key = { it.id }) { chat ->
                TextButton(onClick = { selectedId = chat.id; draft = "" }) {
                    Text(if (chat.id == selectedId) "● " + chat.title else chat.title)
                }
            }
        }
        HorizontalDivider()
        Text(selected.title, style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.weight(3f)) {
            items(selected.messages) { message ->
                Text((if (message.fromUser) "Вы: " else "Ilmar: ") + message.text,
                    modifier = Modifier.padding(vertical = 8.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Сообщение") }
            )
            Button(enabled = draft.isNotBlank(), onClick = {
                val message = draft.trim()
                if (message.isNotEmpty()) {
                    store.append(selectedId, ChatMessage(message, true),
                        if (selected.messages.isEmpty()) message.take(40) else null)
                    chats = chats.map { chat ->
                        if (chat.id == selectedId) chat.copy(
                            title = if (chat.messages.isEmpty()) message.take(40) else chat.title,
                            messages = chat.messages + ChatMessage(message, true)
                        ) else chat
                    }
                    draft = ""
                }
            }) { Text("➤") }
        }
        Text("Локальный чат · ИИ пока не подключён",
            style = MaterialTheme.typography.labelSmall)
    }
}
