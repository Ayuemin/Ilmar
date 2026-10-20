package com.ayuemin.ilmar.chat

import android.app.Application
import com.ayuemin.ilmar.settings.RuntimeLimits as Limits
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ayuemin.ilmar.diagnostics.Diagnostics
import com.ayuemin.ilmar.model.OpenAiCompatibleProvider
import com.ayuemin.ilmar.model.ChatProvider
import com.ayuemin.ilmar.model.ProviderFailure
import com.ayuemin.ilmar.model.ProviderRegistry
import com.ayuemin.ilmar.model.GenerationOptions
import com.ayuemin.ilmar.model.TokenUsage
import com.ayuemin.ilmar.agent.AgentLoopRunner
import com.ayuemin.ilmar.agent.ToolPermissionBroker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatState(
    val chats: List<ChatSession> = emptyList(), val selectedId: Long = 0,
    val activeChatId: Long? = null, val streamingText: String = "",
    val attachments: Map<Long, List<com.ayuemin.ilmar.attachments.ChatAttachment>> = emptyMap(), val importing: Set<Long> = emptySet(),
    val contextStatus: Map<Long, String> = emptyMap(),
    val archiveStatus: Map<Long, String> = emptyMap(), val taskRevision: Map<Long, Long> = emptyMap(),
    val errors: Map<Long, String> = emptyMap(), val drafts: Map<Long, String> = emptyMap()
)

