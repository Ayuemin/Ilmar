package com.ayuemin.ilmar.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.ayuemin.ilmar.ui.IlmarWelcome
import com.ayuemin.ilmar.ui.IlmarCircleAction
import com.ayuemin.ilmar.ui.IlmarFieldShape

@Composable
fun ChatScreen(onSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val store = remember(context) { ChatStore(context.applicationContext) }
    val prefs = remember(context) { context.getSharedPreferences("ilmar_ui", android.content.Context.MODE_PRIVATE) }
    var compactMessages by remember { mutableStateOf(prefs.getBoolean("compact_messages", false)) }
    androidx.compose.runtime.DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "compact_messages") compactMessages = prefs.getBoolean(key, false)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var chats by remember { mutableStateOf(store.load().ifEmpty {
        store.create(1L, "Новый чат")
        listOf(ChatSession(1L, "Новый чат"))
    }) }
    var selectedId by remember { mutableStateOf(chats.first().id) }
    var draft by remember { mutableStateOf("") }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val selected = chats.first { it.id == selectedId }
    val messageListState = rememberLazyListState()
    LaunchedEffect(selectedId, selected.messages.size) {
        if (selected.messages.isNotEmpty()) messageListState.animateScrollToItem(selected.messages.lastIndex)
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatDrawer(chats, selectedId,
                onSelect = { selectedId = it; draft = ""; scope.launch { drawerState.close() } },
                onNew = {
                    val id = (chats.maxOfOrNull { it.id } ?: 0L) + 1L
                    store.create(id, "Новый чат")
                    chats = chats + ChatSession(id, "Новый чат")
                    selectedId = id
                    draft = ""
                    scope.launch { drawerState.close() }
                },
                onSettings = { scope.launch { drawerState.close() }; onSettings() }
            )
        }
    ) {
    Column(Modifier.fillMaxSize().background(Color(0xFF14151C)).imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IlmarCircleAction("☰", "Открыть диалоги", onClick = { scope.launch { drawerState.open() } })
            Text("Ilmar", color = Color.White, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f))
            IlmarCircleAction("⚙", "Настройки", onClick = onSettings)
            IconButton(onClick = {
                val id = (chats.maxOfOrNull { it.id } ?: 0L) + 1L
                store.create(id, "Новый чат")
                chats = chats + ChatSession(id, "Новый чат")
                selectedId = id
                draft = ""
            }) { Text("+", color = Color.White, style = MaterialTheme.typography.headlineSmall) }
        }
        Text(selected.title, color = Color(0xFFB7A5E9), style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(vertical = 8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
        if (selected.messages.isEmpty()) {
            IlmarWelcome(Modifier.align(Alignment.Center))
        } else LazyColumn(Modifier.fillMaxSize(),
            state = messageListState,
            verticalArrangement = Arrangement.spacedBy(if (compactMessages) 4.dp else 10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)) {
            items(selected.messages) { message ->
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start) {
                    Surface(color = if (message.fromUser) Color(0xFF38304D) else Color(0xFF242632),
                        shape = RoundedCornerShape(18.dp), modifier = Modifier.widthIn(max = 320.dp)) {
                        SelectionContainer {
                            Text(message.text, color = Color.White,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = if (compactMessages) 7.dp else 12.dp))
                        }
                    }
                }
            }
        }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                shape = IlmarFieldShape,
                maxLines = 5,
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
            color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
    }
    }
}
