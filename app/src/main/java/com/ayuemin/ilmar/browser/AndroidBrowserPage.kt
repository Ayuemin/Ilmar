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
                return try { BrowserAddress.publicHost(request.url.toString()); null }
                catch (_: Exception) {
                    if (request.isForMainFrame) view.post { pending?.completeExceptionally(IllegalStateException("Адрес страницы запрещён")) }
                    WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream("Blocked".toByteArray()))
                }
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (pending != null) navigationUrl = url
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
        view.setDownloadListener { _, _, _, _, _ -> pending?.completeExceptionally(IllegalStateException("Загрузка файлов через браузер не подключена")) }
    }
    override suspend fun open(url: String): JSONObject {
        withContext(Dispatchers.IO) { BrowserAddress.publicHost(url) }
        stop()
        val navigation = CompletableDeferred<Unit>(); pending = navigation; navigationUrl = url
        resume()
        try { view.loadUrl(url); navigation.await(); delay(300); return read() }
        finally { if (pending === navigation) pending = null }
    }
    override suspend fun read(): JSONObject {
        resume()
        require(BrowserAddress.valid(view.url.orEmpty())) { "Нет открытой HTTPS-страницы" }
        val evaluation = suspendCancellableCoroutine<String> { continuation ->
            view.evaluateJavascript(BrowserSnapshot.SCRIPT) { value -> if (continuation.isActive) continuation.resume(value) }
        }
        return BrowserSnapshot.decode(evaluation).also {
            require(it.getString("url") == view.url) { "Страница изменилась при чтении. Повтори browser_read." }
        }
    }
    override suspend fun scroll(down: Boolean): JSONObject {
        view.scrollBy(0, (view.height.coerceAtLeast(800) * if (down) 0.8 else -0.8).toInt())
        delay(200); return read()
    }
    override fun resume() { view.onResume(); view.resumeTimers() }
    override fun stop() { pending?.cancel(); pending = null; navigationUrl = null; view.stopLoading(); view.onPause(); view.pauseTimers() }
}
