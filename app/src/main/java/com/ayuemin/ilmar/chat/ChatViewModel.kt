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
    val errors: Map<Long, String> = emptyMap()
)

/** Activity scoped: independent of screen composition, settings navigation and rotation. */
class ChatViewModel @JvmOverloads constructor(
    application: Application,
    private val provider: ChatProvider = OpenAiCompatibleProvider(),
    val registry: ProviderRegistry = ProviderRegistry(application)
) : AndroidViewModel(application) {
    private val store = ChatStore(application)
    private val diagnostics = Diagnostics(application)
    private val runtime = application.getSharedPreferences("ilmar_requests", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(ChatState())
    val state = mutable.asStateFlow()
    private var request: Job? = null

    init {
        val chats = store.load().ifEmpty {
            store.create(1L, "Новый чат"); listOf(ChatSession(1L, "Новый чат"))
        }
        val interrupted = runtime.getLong("active_chat", -1)
        val errors = if (chats.any { it.id == interrupted && it.messages.lastOrNull()?.fromUser == true }) {
            diagnostics.record("INTERRUPTED", interrupted)
            mapOf(interrupted to "Ответ прерван при закрытии приложения. Можно повторить запрос.")
        } else emptyMap()
        runtime.edit().remove("active_chat").commit()
        mutable.value = ChatState(chats, chats.first().id, errors = errors)
    }

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
        if (mutable.value.activeChatId == id) stop()
        store.clearMessages(id)
        mutable.update { it.copy(chats = it.chats.map { chat -> if (chat.id == id) chat.copy(messages = emptyList()) else chat }, errors = it.errors - id) }
    }
    fun delete(id: Long) {
        if (mutable.value.activeChatId == id) stop()
        store.delete(id)
        val remaining = mutable.value.chats.filterNot { it.id == id }
        mutable.update { it.copy(chats = remaining, selectedId = if (it.selectedId == id) remaining.firstOrNull()?.id ?: 0 else it.selectedId, errors = it.errors - id) }
        if (remaining.isEmpty()) create()
    }
    fun send(text: String) {
        if (text.isBlank() || mutable.value.activeChatId != null) return
        val chat = mutable.value.chats.first { it.id == mutable.value.selectedId }
        val message = ChatMessage(text.trim(), true)
        val title = if (chat.messages.isEmpty()) text.trim().take(40) else null
        store.append(chat.id, message, title)
        mutable.update { it.copy(chats = it.chats.map { c -> if (c.id == chat.id) c.copy(title = title ?: c.title, messages = c.messages + message) else c }) }
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
                val reply = provider.stream(target.connection.endpoint, target.model.name, target.apiKey, history) { delta ->
                    mutable.update { current ->
                        if (current.activeChatId == chatId) current.copy(streamingText = current.streamingText + delta) else current
                    }
                }
                if (mutable.value.activeChatId == chatId && mutable.value.chats.any { it.id == chatId }) {
                    store.append(chatId, ChatMessage(reply, false), null)
                    mutable.update { it.copy(chats = it.chats.map { c -> if (c.id == chatId) c.copy(messages = c.messages + ChatMessage(reply, false)) else c }) }
                    diagnostics.record("SUCCESS", chatId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnostics.record("NETWORK_ERROR", chatId)
                mutable.update { it.copy(errors = it.errors + (chatId to ((e as? ProviderFailure)?.message ?: "Не удалось завершить ответ. Проверь подключение и повтори запрос."))) }
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
    override fun onCleared() { store.close(); super.onCleared() }
}
