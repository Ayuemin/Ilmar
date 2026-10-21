package com.ayuemin.ilmar.browser

import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class BrowserPrompt(val id: String, val chat: Long, val title: String, val message: String)

/** One live request, identified so a stale UI callback cannot approve the next request. */
internal object BrowserPrompts {
    private val mutable = MutableStateFlow<BrowserPrompt?>(null)
    val pending = mutable.asStateFlow()
    private var answer: CompletableDeferred<Boolean>? = null
    suspend fun ask(chat: Long, title: String, message: String): Boolean {
        check(answer == null) { "Уже ожидается подтверждение браузера" }
        val prompt = BrowserPrompt(java.util.UUID.randomUUID().toString(), chat, title, message)
        val deferred = CompletableDeferred<Boolean>()
        answer = deferred; mutable.value = prompt
        return try { deferred.await() } finally {
            if (mutable.value?.id == prompt.id) { mutable.value = null; answer = null }
        }
    }
    fun respond(id: String, allow: Boolean) { if (mutable.value?.id == id) answer?.complete(allow) }
}

@Composable
internal fun BrowserPromptDialog(chat: Long) {
    val prompt by BrowserPrompts.pending.collectAsState()
    prompt?.takeIf { it.chat == chat }?.let { request ->
        AlertDialog(onDismissRequest = { BrowserPrompts.respond(request.id, false) },
            title = { Text(request.title) }, text = { Text(request.message) },
            confirmButton = { TextButton(onClick = { BrowserPrompts.respond(request.id, true) }) { Text("Разрешить") } },
            dismissButton = { TextButton(onClick = { BrowserPrompts.respond(request.id, false) }) { Text("Отмена") } })
    }
}
