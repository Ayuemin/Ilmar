package com.ayuemin.ilmar.chat

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuemin.ilmar.ui.IlmarWelcome
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(onSettings: () -> Unit = {}, controller: ChatViewModel = viewModel(), onConnectionSettings: () -> Unit = onSettings, onSettingsPage: (String) -> Unit = { onSettings() }) {
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    val selected = state.chats.firstOrNull { it.id == state.selectedId } ?: return
    LaunchedEffect(selected.id) { controller.indexArchive(selected.id) }
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
    val selectedProvider = remember(selectedModel, modelRevision) {
        controller.registry.snapshot().providers.find { it.id == selectedModel?.providerId }?.let { connection ->
            if (connection.id == "legacy" && connection.name == "Основное подключение") {
                when (runCatching { java.net.URI(connection.endpoint).host?.lowercase() }.getOrNull()) {
                    "openrouter.ai" -> "OpenRouter"
                    "api.openai.com" -> "OpenAI"
                    else -> connection.name
                }
            } else connection.name
        }
    }
    var overflow by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }
    var activityExpanded by remember(selected.id) { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    var searchOpen by remember(selected.id) { mutableStateOf(false) }
    var query by remember(selected.id) { mutableStateOf("") }
    var speechError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var attachmentChatId by rememberSaveable { mutableStateOf<Long?>(null) }
    val attachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = attachmentChatId
        if (uri != null && target != null) controller.importAttachment(target, uri)
    }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = cameraPath?.let { java.io.File(it) }
        val target = attachmentChatId
        if (success && file != null && target != null) {
            controller.importAttachment(target, androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file), file)
        } else file?.delete()
        cameraPath = null
    }
    val speech = remember { com.ayuemin.ilmar.speech.SpeechController(context.applicationContext, scope) }
    DisposableEffect(speech) { onDispose { speech.close() } }
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) controller.setDraft(selected.id, (draft.trim() + " " + spoken).trim())
        }
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val list = rememberLazyListState()
    val messages = selected.messages
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
    val dictate = {
        try {
            speechError = null
            speechLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            })
        } catch (_: android.content.ActivityNotFoundException) { speechError = "Распознавание речи недоступно" }
    }
    if (showModes) ChatModeMenu(selected.id, chatModes,
        onAttachText = {
            attachmentChatId = selected.id
            try { attachmentLauncher.launch(arrayOf("*/*")) }
            catch (_: android.content.ActivityNotFoundException) { speechError = "Выбор файлов недоступен" }
        },
        onImage = {
            attachmentChatId = selected.id
            try { attachmentLauncher.launch(arrayOf("image/*")) }
            catch (_: android.content.ActivityNotFoundException) { speechError = "Выбор изображений недоступен" }
        }, onPhoto = {
            attachmentChatId = selected.id
            try {
                val directory = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
                val file = java.io.File.createTempFile("photo-", ".jpg", directory)
                cameraPath = file.path
                val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
                cameraLauncher.launch(uri)
            } catch (_: Exception) { cameraPath?.let { java.io.File(it).delete() }; cameraPath = null; speechError = "Не удалось открыть камеру" }
        }, onDismiss = { showModes = false })
    if (showModels) ChatProviderDialog(selected.id, controller.registry, onConnectionSettings,
        onSelected = { modelRevision++ }, onDismiss = { showModels = false })
    if (showMemory) ChatMemoryPanel(selected.id, controller, sending, onDismiss = { showMemory = false })
    if (showStats) AlertDialog(onDismissRequest = { showStats = false }, title = { Text("Статистика чата") },
        text = { Text(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), text = "Сообщений: ${selected.messages.size}\nСимволов: ${selected.messages.sumOf { it.text.length }}\n\n${controller.usage(selected.id).describe()}") },
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
                Text(selected.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { overflow = true }) { Icon(Icons.Default.MoreVert, "Действия с чатом") }
                    DropdownMenu(overflow, { overflow = false }) {
                        DropdownMenuItem(text = { Text("Память и сжатие") }, onClick = { overflow = false; showMemory = true })
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
                        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = if (compact) 4.dp else 8.dp)) {
                            if (message.fromUser) Surface(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                                shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()
                            ) { SelectionContainer { Text(message.text, modifier = Modifier.fillMaxWidth().padding(12.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Start) } }
                            else com.ayuemin.ilmar.ui.markdown.RichMessageContent(message.text, MaterialTheme.colorScheme.onSurface)
                            com.ayuemin.ilmar.attachments.AttachmentList(message.attachments)
                            if (message.id != 0L) Box(Modifier.fillMaxWidth(), contentAlignment = if (message.fromUser) Alignment.CenterEnd else Alignment.CenterStart) { MessageActions(message, sending,
                                onAskAgain = { controller.askAgain(selected.id, message.id); following.value = true },
                                onBranch = { controller.branch(selected.id, message.id); following.value = true },
                                onSpeak = { speech.speak(message.text) }) }
                        }
                    }
                }
            }
            state.contextStatus[selected.id]?.let { label -> TextButton(onClick = { showMemory = true }) { Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            if (inThisChat) {
                val events = state.activityEvents
                val label = state.activityLabel.ifBlank { "Агент выполняет задачу…" }
                Column(Modifier.fillMaxWidth()) {
                    if (events.size > 1) TextButton(onClick = { activityExpanded = !activityExpanded }) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(if (activityExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    } else Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                    if (activityExpanded && events.size > 1) Column(Modifier.padding(start = 20.dp, bottom = 8.dp)) {
                        events.forEach { event -> Text("• $event", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            else if (sending) TextButton(onClick = { state.activeChatId?.let(controller::select) }) {
                Text("Перейти к активному ответу")
            }
            (state.errors[selected.id] ?: speechError ?: speech.error)?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (state.errors.containsKey(selected.id)) {
                    Row {
                        TextButton(onClick = { onSettingsPage("Диагностика") }) { Text("Подробности / журнал") }
                        if (error.contains("контекст", true) || error.contains("Compact", true)) TextButton(onClick = { onSettingsPage("Память и контекст") }) { Text("Память") }
                        else if (error.contains("лимит", true) || error.contains("врем", true)) TextButton(onClick = { onSettingsPage("Расширенные лимиты") }) { Text("Лимиты") }
                        else TextButton(onClick = onConnectionSettings) { Text("Подключение") }
                    }
                }
            }
            if (!inThisChat && (selected.messages.lastOrNull()?.fromUser == true || state.errors.containsKey(selected.id) && selected.messages.any { it.fromUser })) {
                TextButton(enabled = !sending, onClick = controller::retry) { Text("Повторить запрос") }
            }
            if (speech.playing) TextButton(onClick = { speech.stop() }) { Text("Остановить озвучку") }
            if (selected.id in state.importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.interventions[selected.id].orEmpty().forEach { item ->
                var menuOpen by remember(item.id) { mutableStateOf(false) }
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            Text(when(item.status) { "NOW" -> "На ближайшем раунде"; "QUEUED" -> "В очереди"; else -> "Выбери действие" }, style = MaterialTheme.typography.labelSmall)
                        }
                        if (item.status == "PENDING") Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Действия с уточнением") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                listOf("EDIT" to "Редактировать", "NOW" to "Отправить сейчас", "QUEUED" to "Поставить в очередь", "DELETE" to "Удалить").forEach { (action, title) ->
                                    DropdownMenuItem(text = { Text(title) }, onClick = { menuOpen = false; controller.chooseIntervention(selected.id,item.id,action) })
                                }
                            }
                        }
                    }
                }
            }
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(4.dp)) {
                    com.ayuemin.ilmar.attachments.AttachmentList(state.attachments[selected.id].orEmpty(),
                        onRemove = { controller.removeAttachment(selected.id, it) })
                    TextField(draft, { controller.setDraft(selected.id, it) }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 5,
                        colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(enabled = selected.id !in state.importing, onClick = { showModes = true }) { Icon(Icons.Default.Add, "Режимы и инструменты") }
                        TextButton(onClick = { showModels = true }, modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.Start) {
                                Text(selectedModel?.name?.substringAfterLast('/') ?: "Выбрать модель", maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                                selectedProvider?.let { providerName ->
                                    Text(providerName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp))
                        }
                        IconButton(onClick = dictate) { Icon(Icons.Default.Mic, "Распознать речь") }
                        if (sending) {
                            if (inThisChat) IconButton(enabled = draft.isNotBlank() && selected.id !in state.importing, onClick = {
                                controller.stageIntervention(selected.id, draft)
                            }) { Icon(Icons.Default.Send, "Добавить уточнение") }
                            IconButton(onClick = controller::stop) { Icon(Icons.Default.Stop, "Остановить ответ") }
                        }
                        else IconButton(enabled = selected.id !in state.importing && (draft.isNotBlank() || state.attachments[selected.id].orEmpty().isNotEmpty()), onClick = {
                            val text = draft; speechError = null; following.value = true; controller.send(text)
                        }) { Icon(Icons.Default.Send, "Отправить сообщение") }
                    }
                }
            }
        }
    }
}
