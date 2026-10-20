package com.ayuemin.ilmar.chat

import androidx.compose.foundation.layout.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.speech.RecognizerIntent
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
    val chatModes = remember(context) { ChatModePreferences(context) }
    var showModes by remember { mutableStateOf(false) }
    val credentials = remember(context) { CredentialStore(context) }
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) draft = (draft.trim() + " " + spoken).trim()
        }
    }
    var overflow by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var statsOpen by remember { mutableStateOf(false) }
    var clearOpen by remember { mutableStateOf(false) }
    var providerOpen by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val selected = chats.first { it.id == selectedId }
    val messageListState = rememberLazyListState()
    LaunchedEffect(selectedId, selected.messages.size, streamingText.length, searchOpen, searchQuery) {
        val visibleCount = selected.messages.size + if (sending && streamingChatId == selectedId && streamingText.isNotEmpty()) 1 else 0
        if (visibleCount > 0 && (!searchOpen || searchQuery.isBlank())) messageListState.scrollToItem(visibleCount - 1)
    }
    if (showModes) ChatModeMenu(selectedId, chatModes) { showModes = false }
    if (providerOpen) ChatProviderDialog(selectedId, chatModes, prefs) { providerOpen = false }
    if (statsOpen) AlertDialog(onDismissRequest = { statsOpen = false },
        title = { Text("Статистика чата") },
        text = { Text("Сообщений: ${selected.messages.size}. Символов: ${selected.messages.sumOf { it.text.length }}. Точный расход токенов пока не учитывается.") },
        confirmButton = { TextButton(onClick = { statsOpen = false }) { Text("Закрыть") } })
    if (clearOpen) AlertDialog(onDismissRequest = { clearOpen = false },
        title = { Text("Очистить чат?") },
        text = { Text("Сообщения будут удалены без возможности восстановления.") },
        confirmButton = { TextButton(onClick = {
            if (streamingChatId == selectedId) activeRequest?.cancel()
            store.clearMessages(selectedId)
            chats = chats.map { if (it.id == selectedId) it.copy(messages = emptyList()) else it }
            clearOpen = false
        }) { Text("Очистить") } },
        dismissButton = { TextButton(onClick = { clearOpen = false }) { Text("Отмена") } })
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
                onRename = { id, title ->
                    store.rename(id, title)
                    chats = chats.map { if (it.id == id) it.copy(title = title) else it }
                },
                onDelete = { id ->
                    if (streamingChatId == id) activeRequest?.cancel()
                    store.delete(id)
                    chats = chats.filterNot { it.id == id }
                    if (chats.isEmpty()) {
                        val nextId = id + 1
                        store.create(nextId, "Новый чат")
                        chats = listOf(ChatSession(nextId, "Новый чат"))
                    }
                    if (selectedId == id) { selectedId = chats.first().id; draft = "" }
                },
                onSettings = { scope.launch { drawerState.close() }; onSettings() }
            )
        }
    ) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IlmarCircleAction("☰", "Открыть диалоги", onClick = { scope.launch { drawerState.open() } })
            Text(selected.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            Box {
                IlmarCircleAction("⋮", "Действия с чатом", onClick = { overflow = true })
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                    DropdownMenuItem(text = { Text("Статистика токенов") }, onClick = { overflow = false; statsOpen = true })
                    DropdownMenuItem(text = { Text("Поиск по чату") }, onClick = { overflow = false; searchOpen = true })
                    DropdownMenuItem(text = { Text("Провайдер и модель") }, onClick = { overflow = false; providerOpen = true })
                    DropdownMenuItem(text = { Text("Очистить чат") }, onClick = { overflow = false; clearOpen = true })
                    DropdownMenuItem(text = { Text("Добавить в проект · позже") }, enabled = false, onClick = {})
                }
            }

        }
        if (searchOpen) OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Поиск в чате") },
            trailingIcon = { TextButton(onClick = { searchOpen = false; searchQuery = "" }) { Text("×") } })
        Box(Modifier.weight(1f).fillMaxWidth()) {
        if (selected.messages.isEmpty() && !(sending && streamingChatId == selectedId)) {
            IlmarWelcome(Modifier.align(Alignment.Center))
        } else LazyColumn(Modifier.fillMaxSize(),
            state = messageListState,
            verticalArrangement = Arrangement.spacedBy(if (compactMessages) 4.dp else 10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)) {
            items((selected.messages + if (sending && streamingChatId == selectedId && streamingText.isNotEmpty()) listOf(ChatMessage(streamingText, false)) else emptyList()).filter { !searchOpen || searchQuery.isBlank() || it.text.contains(searchQuery, ignoreCase = true) }) { message ->
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start) {
                    Surface(color = if (message.fromUser) Color(0xFF303030) else Color(0xFF1B1B1B),
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                shape = IlmarFieldShape,
                maxLines = 5,
                placeholder = {},
                leadingIcon = { TextButton(onClick = { showModes = true }) { Text("+", style = MaterialTheme.typography.headlineMedium) } },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            try {
                                speechLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                })
                            } catch (_: android.content.ActivityNotFoundException) {
                                requestError = "Распознавание речи недоступно"
                            }
                        }) { Text("🎙") }
                        IconButton(enabled = draft.isNotBlank() && !sending, onClick = {
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
                                    chatModes.choice(targetChat, "api_endpoint").takeUnless { it == "Автоматически" } ?: (prefs.getString("api_endpoint", "https://api.openai.com/v1/chat/completions") ?: ""),
                                    chatModes.choice(targetChat, "api_model").takeUnless { it == "Автоматически" } ?: (prefs.getString("api_model", "gpt-4o-mini") ?: ""),
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
                }
            )
        }
        if (sending) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = { activeRequest?.cancel() }) { Text("Остановить ответ") }
        }
        requestError?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
    }
}
