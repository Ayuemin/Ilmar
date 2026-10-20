package com.ayuemin.ilmar.chat

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuemin.ilmar.ui.IlmarWelcome
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(onSettings: () -> Unit = {}, controller: ChatViewModel = viewModel()) {
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    val selected = state.chats.firstOrNull { it.id == state.selectedId } ?: return
    val sending = state.activeChatId != null
    val inThisChat = state.activeChatId == selected.id
    val draft = state.drafts[selected.id].orEmpty()
    val prefs = remember { context.getSharedPreferences("ilmar_ui", android.content.Context.MODE_PRIVATE) }
    val compact = prefs.getBoolean("compact_messages", false)
    val chatModes = remember { ChatModePreferences(context) }
    var showModes by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var modelRevision by remember { mutableIntStateOf(0) }
    val selectedModel = remember(selected.id, modelRevision) { controller.registry.modelForChat(selected.id) }
    var overflow by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    var searchOpen by remember(selected.id) { mutableStateOf(false) }
    var query by remember(selected.id) { mutableStateOf("") }
    var speechError by remember { mutableStateOf<String?>(null) }
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) controller.setDraft(selected.id, (draft.trim() + " " + spoken).trim())
        }
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val messages = (selected.messages + if (inThisChat && state.streamingText.isNotEmpty())
        listOf(ChatMessage(state.streamingText, false)) else emptyList())
        .filter { !searchOpen || query.isBlank() || it.text.contains(query, true) }
    // Follow the stream only when the reader is already at the bottom.
    val following = remember { mutableStateOf(true) }
    LaunchedEffect(selected.id) { following.value = true; if (messages.isNotEmpty()) list.scrollToItem(messages.lastIndex) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (scrolling, canForward) ->
            if (scrolling) following.value = !canForward
        }
    }
    LaunchedEffect(messages.size, state.streamingText.length) {
        if (following.value && !searchOpen && messages.isNotEmpty()) list.scrollToItem(messages.lastIndex)
    }
    if (showModes) ChatModeMenu(selected.id, chatModes) { showModes = false }
    if (showModels) ChatProviderDialog(selected.id, controller.registry, onSettings,
        onSelected = { modelRevision++ }, onDismiss = { showModels = false })
    if (showStats) AlertDialog(onDismissRequest = { showStats = false }, title = { Text("Статистика чата") },
        text = { Text("Сообщений: ${selected.messages.size}\nСимволов: ${selected.messages.sumOf { it.text.length }}\nРасход токенов пока не учитывается.") },
        confirmButton = { TextButton(onClick = { showStats = false }) { Text("Закрыть") } })
    if (showClear) AlertDialog(onDismissRequest = { showClear = false }, title = { Text("Очистить чат?") },
        text = { Text("Сообщения будут удалены без возможности восстановления.") },
        confirmButton = { TextButton(onClick = { controller.clear(selected.id); showClear = false }) { Text("Очистить") } },
        dismissButton = { TextButton(onClick = { showClear = false }) { Text("Отмена") } })
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ChatDrawer(state.chats, selected.id,
            onSelect = { controller.select(it); scope.launch { drawer.close() } },
            onNew = { controller.create(); scope.launch { drawer.close() } },
            onRename = controller::rename, onDelete = controller::delete,
            onSettings = { scope.launch { drawer.close() }; onSettings() })
    }) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()
            .padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Открыть диалоги") }
                Column(Modifier.weight(1f)) {
                    Text(selected.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { showModels = true }, contentPadding = PaddingValues(0.dp)) {
                        Text(selectedModel?.name ?: "Выбрать модель", maxLines = 1)
                        Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp))
                    }
                }
                Box {
                    IconButton(onClick = { overflow = true }) { Icon(Icons.Default.MoreVert, "Действия с чатом") }
                    DropdownMenu(overflow, { overflow = false }) {
                        DropdownMenuItem(text = { Text("Статистика чата") }, onClick = { overflow = false; showStats = true })
                        DropdownMenuItem(text = { Text("Поиск по чату") }, onClick = { overflow = false; searchOpen = true })
                        DropdownMenuItem(text = { Text("Выбрать модель") }, onClick = { overflow = false; showModels = true })
                        DropdownMenuItem(text = { Text("Очистить чат") }, onClick = { overflow = false; showClear = true })
                    }
                }
            }
            if (searchOpen) OutlinedTextField(query, { query = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), label = { Text("Поиск в чате") },
                trailingIcon = { IconButton(onClick = { searchOpen = false; query = "" }) { Icon(Icons.Default.Close, "Закрыть поиск") } })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty() && !searchOpen) IlmarWelcome(Modifier.align(Alignment.Center))
                else LazyColumn(Modifier.fillMaxSize(), state = list,
                    verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)) {
                    items(messages) { message ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start) {
                            Surface(color = if (message.fromUser) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(0.94f)) {
                                SelectionContainer {
                                    Text(message.text, modifier = Modifier.padding(horizontal = 16.dp, vertical = if (compact) 7.dp else 12.dp))
                                }
                            }
                        }
                    }
                }
            }
            if (inThisChat) LinearProgressIndicator(Modifier.fillMaxWidth())
            (state.errors[selected.id] ?: speechError)?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (!inThisChat && selected.messages.lastOrNull()?.fromUser == true) {
                TextButton(enabled = !sending, onClick = controller::retry) { Text("Повторить запрос") }
            }
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(4.dp)) {
                    TextField(draft, { controller.setDraft(selected.id, it) }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 5,
                        colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showModes = true }) { Icon(Icons.Default.Add, "Режимы и инструменты") }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            try {
                                speechError = null
                                speechLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                })
                            } catch (_: android.content.ActivityNotFoundException) { speechError = "Распознавание речи недоступно" }
                        }) { Icon(Icons.Default.Mic, "Распознать речь") }
                        if (sending) IconButton(onClick = controller::stop) { Icon(Icons.Default.Stop, "Остановить ответ") }
                        else IconButton(enabled = draft.isNotBlank(), onClick = {
                            val text = draft; controller.setDraft(selected.id, ""); speechError = null; following.value = true; controller.send(text)
                        }) { Icon(Icons.Default.Send, "Отправить сообщение") }
                    }
                }
            }
        }
    }
}
