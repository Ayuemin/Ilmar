package com.ayuemin.ilmar.browser

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

/** Android adapter kept separate from routing, tool schemas and Compose. Based on Umnik's WebView host. */
@SuppressLint("SetJavaScriptEnabled")
internal class AndroidBrowserPage(private val view: WebView) : BrowserPage {
    private var policy = BrowserPolicy()
    override fun configure(policy: BrowserPolicy) { this.policy = policy }
    private val sessions = mutableMapOf<Long, android.os.Bundle>()
    private var sessionChat: Long? = null
    private var requestedChat: Long? = null
    private var clearHistoryAfterOpen = false
    private var downloadHint: JSONObject? = null
    override fun activate(chat: Long) {
        requestedChat = chat
        if (sessionChat == chat) return
        stop()
        sessionChat?.let { owner -> sessions[owner] = android.os.Bundle().also { view.saveState(it) } }
        sessionChat = null
        val saved = sessions[chat]
        clearHistoryAfterOpen = saved == null || view.restoreState(saved) == null
        if (!clearHistoryAfterOpen) sessionChat = chat
    }
    private var pending: CompletableDeferred<Unit>? = null
    private var navigationUrl: String? = null
    init {
        view.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            setGeolocationEnabled(false)
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
            override fun onShowFileChooser(webView: WebView, callback: android.webkit.ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean {
                callback.onReceiveValue(null); return true
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !BrowserAddress.valid(request.url.toString())
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Same per-resource public-host policy as Umnik. No local/file/content/custom schemes.
                return try {
                    com.ayuemin.ilmar.downloads.UserDownloadStore(view.context).requireModelReadable(request.url.toString())
                    BrowserAddress.publicHost(request.url.toString()); null
                }
                catch (_: Exception) {
                    if (request.isForMainFrame) view.post { pending?.completeExceptionally(IllegalStateException("Адрес страницы запрещён")) }
                    WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream("Blocked".toByteArray()))
                }
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (pending == null) pending = CompletableDeferred()
                navigationUrl = url
                url?.let(BrowserRuntime::navigated)
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if (url == navigationUrl && url == view.url) pending?.complete(Unit)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) pending?.completeExceptionally(IllegalStateException("Ошибка загрузки страницы (${error.errorCode})"))
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) pending?.completeExceptionally(IllegalStateException("Страница: HTTP ${response.statusCode}. Можно открыть браузер вручную."))
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel(); pending?.completeExceptionally(IllegalStateException("Ошибка сертификата страницы"))
            }
            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                handler.cancel(); pending?.completeExceptionally(IllegalStateException("HTTP-аутентификация страницы не поддерживается"))
            }
        }
        view.setDownloadListener { url, _, disposition, mime, _ ->
            val supported = BrowserAddress.valid(url)
            downloadHint = JSONObject().put("download_available", supported).apply { if (supported) put("url", url) }
                .put("name", if (supported) com.ayuemin.ilmar.downloads.UserDownloadStore.safeName(URLUtil.guessFileName(url, disposition, mime)) else "Файл сайта")
                .put("status", if (supported) "not_downloaded" else "download_source_unsupported").put("user_only", true).put("untrusted", true)
                .put("notice", "Сайт предложил файл. Скачивать только по просьбе пользователя через browser_download с подтверждением. Содержимое не читать и не отправлять модели.")
            pending?.complete(Unit)
        }
    }
    override suspend fun open(url: String): JSONObject {
        com.ayuemin.ilmar.downloads.UserDownloadStore(view.context).requireModelReadable(url)
        withContext(Dispatchers.IO) { BrowserAddress.publicHost(url) }
        stop()
        downloadHint = null
        val navigation = CompletableDeferred<Unit>(); pending = navigation; navigationUrl = url
        resume()
        try {
            view.loadUrl(url); navigation.await(); delay(300)
            downloadHint?.let { return JSONObject(it.toString()) }
            if (clearHistoryAfterOpen) { view.clearHistory(); clearHistoryAfterOpen = false }
            sessionChat = requestedChat
            return read()
        }
        finally { if (pending === navigation) pending = null }
    }
    override suspend fun find(query: String, exact: Boolean): JSONObject {
        resume()
        require(BrowserAddress.valid(view.url.orEmpty())) { "Нет открытой HTTPS-страницы" }
        com.ayuemin.ilmar.downloads.UserDownloadStore(view.context).requireModelReadable(view.url.orEmpty())
        val result = JSONObject(org.json.JSONTokener(evaluate(BrowserFind.script(query, exact, policy))).nextValue() as String)
        require(result.optString("url") == view.url) { "Страница изменилась во время поиска" }
        return result
    }
    override suspend fun read(): JSONObject = snapshot(false)
    override suspend fun readFull(): JSONObject = snapshot(true)
    private suspend fun snapshot(full: Boolean): JSONObject {
        resume()
        require(BrowserAddress.valid(view.url.orEmpty())) { "Нет открытой HTTPS-страницы" }
        com.ayuemin.ilmar.downloads.UserDownloadStore(view.context).requireModelReadable(view.url.orEmpty())
        val evaluation = suspendCancellableCoroutine<String> { continuation ->
            view.evaluateJavascript(BrowserSnapshot.script(full, policy)) { value -> if (continuation.isActive) continuation.resume(value) }
        }
        return BrowserSnapshot.decode(evaluation, full, policy).also {
            require(it.getString("url") == view.url) { "Страница изменилась при чтении. Повтори browser_read." }
            downloadHint?.let { hint -> it.put("download", JSONObject(hint.toString())) }
        }
    }
    override suspend fun scroll(down: Boolean): JSONObject {
        view.scrollBy(0, (view.height.coerceAtLeast(800) * if (down) 0.8 else -0.8).toInt())
        delay(200); return read()
    }
    private suspend fun evaluate(script: String): String = suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript(script) { value -> if (continuation.isActive) continuation.resume(value) }
    }
    override suspend fun act(command: String, args: JSONObject): JSONObject {
        resume()
        com.ayuemin.ilmar.downloads.UserDownloadStore(view.context).requireModelReadable(view.url.orEmpty())
        downloadHint = null
        if (pending?.isCompleted == true) pending = null
        val action = JSONObject(org.json.JSONTokener(evaluate(BrowserActions.script(command, args))).nextValue() as String)
        if (action.has("error")) return action
        // A failed read after a click throws; the durable journal retains UNKNOWN instead of repeating the click.
        delay(500)
        pending?.await()
        downloadHint?.let { return JSONObject(it.toString()).put("browser_action", action) }
        return read().put("browser_action", action)
    }
    override suspend fun back(): JSONObject {
        require(view.canGoBack()) { "Нет предыдущей страницы" }
        if (pending?.isCompleted == true) pending = null
        resume(); view.goBack(); delay(500); pending?.await(); return read()
    }
    override suspend fun screenshot(): ByteArray {
        require(view.width > 0 && view.height > 0) { "Браузер не получил размер экрана" }
        suspendCancellableCoroutine<Unit> { continuation ->
            view.postVisualStateCallback(android.os.SystemClock.uptimeMillis(), object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { if (continuation.isActive) continuation.resume(Unit) }
            })
        }
        require(!read().optBoolean("requires_user")) { "На странице есть пароль или CAPTCHA; снимок отменён" }
        val scale = minOf(1f, 1600f / maxOf(view.width, view.height))
        val bitmap = android.graphics.Bitmap.createBitmap((view.width * scale).toInt().coerceAtLeast(1), (view.height * scale).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(bitmap); canvas.scale(scale, scale); view.draw(canvas)
            return java.io.ByteArrayOutputStream().also { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }
    override fun saveSession() { CookieManager.getInstance().flush() }
    override fun downloadHeaders(url: String): Map<String, String> {
        val current = runCatching { BrowserAddress.parse(view.url.orEmpty()) }.getOrNull() ?: return emptyMap()
        val target = BrowserAddress.parse(url)
        if (current.host != target.host || (current.port.takeIf { it >= 0 } ?: 443) != (target.port.takeIf { it >= 0 } ?: 443)) return emptyMap()
        return buildMap {
            put("User-Agent", view.settings.userAgentString)
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
        }
    }
    override fun resume() { view.onResume(); view.resumeTimers() }
    override fun stop() { pending?.cancel(); pending = null; navigationUrl = null; view.stopLoading(); view.onPause(); view.pauseTimers() }
}
