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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import com.ayuemin.ilmar.ui.IlmarWelcome
import com.ayuemin.ilmar.ui.IlmarCircleAction
import com.ayuemin.ilmar.ui.IlmarFieldShape
import com.ayuemin.ilmar.security.CredentialStore
import com.ayuemin.ilmar.model.OpenAiCompatibleProvider

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
    var sending by remember { mutableStateOf(false) }
    var streamingText by remember { mutableStateOf("") }
    var streamingChatId by remember { mutableStateOf<Long?>(null) }
    var activeRequest by remember { mutableStateOf<Job?>(null) }
    var requestError by remember { mutableStateOf<String?>(null) }
    val provider = remember { OpenAiCompatibleProvider() }
    val credentials = remember(context) { CredentialStore(context) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val selected = chats.first { it.id == selectedId }
    val messageListState = rememberLazyListState()
    LaunchedEffect(selectedId, selected.messages.size, streamingText.length) {
        val visibleCount = selected.messages.size + if (sending && streamingChatId == selectedId && streamingText.isNotEmpty()) 1 else 0
        if (visibleCount > 0) messageListState.scrollToItem(visibleCount - 1)
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
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IlmarCircleAction("☰", "Открыть диалоги", onClick = { scope.launch { drawerState.open() } })
            Text(selected.title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            IlmarCircleAction("⋮", "Настройки", onClick = onSettings)

        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        if (selected.messages.isEmpty() && !(sending && streamingChatId == selectedId)) {
            IlmarWelcome(Modifier.align(Alignment.Center))
        } else LazyColumn(Modifier.fillMaxSize(),
            state = messageListState,
            verticalArrangement = Arrangement.spacedBy(if (compactMessages) 4.dp else 10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)) {
            items(selected.messages + if (sending && streamingChatId == selectedId && streamingText.isNotEmpty()) listOf(ChatMessage(streamingText, false)) else emptyList()) { message ->
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start) {
                    Surface(color = if (message.fromUser) Color(0xFF493028) else Color(0xFF201511),
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
            Button(enabled = draft.isNotBlank() && !sending, onClick = {
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
                    requestError = null
                    val targetChat = selectedId
                    val history = chats.first { it.id == targetChat }.messages
                    val apiKey = credentials.loadApiKey()
                    if (apiKey == null) {
                        requestError = "Сообщение сохранено. Сохрани API-ключ в настройках, чтобы получить ответ"
                    } else {
                        sending = true
                        streamingChatId = targetChat
                        streamingText = ""
                        activeRequest = scope.launch {
                            try {
                                val buffer = StringBuilder()
                                provider.stream(
                                    prefs.getString("api_endpoint", "https://api.openai.com/v1/chat/completions") ?: "",
                                    prefs.getString("api_model", "gpt-4o-mini") ?: "",
                                    apiKey, history
                                ) { delta ->
                                    buffer.append(delta)
                                    streamingText = buffer.toString()
                                }
                                val reply = buffer.toString()
                                if (reply.isNotBlank()) {
                                    store.append(targetChat, ChatMessage(reply, false), null)
                                    chats = chats.map { chat ->
                                        if (chat.id == targetChat) chat.copy(messages = chat.messages + ChatMessage(reply, false))
                                        else chat
                                    }
                                }
                            } catch (_: CancellationException) {
                                // Partial output stays ephemeral and is not saved.
                            } catch (e: Exception) {
                                requestError = e.message ?: "Не удалось получить ответ"
                            } finally {
                                streamingText = ""
                                streamingChatId = null
                                sending = false
                                activeRequest = null
                            }
                        }
                    }
                }
            }) { Text(if (sending) "…" else "➤") }
        }
        if (sending) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = { activeRequest?.cancel() }) { Text("Остановить ответ") }
        }
        requestError?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        Text("OpenAI-compatible · HTTPS",
            color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
    }
    }
}
