package com.ayuemin.ilmar.browser

import androidx.compose.material3.*
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class BrowserPrompt(val id: String, val chat: Long, val title: String, val message: String, val confirmLabel: String = "Разрешить")

/** One live request, identified so a stale UI callback cannot approve the next request. */
internal object BrowserPrompts {
    private val mutable = MutableStateFlow<BrowserPrompt?>(null)
    val pending = mutable.asStateFlow()
    private data class Request(val prompt: BrowserPrompt, val answer: CompletableDeferred<Boolean>)
    private val mutex = Mutex()
    @Volatile private var active: Request? = null
    suspend fun ask(chat: Long, title: String, message: String): Boolean = askLabel(chat, title, message, "Разрешить")
    suspend fun askDownload(chat: Long, title: String, message: String): Boolean = askLabel(chat, title, message, "Скачать")
    private suspend fun askLabel(chat: Long, title: String, message: String, label: String): Boolean = mutex.withLock {
        val prompt = BrowserPrompt(java.util.UUID.randomUUID().toString(), chat, title, message, label)
        val deferred = CompletableDeferred<Boolean>()
        val request = Request(prompt, deferred)
        active = request; mutable.value = prompt
        try { deferred.await() } finally {
            if (active === request) { active = null; mutable.value = null }
        }
    }
    fun respond(id: String, allow: Boolean) { active?.takeIf { it.prompt.id == id }?.answer?.complete(allow) }
}

@Composable
internal fun BrowserPromptDialog(chat: Long) {
    val prompt by BrowserPrompts.pending.collectAsState()
    prompt?.takeIf { it.chat == chat }?.let { request ->
        AlertDialog(onDismissRequest = { BrowserPrompts.respond(request.id, false) },
            title = { Text(request.title) }, text = { Text(request.message, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { BrowserPrompts.respond(request.id, true) }) { Text(request.confirmLabel) } },
            dismissButton = { TextButton(onClick = { BrowserPrompts.respond(request.id, false) }) { Text("Отмена") } })
    }
}