/** Activity scoped: independent of screen composition, settings navigation and rotation. */
class ChatViewModel @JvmOverloads constructor(
    application: Application,
    private val provider: ChatProvider = OpenAiCompatibleProvider(),
    val registry: ProviderRegistry = ProviderRegistry(application),
    private val contextDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default
) : AndroidViewModel(application) {
    private val store = ChatStore(application)
    private val memory = com.ayuemin.ilmar.memory.ContextStore(store)
    private val contextPreferences = com.ayuemin.ilmar.memory.ContextPreferences(application)
    private val attachmentStore = com.ayuemin.ilmar.attachments.AttachmentStore(application)
    private val modes = ChatModePreferences(application)
    private val permissions = ToolPermissionBroker(application)
    private val diagnostics = Diagnostics(application)
    private val archive = com.ayuemin.ilmar.memory.ArchiveRetrieval(application, registry)
    private val runtime = application.getSharedPreferences("ilmar_requests", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(ChatState())
    val state = mutable.asStateFlow()
    private var request: Job? = null
    @Volatile private var closed = false

    init {
        com.ayuemin.ilmar.tools.NativeTools.install(application)
        val chats = store.load().ifEmpty {
            store.create(1L, "Новый чат"); listOf(ChatSession(1L, "Новый чат"))
        }
        com.ayuemin.ilmar.memory.EmbeddingLedger(store).interruptOpen()
        memory.tasks.interruptOpen()
        val interruptedDetails = activeDiagnostics()
        val interrupted = runtime.getLong("active_chat", -1)
        runtime.getString("active_request", null)?.let { pending ->
            if (interrupted >= 0) store.recordUsage(interrupted, pending, TokenUsage(), "interrupted", runtime.getString("active_purpose", "answer") ?: "answer")
        }
        runtime.edit().remove("active_request").commit()
        if (interrupted >= 0) memory.interrupt(interrupted)
        val errors = if (chats.any { it.id == interrupted && it.messages.lastOrNull()?.fromUser == true }) {
            diagnostics.record("INTERRUPTED", interrupted, interruptedDetails)
            mapOf(interrupted to "Ответ прерван при закрытии приложения. Можно повторить запрос.")
        } else emptyMap()
        runtime.edit().remove("active_chat").remove("active_task").remove("active_run").remove("active_parent_run").commit()
        mutable.value = ChatState(chats, chats.first().id, errors = errors, attachments = store.drafts())
        val referenced = (chats.flatMap { it.messages }.flatMap { it.attachments } + mutable.value.attachments.values.flatten())
            .flatMap { listOf(it.id) + it.images }.toSet()
        java.io.File(application.filesDir, "attachments").listFiles()?.filter { it.name !in referenced }?.forEach { it.delete() }
        indexArchive(chats.first().id)
    }

    internal fun indexArchive(chatId: Long, force: Boolean = false) { archive.schedule(viewModelScope, chatId, { label -> mutable.update { it.copy(archiveStatus = it.archiveStatus + (chatId to label)) } }, force) }
    internal suspend fun semanticSearch(chatId: Long, query: String) = archive.search(chatId, query, Long.MAX_VALUE)
    internal fun stopArchive(chatId: Long) { archive.cancel(chatId); mutable.update { it.copy(archiveStatus = it.archiveStatus + (chatId to "Индексация остановлена. Сохранённые пакеты будут использованы при продолжении.")) } }
    internal fun embeddingUsage(chatId: Long) = com.ayuemin.ilmar.memory.EmbeddingLedger(store).summary(chatId)
    internal fun memorySnapshot(chatId: Long) = memory.factualSnapshot(chatId)
    internal fun memorySearch(chatId: Long, query: String) = memory.search(chatId, query)
    internal fun setRules(chatId: Long, text: String) { if (mutable.value.activeChatId == null) memory.setRules(chatId, text) }
    internal fun deleteCapsule(chatId: Long, id: Long) { if (mutable.value.activeChatId == null) memory.delete(chatId, id) }
    internal fun activateCapsule(chatId: Long, id: Long) { if (mutable.value.activeChatId == null) memory.activate(chatId, id) }
    fun compact(chatId: Long) { if (mutable.value.activeChatId == null) generate(chatId, true) }
    fun usage(chatId: Long) = store.usage(chatId)
    fun importAttachment(chatId: Long, uri: android.net.Uri, cleanup: java.io.File? = null) {
        if (chatId in mutable.value.importing || mutable.value.chats.none { it.id == chatId }) { cleanup?.delete(); return }
        mutable.update { it.copy(importing = it.importing + chatId, errors = it.errors - chatId) }
        viewModelScope.launch {
            try {
                require(mutable.value.attachments[chatId].orEmpty().size < Limits.get("attachments")) { "Достигнут глобальный лимит вложений" }
                val item = attachmentStore.import(uri)
                if (mutable.value.chats.any { it.id == chatId }) {
                    val items = mutable.value.attachments[chatId].orEmpty() + item
                    if (items.sumOf { it.text?.length ?: 0 } > Limits.get("message_chars")) {
                        releaseAttachments(listOf(item)); throw IllegalArgumentException("Текст вложений превышает глобальный лимит сообщения")
                    }
                    store.saveDraft(chatId, items)
                    mutable.update { it.copy(attachments = it.attachments + (chatId to items)) }
                } else releaseAttachments(listOf(item))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(errors = it.errors + (chatId to
                (if (e is IllegalArgumentException || e is IllegalStateException) e.message ?: "Не удалось прочитать вложение" else "Не удалось прочитать вложение. Проверь формат и доступ к файлу."))) } }
            finally { cleanup?.delete(); mutable.update { it.copy(importing = it.importing - chatId) } }
        }
    }
    private fun releaseAttachments(items: List<com.ayuemin.ilmar.attachments.ChatAttachment>) {
        val referenced = (mutable.value.chats.flatMap { it.messages }.flatMap { it.attachments } + mutable.value.attachments.values.flatten())
            .flatMap { listOf(it.id) + it.images }.toSet()
        items.flatMap { listOf(it.id) + it.images }.filterNot { it in referenced }.forEach { attachmentStore.file(it).delete() }
    }
    fun removeAttachment(chatId: Long, attachmentId: String) {
        val removed = mutable.value.attachments[chatId].orEmpty().filter { it.id == attachmentId }
        val items = mutable.value.attachments[chatId].orEmpty().filterNot { it.id == attachmentId }
        store.saveDraft(chatId, items)
        mutable.update { it.copy(attachments = it.attachments + (chatId to items)) }
        releaseAttachments(removed)
    }
    fun branch(chatId: Long, messageId: Long, askAgain: Boolean = false) {
        if (mutable.value.activeChatId != null) return
        val source = mutable.value.chats.firstOrNull { it.id == chatId } ?: return
        val message = source.messages.firstOrNull { it.id == messageId } ?: return
        if (askAgain) { askAgain(chatId, messageId); return }
        val newId = maxOf(System.currentTimeMillis(), (mutable.value.chats.maxOfOrNull { it.id } ?: 0) + 1)
        store.branch(chatId, newId, messageId, source.title.take(32) + " · ветка")
        memory.setRules(newId, memory.rules(chatId))
        val preferences = getApplication<Application>().getSharedPreferences("ilmar_chat_modes", Context.MODE_PRIVATE)
        val editor = preferences.edit()
        preferences.all.filterKeys { it.startsWith("${chatId}_") }.forEach { (key, value) ->
            val newKey = "${newId}_" + key.substringAfter('_')
            when (value) { is String -> editor.putString(newKey, value); is Boolean -> editor.putBoolean(newKey, value) }
        }; check(editor.commit())
        mutable.update { it.copy(chats = store.load(), selectedId = newId) }
    }
    /** Repeat an earlier question at the end of the same conversation; keep all existing replies. */
    fun askAgain(chatId: Long, messageId: Long) {
        if (mutable.value.activeChatId != null || chatId in mutable.value.importing) return
        val chat = mutable.value.chats.firstOrNull { it.id == chatId } ?: return
        val original = chat.messages.firstOrNull { it.id == messageId && it.fromUser } ?: return
        select(chatId)
        // An unanswered tail already represents this request: retry it without duplicating it.
        if (chat.messages.last().id != messageId) {
            val repeated = store.append(chatId, ChatMessage(original.text, true, attachments = original.attachments), null, clearDraft = false)
            mutable.update { state -> state.copy(chats = state.chats.map { if (it.id == chatId) it.copy(messages = it.messages + repeated) else it }) }
        }
        generate(chatId)
    }
    fun setDraft(id: Long, text: String) { mutable.update { it.copy(drafts = it.drafts + (id to text)) } }
    fun select(id: Long) { if (mutable.value.chats.any { it.id == id }) { mutable.update { it.copy(selectedId = id) }; indexArchive(id) } }
    fun create() {
        val id = maxOf(System.currentTimeMillis(), (mutable.value.chats.maxOfOrNull { it.id } ?: 0) + 1)
        store.create(id, "Новый чат")
        mutable.update { it.copy(chats = it.chats + ChatSession(id, "Новый чат"), selectedId = id) }
    }
    fun rename(id: Long, title: String) {
        if (title.isBlank()) return
        store.rename(id, title.trim())
        mutable.update { it.copy(chats = it.chats.map { chat -> if (chat.id == id) chat.copy(title = title.trim()) else chat }) }
    }
    fun clear(id: Long) {
        archive.cancel(id)
        val removed = mutable.value.chats.firstOrNull { it.id == id }?.messages.orEmpty().flatMap { it.attachments }
        if (mutable.value.activeChatId == id) stop()
        store.clearMessages(id)
        mutable.update { it.copy(chats = it.chats.map { chat -> if (chat.id == id) chat.copy(messages = emptyList()) else chat }, errors = it.errors - id) }
        releaseAttachments(removed)
    }
    fun delete(id: Long) {
        archive.cancel(id)
        val removed = mutable.value.chats.firstOrNull { it.id == id }?.messages.orEmpty().flatMap { it.attachments } + mutable.value.attachments[id].orEmpty()
        if (mutable.value.activeChatId == id) stop()
        store.delete(id)
        com.ayuemin.ilmar.tools.ToolDataStore(getApplication()).use { it.clear(id) }
        val remaining = mutable.value.chats.filterNot { it.id == id }
        mutable.update { it.copy(chats = remaining, selectedId = if (it.selectedId == id) remaining.firstOrNull()?.id ?: 0 else it.selectedId, errors = it.errors - id, drafts = it.drafts - id, attachments = it.attachments - id, contextStatus = it.contextStatus - id) }
        releaseAttachments(removed)
        if (remaining.isEmpty()) create()
    }
    fun send(text: String) {
        if ((text.isBlank() && mutable.value.attachments[mutable.value.selectedId].orEmpty().isEmpty()) || mutable.value.activeChatId != null || mutable.value.selectedId in mutable.value.importing) return
        val chat = mutable.value.chats.first { it.id == mutable.value.selectedId }
        val pending = ChatMessage(text.trim(), true, attachments = mutable.value.attachments[chat.id].orEmpty())
        val title = if (chat.messages.isEmpty()) text.trim().ifBlank { pending.attachments.first().name }.take(40) else null
        val message = store.append(chat.id, pending, title)
        mutable.update { it.copy(chats = it.chats.map { c -> if (c.id == chat.id) c.copy(title = title ?: c.title, messages = c.messages + message) else c }, attachments = it.attachments - chat.id, drafts = it.drafts - chat.id) }
        generate(chat.id)
    }
    fun resumeBrowserTask(chat: Long) {
        if (mutable.value.activeChatId != null || mutable.value.chats.none { it.id == chat }) return
        if (!com.ayuemin.ilmar.browser.BrowserRuntime.completeHandoff(chat)) return
        val message = store.append(chat, ChatMessage("Я завершил действия в браузере и возвращаю управление. Сначала прочитай текущую страницу и продолжи задачу; не повторяй уже выполненные действия.", true), null)
        mutable.update { it.copy(chats = it.chats.map { c -> if (c.id == chat) c.copy(messages = c.messages + message) else c }) }
        generate(chat)
    }
    fun retry() {
        val chat = mutable.value.chats.find { it.id == mutable.value.selectedId } ?: return
        if (mutable.value.activeChatId == null && (chat.messages.lastOrNull()?.fromUser == true || mutable.value.errors.containsKey(chat.id) && chat.messages.any { it.fromUser })) generate(chat.id)
    }
    private fun generate(chatId: Long, compactOnly: Boolean = false) {
        val target = try { registry.target(chatId) } catch (e: IllegalStateException) {
            mutable.update { it.copy(errors = it.errors + (chatId to (e.message ?: "Настрой подключение"))) }; return
        } catch (e: IllegalArgumentException) {
            mutable.update { it.copy(errors = it.errors + (chatId to (e.message ?: "Проверь подключение"))) }; return
        }
        val history = mutable.value.chats.first { it.id == chatId }.messages.toList()
        check(runtime.edit().putLong("active_chat", chatId).commit())
        mutable.update { it.copy(activeChatId = chatId, streamingText = "", errors = it.errors - chatId) }
        var cleanupSession: com.ayuemin.ilmar.agent.TaskSession? = null
        request = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val taskSession = memory.tasks.begin(chatId, history)
                cleanupSession = taskSession
                val contextPolicy = contextPreferences.load()
                runtime.edit().putString("active_task", taskSession.taskId).putString("active_run", taskSession.runId).putString("active_parent_run", taskSession.parentRunId).commit()
                diagnostics.record("START", chatId, taskSession.json().put("limits", org.json.JSONObject(Limits.fields.associate { it.key to Limits.get(it.key) })))
                val started = System.currentTimeMillis()
                val roundUsage = mutableListOf<UsageRecord>()
                val capabilities = registry.capabilities(target.model.id)
                val reasoning = capabilities.reasoningConfig(target.connection.endpoint,
                    modes.enabled(chatId, "Размышление"), modes.choice(chatId, "reasoning_effort").takeUnless { it == "auto" })
                val searchProtocol = com.ayuemin.ilmar.browser.ProviderSearch.resolve(
                    com.ayuemin.ilmar.browser.ProviderSearchPreferences(getApplication()).selected(target.connection.id), target.connection.endpoint, capabilities)
                val searchRoute = if (compactOnly) com.ayuemin.ilmar.browser.SearchRoute.OFF else com.ayuemin.ilmar.browser.SearchRouting.resolve(
                    modes.enabled(chatId, "Поиск"), com.ayuemin.ilmar.browser.SearchRouting.source(modes.choice(chatId, "search_source")), searchProtocol != null, capabilities.tools)
                val agentTools = if (modes.enabled(chatId, "Агент") && capabilities.tools)
                    ToolRegistry.authorized(chatId, modes, permissions) else emptyList()
                val tools = agentTools + if (searchRoute == com.ayuemin.ilmar.browser.SearchRoute.BROWSER)
                    com.ayuemin.ilmar.browser.BrowserTools.forChat(chatId, modes, searchProtocol != null, capabilities.tools, "image" in capabilities.inputModalities, getApplication(), taskSession.runId) else emptyList()
                diagnostics.record("SEARCH_ROUTE", chatId, taskSession.json().put("route", searchRoute.name)
                    .put("protocol", searchProtocol?.name ?: org.json.JSONObject.NULL))
                val compactId = contextPreferences.compactModelId
                val compactModel = registry.snapshot().models.find { it.id == compactId }
                val compactConnection = registry.snapshot().providers.find { it.id == compactModel?.providerId }
                // Metadata is enough to plan Compact. Resolve its secret only when a Compact call is needed.
                val compactTarget = if (compactModel != null && compactConnection != null)
                    com.ayuemin.ilmar.model.ModelTarget(compactConnection, compactModel, "") else target
                val compactCaps = registry.capabilities(compactTarget.model.id)
                val result = kotlinx.coroutines.withContext(contextDispatcher) {
                    com.ayuemin.ilmar.memory.ContextExecution(chatId, memory, attachmentStore, contextPolicy, capabilities, target,
                        status = { label -> mutable.update { it.copy(contextStatus = it.contextStatus + (chatId to label)) } },
                        compactTarget = compactTarget, compactCaps = compactCaps,
                        event = { code, details ->
                            diagnostics.record(code, chatId, details.put("task_id", taskSession.taskId).put("run_id", taskSession.runId).put("parent_run_id", taskSession.parentRunId ?: org.json.JSONObject.NULL))
                            if (code == "TOOL_ROUND" || code == "TOOL_ROUND_ERROR" || code == "TASK_CHECKPOINT") mutable.update { it.copy(chats = store.load(), taskRevision = it.taskRevision + (chatId to ((it.taskRevision[chatId] ?: 0) + 1))) }
                        },
                        semanticSearch = { query, through -> archive.search(chatId, query, through) }, session = taskSession,
                        observation = { com.ayuemin.ilmar.browser.BrowserVision.peek(taskSession.runId) },
                        consumeObservation = { com.ayuemin.ilmar.browser.BrowserVision.consume(taskSession.runId) },
                        request = { projectedHistory, options, purpose, estimatedInput, saved ->
                            if (purpose == "answer") mutable.update { it.copy(streamingText = "") }
                            val requestId = options.requestId ?: java.util.UUID.randomUUID().toString()
                            check(runtime.edit().putString("active_request", requestId).putString("active_purpose", purpose).commit())
                            try {
                                val callTarget = if (purpose == "compact" && compactId != null) {
                                    try { registry.targetModel(compactId) } catch (_: Exception) {
                                        throw com.ayuemin.ilmar.memory.ContextFailure("Модель Compact недоступна. Проверь её провайдера и ключ в настройках памяти.")
                                    }
                                } else target
                                val callCaps = if (purpose == "compact") compactCaps else capabilities
                                val callStarted = android.os.SystemClock.elapsedRealtime()
                                val callDetails = taskSession.json().put("request_id", requestId).put("purpose", purpose).put("model", callTarget.model.name)
                                    .put("provider", callTarget.connection.name).put("host", java.net.URI(callTarget.connection.endpoint).host)
                                    .put("estimated_input", estimatedInput).put("messages", projectedHistory.size).put("tools", options.tools.map { it.name }.joinToString())
                                    .put("working_window_tokens", contextPolicy.limit(callCaps)).put("physical_window_tokens", callCaps.contextLength ?: org.json.JSONObject.NULL)
                                    .put("reserved_output_tokens", options.maxOutput ?: org.json.JSONObject.NULL).put("output_parameter", options.outputParameter ?: org.json.JSONObject.NULL)
                                    .put("automatic_compact", contextPolicy.automatic).put("recent_messages_target", contextPolicy.recent)
                                    .put("compact_model", compactTarget.model.name).put("compact_window_tokens", contextPolicy.limit(compactCaps)).put("cache_requested", options.ephemeralCache)
                                    .put("estimated_saved_tokens", saved).put("context_policy", contextPolicy.diagnosticJson())
                                    .put("search_route", if (purpose == "answer") searchRoute.name else "OFF")
                                    .put("search_protocol", if (purpose == "answer" && searchRoute == com.ayuemin.ilmar.browser.SearchRoute.PROVIDER) searchProtocol?.name else org.json.JSONObject.NULL)
                                diagnostics.record("MODEL_REQUEST", chatId, callDetails)
                                val completion = provider.generate(callTarget.connection.endpoint, callTarget.model.name, callTarget.apiKey, projectedHistory,
                                    if (purpose == "answer" && searchRoute == com.ayuemin.ilmar.browser.SearchRoute.PROVIDER) options.copy(webSearch = searchProtocol!!.name) else options) { delta ->
                                    if (purpose == "answer") mutable.update { current -> if (current.activeChatId == chatId) current.copy(streamingText = current.streamingText + delta) else current }
                                }
                                diagnostics.record("MODEL_RESPONSE", chatId, callDetails.put("latency_ms", android.os.SystemClock.elapsedRealtime() - callStarted)
                                    .put("finish_reason", completion.finishReason).put("input_tokens", completion.usage.input).put("output_tokens", completion.usage.output)
                                    .put("cost_usd", completion.usage.costUsd ?: org.json.JSONObject.NULL).put("cached_tokens", completion.usage.cached ?: org.json.JSONObject.NULL)
                                    .put("reasoning_tokens", completion.usage.reasoning ?: org.json.JSONObject.NULL).put("usage", com.ayuemin.ilmar.model.usageJson(completion.usage))
                                    .put("tool_calls", completion.toolCalls.map { it.name }.joinToString()))
                                recordCall(chatId, requestId, completion.usage, "completed", purpose, saved, completion)
                                if (purpose == "answer") roundUsage += UsageRecord(completion.usage, "completed")
                                completion
                            } catch (e: CancellationException) {
                                recordCall(chatId, requestId, TokenUsage(), "cancelled", purpose); throw e
                            } catch (e: Exception) {
                                diagnostics.failure("MODEL_ERROR", chatId, e, taskSession.json().put("request_id", requestId).put("purpose", purpose).put("estimated_input", estimatedInput))
                                recordCall(chatId, requestId, (e as? ProviderFailure)?.reportedUsage ?: TokenUsage(), "failed", purpose); throw e
                            }
                        }).run(history, compactOnly, reasoning, tools)
                }
                if (result == null) return@launch
                val reply = result.text
                if (mutable.value.activeChatId == chatId && mutable.value.chats.any { it.id == chatId }) {
                    val ledger = UsageLedger(roundUsage)
                    val info = ReplyInfo(target.model.name, target.connection.name, System.currentTimeMillis(), System.currentTimeMillis() - started,
                        TokenUsage(ledger.input, ledger.output, ledger.total, ledger.cached, ledger.reasoning, ledger.costUsd), ledger.calls, roundUsage.any { it.usage.input == null || it.usage.output == null || it.usage.costUsd == null })
                    store.append(chatId, ChatMessage(reply, false, info = info), null)
                    mutable.update { it.copy(chats = it.chats.map { c -> if (c.id == chatId) c.copy(messages = store.load().first { it.id == chatId }.messages) else c }) }
                    diagnostics.record("SUCCESS", chatId)
                    indexArchive(chatId)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                diagnostics.failure("TASK_TIMEOUT", chatId, e)
                mutable.update { it.copy(errors = it.errors + (chatId to "Достигнут лимит времени задачи. Автоматический повтор не выполняется.")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: com.ayuemin.ilmar.tools.ClarificationRequired) {
                if (mutable.value.chats.any { it.id == chatId }) {
                    store.append(chatId, ChatMessage(e.question, false), null)
                    mutable.update { it.copy(contextStatus = it.contextStatus + (chatId to "Ожидается ответ пользователя")) }
                }
            } catch (e: Exception) {
                val code = when (e) {
                    is com.ayuemin.ilmar.agent.AgentFailure -> e.code
                    is com.ayuemin.ilmar.memory.ContextFailure -> "CONTEXT_ERROR"
                    is com.ayuemin.ilmar.attachments.AttachmentFailure -> "ATTACHMENT_ERROR"
                    is ProviderFailure -> "PROVIDER_ERROR"
                    else -> "REQUEST_ERROR"
                }
                diagnostics.failure(code, chatId, e, cleanupSession?.json() ?: org.json.JSONObject())
                mutable.update { it.copy(errors = it.errors + (chatId to ((e as? ProviderFailure)?.message ?: if (e is com.ayuemin.ilmar.memory.ContextFailure) e.message ?: "Не удалось подготовить контекст" else if (e is com.ayuemin.ilmar.attachments.AttachmentFailure) e.message ?: "Не удалось подготовить вложения" else if (e is com.ayuemin.ilmar.agent.AgentFailure) "Агент остановлен: ${e.message}"
                    else if (e is IllegalStateException || e is IllegalArgumentException) e.message ?: "Проверь параметры запроса; подробности в диагностике."
                    else "Не удалось завершить ответ. Проверь подключение и повтори запрос."))) }
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { com.ayuemin.ilmar.browser.BrowserRuntime.stop(chatId) }
                cleanupSession?.let { com.ayuemin.ilmar.browser.BrowserVision.consume(it.runId) }
                if (!closed) { cleanupSession?.let { memory.tasks.finish(it, "FAILED") }; memory.interrupt(chatId) }
                runtime.edit().remove("active_chat").remove("active_task").remove("active_run").remove("active_parent_run").commit()
                mutable.update { it.copy(chats = store.load(), activeChatId = null, streamingText = "") }
                request = null
            }
        }
        val job = request!!
        job.invokeOnCompletion {
            // Cancellation before the lazy body starts skips its finally block.
            if (!closed && request === job) {
                cleanupSession?.let { memory.tasks.finish(it, "PAUSED") }
                runtime.edit().remove("active_chat").remove("active_task").remove("active_run").remove("active_parent_run").commit()
                mutable.update { it.copy(chats = store.load(), activeChatId = null, streamingText = "") }
                request = null
            }
        }
        job.start()
    }
    fun stop() {
        val id = mutable.value.activeChatId ?: return
        request?.cancel()
        diagnostics.record("CANCELLED", id, activeDiagnostics())
        mutable.update { it.copy(errors = it.errors + (id to "Ответ остановлен. Можно повторить запрос.")) }
    }
    private fun activeDiagnostics() = org.json.JSONObject().put("task_id", runtime.getString("active_task", null) ?: org.json.JSONObject.NULL)
        .put("run_id", runtime.getString("active_run", null) ?: org.json.JSONObject.NULL).put("parent_run_id", runtime.getString("active_parent_run", null) ?: org.json.JSONObject.NULL)
        .put("request_id", runtime.getString("active_request", null) ?: org.json.JSONObject.NULL)
    private fun recordCall(chatId: Long, requestId: String, usage: TokenUsage, status: String, purpose: String = "answer", saved: Int = 0, completion: com.ayuemin.ilmar.model.GenerationResult? = null) {
        if (closed) return
        store.recordUsage(chatId, requestId, usage, status, purpose, saved, completion)
        runtime.edit().remove("active_request").commit()
    }
    override fun onCleared() {
        runtime.getString("active_request", null)?.let { id ->
            mutable.value.activeChatId?.let { store.recordUsage(it, id, TokenUsage(), "cancelled", runtime.getString("active_purpose", "answer") ?: "answer") }
        }
        closed = true
        val active = request
        active?.cancel()
        if (active?.isCompleted == false) active.invokeOnCompletion { store.close() } else store.close()
        super.onCleared()
    }
}
