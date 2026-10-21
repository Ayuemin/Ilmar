package com.ayuemin.ilmar.browser

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal interface BrowserPage {
    suspend fun open(url: String): JSONObject
    suspend fun readFull(): JSONObject = read()
    suspend fun read(): JSONObject
    suspend fun scroll(down: Boolean): JSONObject
    suspend fun act(command: String, args: JSONObject): JSONObject = error("Действия браузера не подключены")
    suspend fun back(): JSONObject = error("Назад недоступно")
    suspend fun screenshot(): ByteArray = error("Снимок браузера недоступен")
    fun stop()
    fun resume() = Unit
    fun saveSession() = Unit
}
internal data class BrowserFrame(val bytes: ByteArray, val url: String, val pageId: String, val capturedAt: Long)
internal data class BrowserUi(val chat: Long? = null, val url: String = "", val status: String = "", val visible: Boolean = false, val waitingUser: Boolean = false, val pageId: String = "")

/** A foreground browser with an exclusive human-control gate. Commands cannot read another chat's page. */
internal object BrowserRuntime {
    private var page: BrowserPage? = null
    private val lock = Mutex()
    private val observations = BrowserObservations()
    private var lastObservationChat: Long? = null
    private val mutable = MutableStateFlow(BrowserUi())
    val state = mutable.asStateFlow()
    fun attach(value: BrowserPage) { page = value }
    fun detach(value: BrowserPage) { if (page === value) { page = null; value.stop(); mutable.value = BrowserUi(); observations.clear(); lastObservationChat = null } }
    fun show(chat: Long) { if (mutable.value.chat == chat) { page?.resume(); mutable.value = mutable.value.copy(visible = true, waitingUser = true) } }
    fun navigated(url: String) { if (mutable.value.chat != null) mutable.value = mutable.value.copy(url = url) }
    fun hide() { mutable.value = mutable.value.copy(visible = false); if (!lock.isLocked) page?.stop() }
    fun completeHandoff(chat: Long): Boolean {
        if (mutable.value.chat != chat || !mutable.value.waitingUser) return false
        mutable.value = mutable.value.copy(waitingUser = false, visible = false, status = "Управление возвращено модели")
        observations.clear()
        page?.saveSession()
        return true
    }
    private fun awaitingUser() = JSONObject().put("error", "USER_REQUIRED").put("action_performed", false)
        .put("_awaiting_user", true).put("message", "Продолжи в открытом браузере: CAPTCHA, пароль или подтверждение вводятся только на странице. Затем нажми «Готово, вернуть модели».")
    suspend fun command(chat: Long, operation: String, argument: String = "", details: JSONObject = JSONObject()): String = lock.withLock {
        withContext(Dispatchers.Main.immediate) {
            val browser = page ?: error("Браузер ещё не готов. Открой экран чата и повтори запрос.")
            if (operation != "open" && operation != "search") require(mutable.value.chat == chat) { "Сначала открой страницу для текущего чата" }
            if (mutable.value.waitingUser) {
                require(mutable.value.chat == chat) { "Браузером управляет пользователь другого чата" }
                return@withContext awaitingUser().toString()
            }
            if (operation == "takeover") {
                show(chat); return@withContext awaitingUser().toString()
            }
            val url = when (operation) { "search" -> BrowserAddress.search(argument); "open" -> argument; else -> null }
            if (url != null) BrowserAddress.parse(url)
            if (lastObservationChat != chat || operation == "open" || operation == "search") { observations.clear(); lastObservationChat = chat }
            mutable.value = BrowserUi(chat, url ?: mutable.value.url, "Загрузка…", mutable.value.visible && mutable.value.chat == chat)
            try {
                val result = withTimeout(25000) {
                    when (operation) {
                        "open", "search" -> browser.open(url!!)
                        "read" -> if (details.optBoolean("full")) browser.readFull() else browser.read()
                        "back" -> browser.back()
                        "click", "type", "select" -> browser.act(operation, details)
                        "scroll" -> { require(argument in setOf("up", "down")); browser.scroll(argument == "down") }
                        else -> error("Неизвестная команда браузера")
                    }
                }
                if (result.optBoolean("_awaiting_user") || result.optBoolean("requires_user")) {
                    show(chat)
                    return@withContext awaitingUser().toString()
                }
                mutable.value = mutable.value.copy(pageId = result.optString("page_id", mutable.value.pageId), url = result.optString("url", mutable.value.url), status = result.optString("title").ifBlank { "Страница прочитана" })
                observations.encode(result, force = operation in setOf("open", "search", "read"), full = operation == "read" && details.optBoolean("full")).toString()
            } catch (e: Exception) {
                browser.stop(); mutable.value = mutable.value.copy(status = if (e is CancellationException) "Остановлено" else "Ошибка страницы")
                throw e
            }
        }
    }
    suspend fun screenshot(chat: Long): ByteArray = captureFrame(chat).bytes
    suspend fun captureFrame(chat: Long): BrowserFrame = lock.withLock {
        withContext(Dispatchers.Main.immediate) {
            require(mutable.value.chat == chat && !mutable.value.waitingUser) { "Снимок недоступен: браузер другого чата или управление у пользователя" }
            val browser = page ?: error("Браузер не готов")
            val state = browser.read()
            mutable.value = mutable.value.copy(pageId = state.optString("page_id"))
            require(!state.optBoolean("requires_user")) { "Нельзя снимать страницу ввода пароля или CAPTCHA; передай её пользователю" }
            val bytes = withTimeout(5000) { browser.screenshot() }
            require(!mutable.value.waitingUser && mutable.value.chat == chat) { "Снимок отменён: управление передано пользователю" }
            require(browser.read().optString("page_id") == state.optString("page_id")) { "Страница изменилась во время снимка" }
            BrowserFrame(bytes, state.optString("url"), state.optString("page_id"), System.currentTimeMillis())
        }
    }
    suspend fun stop(chat: Long) = withContext(Dispatchers.Main.immediate) {
        if (mutable.value.chat == chat && !mutable.value.waitingUser) page?.stop()
    }
}
