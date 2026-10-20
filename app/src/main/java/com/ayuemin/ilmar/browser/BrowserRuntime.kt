package com.ayuemin.ilmar.browser

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal interface BrowserPage {
    suspend fun open(url: String): JSONObject
    suspend fun read(): JSONObject
    suspend fun scroll(down: Boolean): JSONObject
    fun stop()
    fun resume() = Unit
}
internal data class BrowserUi(val chat: Long? = null, val url: String = "", val status: String = "", val visible: Boolean = false)

/** A foreground read-only browser. Commands cannot read another chat's page. */
internal object BrowserRuntime {
    private var page: BrowserPage? = null
    private val lock = Mutex()
    private val mutable = MutableStateFlow(BrowserUi())
    val state = mutable.asStateFlow()
    fun attach(value: BrowserPage) { page = value }
    fun detach(value: BrowserPage) { if (page === value) { page = null; value.stop(); mutable.value = BrowserUi() } }
    fun show(chat: Long) { if (mutable.value.chat == chat) { page?.resume(); mutable.value = mutable.value.copy(visible = true) } }
    fun hide() { mutable.value = mutable.value.copy(visible = false); if (!lock.isLocked) page?.stop() }
    suspend fun command(chat: Long, operation: String, argument: String = ""): String = lock.withLock {
        withContext(Dispatchers.Main.immediate) {
            val browser = page ?: error("Браузер ещё не готов. Открой экран чата и повтори запрос.")
            if (operation != "open" && operation != "search") require(mutable.value.chat == chat) { "Сначала открой страницу для текущего чата" }
            val url = when (operation) { "search" -> BrowserAddress.search(argument); "open" -> argument; else -> null }
            if (url != null) BrowserAddress.parse(url)
            mutable.value = BrowserUi(chat, url ?: mutable.value.url, "Загрузка…", mutable.value.visible && mutable.value.chat == chat)
            try {
                val result = withTimeout(25000) {
                    when (operation) {
                        "open", "search" -> browser.open(url!!)
                        "read" -> browser.read()
                        "scroll" -> { require(argument in setOf("up", "down")); browser.scroll(argument == "down") }
                        else -> error("Неизвестная команда браузера")
                    }
                }
                mutable.value = mutable.value.copy(url = result.getString("url"), status = result.optString("title").ifBlank { "Страница прочитана" })
                result.toString()
            } catch (e: Exception) {
                browser.stop(); mutable.value = mutable.value.copy(status = if (e is CancellationException) "Остановлено" else "Ошибка страницы")
                throw e
            }
        }
    }
    suspend fun stop(chat: Long) = withContext(Dispatchers.Main.immediate) {
        if (mutable.value.chat == chat) page?.stop()
    }
}
