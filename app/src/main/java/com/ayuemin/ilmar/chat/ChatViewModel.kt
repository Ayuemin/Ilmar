package com.ayuemin.ilmar.chat

import android.app.Application
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
    val errors: Map<Long, String> = emptyMap(), val drafts: Map<Long, String> = emptyMap()
)

/** Activity scoped: independent of screen composition, settings navigation and rotation. */
class ChatViewModel @JvmOverloads constructor(
    application: Application,
    private val provider: ChatProvider = OpenAiCompatibleProvider(),
    val registry: ProviderRegistry = ProviderRegistry(application)
) : AndroidViewModel(application) {
    private val store = ChatStore(application)
    private val attachmentStore = com.ayuemin.ilmar.attachments.AttachmentStore(application)
    private val modes = ChatModePreferences(application)
    private val permissions = ToolPermissionBroker(application)
    private val diagnostics = Diagnostics(application)
    private val runtime = application.getSharedPreferences("ilmar_requests", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(ChatState())
    val state = mutable.asStateFlow()
    private var request: Job? = null
    private var closed = false

    init {
        val chats = store.load().ifEmpty {
            store.create(1L, "Новый чат"); listOf(ChatSession(1L, "Новый чат"))
        }
        val interrupted = runtime.getLong("active_chat", -1)
        runtime.getString("active_request", null)?.let { pending ->
            if (interrupted >= 0) store.recordUsage(interrupted, pending, TokenUsage(), "interrupted")
        }
        runtime.edit().remove("active_request").commit()
        val errors = if (chats.any { it.id == interrupted && it.messages.lastOrNull()?.fromUser == true }) {
            diagnostics.record("INTERRUPTED", interrupted)
            mapOf(interrupted to "Ответ прерван при закрытии приложения. Можно повторить запрос.")
        } else emptyMap()
        runtime.edit().remove("active_chat").commit()
        mutable.value = ChatState(chats, chats.first().id, errors = errors, attachments = store.drafts())
        val referenced = (chats.flatMap { it.messages }.flatMap { it.attachments } + mutable.value.attachments.values.flatten())
            .flatMap { listOf(it.id) + it.images }.toSet()
        java.io.File(application.filesDir, "attachments").listFiles()?.filter { it.name !in referenced }?.forEach { it.delete() }
    }

    fun usage(chatId: Long) = store.usage(chatId)
    fun importAttachment(chatId: Long, uri: android.net.Uri, cleanup: java.io.File? = null) {
        if (chatId in mutable.value.importing || mutable.value.chats.none { it.id == chatId }) { cleanup?.delete(); return }
        mutable.update { it.copy(importing = it.importing + chatId, errors = it.errors - chatId) }
        viewModelScope.launch {
            try {
                require(mutable.value.attachments[chatId].orEmpty().size < 8) { "Не больше 8 вложений в одном сообщении" }
                val item = attachmentStore.import(uri)
                if (mutable.value.chats.any { it.id == chatId }) {
                    val items = mutable.value.attachments[chatId].orEmpty() + item
                    if (items.sumOf { it.text?.length ?: 0 } > 128 * 1024) {
                        releaseAttachments(listOf(item)); throw IllegalArgumentException("Общий текст вложений одного сообщения: не больше 128 тысяч символов")
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
        if (askAgain && !message.fromUser) return
        if (askAgain && source.messages.last().id == messageId) { select(chatId); generate(chatId); return }
        val newId = maxOf(System.currentTimeMillis(), (mutable.value.chats.maxOfOrNull { it.id } ?: 0) + 1)
        store.branch(chatId, newId, messageId, source.title.take(32) + " · ветка")
        val preferences = getApplication<Application>().getSharedPreferences("ilmar_chat_modes", Context.MODE_PRIVATE)
        val editor = preferences.edit()
        preferences.all.filterKeys { it.startsWith("${chatId}_") }.forEach { (key, value) ->
            val newKey = "${newId}_" + key.substringAfter('_')
            when (value) { is String -> editor.putString(newKey, value); is Boolean -> editor.putBoolean(newKey, value) }
        }; check(editor.commit())
        mutable.update { it.copy(chats = store.load(), selectedId = newId) }
        if (askAgain) generate(newId)
    }
    fun setDraft(id: Long, text: String) { mutable.update { it.copy(drafts = it.drafts + (id to text)) } }
    fun select(id: Long) { if (mutable.value.chats.any { it.id == id }) mutable.update { it.copy(selectedId = id) } }
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
        val removed = mutable.value.chats.firstOrNull { it.id == id }?.messages.orEmpty().flatMap { it.attachments }
        if (mutable.value.activeChatId == id) stop()
        store.clearMessages(id)
        mutable.update { it.copy(chats = it.chats.map { chat -> if (chat.id == id) chat.copy(messages = emptyList()) else chat }, errors = it.errors - id) }
        releaseAttachments(removed)
    }
    fun delete(id: Long) {
        val removed = mutable.value.chats.firstOrNull { it.id == id }?.messages.orEmpty().flatMap { it.attachments } + mutable.value.attachments[id].orEmpty()
        if (mutable.value.activeChatId == id) stop()
        store.delete(id)
        val remaining = mutable.value.chats.filterNot { it.id == id }
        mutable.update { it.copy(chats = remaining, selectedId = if (it.selectedId == id) remaining.firstOrNull()?.id ?: 0 else it.selectedId, errors = it.errors - id, drafts = it.drafts - id, attachments = it.attachments - id) }
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
    fun retry() {
        val chat = mutable.value.chats.find { it.id == mutable.value.selectedId } ?: return
        if (mutable.value.activeChatId == null && chat.messages.lastOrNull()?.fromUser == true) generate(chat.id)
    }
    private fun generate(chatId: Long) {
        val target = try { registry.target(chatId) } catch (e: IllegalStateException) {
            mutable.update { it.copy(errors = it.errors + (chatId to (e.message ?: "Настрой подключение"))) }; return
        } catch (e: IllegalArgumentException) {
            mutable.update { it.copy(errors = it.errors + (chatId to (e.message ?: "Проверь подключение"))) }; return
        }
        val history = mutable.value.chats.first { it.id == chatId }.messages.toList()
        check(runtime.edit().putLong("active_chat", chatId).commit())
        mutable.update { it.copy(activeChatId = chatId, streamingText = "", errors = it.errors - chatId) }
        diagnostics.record("START", chatId)
        request = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val started = System.currentTimeMillis()
                val roundUsage = mutableListOf<UsageRecord>()
                val capabilities = registry.capabilities(target.model.id)
                val content = if (history.none { it.attachments.isNotEmpty() }) emptyMap() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.ayuemin.ilmar.attachments.AttachmentPayload.build(history, capabilities, attachmentStore)
                }
                val reasoning = capabilities.reasoningConfig(target.connection.endpoint,
                    modes.enabled(chatId, "Размышление"), modes.choice(chatId, "reasoning_effort").takeUnless { it == "auto" })
                val tools = if (modes.enabled(chatId, "Агент") && capabilities.tools)
                    ToolRegistry.authorized(chatId, modes, permissions) else emptyList()
                val result = AgentLoopRunner().run(tools) { toolHistory, schemas ->
                    mutable.update { it.copy(streamingText = "") }
                    val requestId = java.util.UUID.randomUUID().toString()
                    check(runtime.edit().putString("active_request", requestId).commit())
                    try {
                        val completion = provider.generate(target.connection.endpoint, target.model.name, target.apiKey, history,
                            GenerationOptions(reasoning, schemas, toolHistory, capabilities.includeUsage(target.connection.endpoint), content)) { delta ->
                            mutable.update { current -> if (current.activeChatId == chatId) current.copy(streamingText = current.streamingText + delta) else current }
                        }
                        recordCall(chatId, requestId, completion.usage, "completed")
                        roundUsage += UsageRecord(completion.usage, "completed")
                        completion
                    } catch (e: CancellationException) {
                        recordCall(chatId, requestId, TokenUsage(), "cancelled"); throw e
                    } catch (e: Exception) {
                        recordCall(chatId, requestId, TokenUsage(), "failed"); throw e
                    }
                }
                val reply = result.text
                if (mutable.value.activeChatId == chatId && mutable.value.chats.any { it.id == chatId }) {
                    val ledger = UsageLedger(roundUsage)
                    val info = ReplyInfo(target.model.name, target.connection.name, System.currentTimeMillis(), System.currentTimeMillis() - started,
                        TokenUsage(ledger.input, ledger.output, ledger.total, ledger.cached, ledger.reasoning, ledger.costUsd), ledger.calls, roundUsage.any { it.usage.input == null || it.usage.output == null || it.usage.costUsd == null })
                    val saved = store.append(chatId, ChatMessage(reply, false, info = info), null)
                    mutable.update { it.copy(chats = it.chats.map { c -> if (c.id == chatId) c.copy(messages = c.messages + saved) else c }) }
                    diagnostics.record("SUCCESS", chatId)
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                mutable.update { it.copy(errors = it.errors + (chatId to "Достигнут лимит времени задачи. Автоматический повтор не выполняется.")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnostics.record("NETWORK_ERROR", chatId)
                mutable.update { it.copy(errors = it.errors + (chatId to ((e as? ProviderFailure)?.message ?: if (e is com.ayuemin.ilmar.attachments.AttachmentFailure) e.message ?: "Не удалось подготовить вложения" else if (e is IllegalStateException || e is IllegalArgumentException)
                    "Агент остановлен: лимит, повторяющиеся вызовы или недоступный инструмент. Проверь режим и разрешения."
                    else "Не удалось завершить ответ. Проверь подключение и повтори запрос."))) }
            } finally {
                runtime.edit().remove("active_chat").commit()
                mutable.update { it.copy(activeChatId = null, streamingText = "") }
                request = null
            }
        }
        request?.start()
    }
    fun stop() {
        val id = mutable.value.activeChatId ?: return
        request?.cancel()
        diagnostics.record("CANCELLED", id)
        mutable.update { it.copy(errors = it.errors + (id to "Ответ остановлен. Можно повторить запрос.")) }
    }
    private fun recordCall(chatId: Long, requestId: String, usage: TokenUsage, status: String) {
        if (closed) return
        store.recordUsage(chatId, requestId, usage, status)
        runtime.edit().remove("active_request").commit()
    }
    override fun onCleared() {
        runtime.getString("active_request", null)?.let { id ->
            mutable.value.activeChatId?.let { store.recordUsage(it, id, TokenUsage(), "cancelled") }
        }
        closed = true; store.close(); super.onCleared()
    }
}
