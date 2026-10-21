package com.ayuemin.ilmar.downloads

import com.ayuemin.ilmar.browser.BrowserAddress
import com.ayuemin.ilmar.tools.PublicAddressPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Streaming GET to user storage. No import/parser, byte-array body, model upload, or retry. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class UserDownloadTransport(private val client: OkHttpClient = OkHttpClient.Builder()
    .dns(PublicAddressPolicy.dns).proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
    .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
    .callTimeout(0, TimeUnit.MILLISECONDS).build()) {
    suspend fun save(url: String, name: String?, headers: Map<String, String>, limit: Long,
        protect: (String) -> Unit, authorized: () -> Unit,
        destination: (String, String) -> DownloadDestination): SavedUserDownload {
        var current = url
        val original = BrowserAddress.parse(url)
        repeat(11) { redirects ->
            authorized(); BrowserAddress.parse(current)
            val host = BrowserAddress.parse(current).host
            if (host.contains(':') || host.matches(Regex("[0-9.]+"))) require(PublicAddressPolicy.allowed(InetAddress.getByName(host))) { "Локальные адреса запрещены" }
            protect(current)
            val request = Request.Builder().url(current)
            // Never forward cookies to a different origin during redirects.
            val address = BrowserAddress.parse(current)
            if (address.scheme == original.scheme && address.host == original.host && address.port == original.port)
                headers.forEach { (key, value) -> request.header(key, value) }
            val outcome = receive(request.build(), name, limit, authorized, destination)
            outcome.file?.let { return it }
            require(redirects < 10) { "Слишком много перенаправлений загрузки" }
            current = request.build().url.resolve(requireNotNull(outcome.redirect))?.toString() ?: error("Неверное перенаправление")
        }
        error("Слишком много перенаправлений")
    }
    private data class Outcome(val file: SavedUserDownload? = null, val redirect: String? = null)
    private suspend fun receive(request: Request, name: String?, limit: Long, authorized: () -> Unit,
        destination: (String, String) -> DownloadDestination): Outcome = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("Скачивание прервано: нет соединения или ответа сервера"))
            }
            override fun onResponse(call: Call, response: Response) {
                var target: DownloadDestination? = null
                try {
                    response.use {
                        if (it.code in setOf(301, 302, 303, 307, 308)) {
                            val next = it.header("Location") ?: error("Перенаправление без адреса")
                            if (continuation.isActive) continuation.resume(Outcome(redirect = next)) { _, _, _ -> }
                            return
                        }
                        require(it.isSuccessful) { "Сервер загрузки: HTTP ${it.code}" }
                        val body = it.body ?: error("Нет тела файла")
                        require(limit == 0L || body.contentLength() <= limit) { "Размер превышает выбранный лимит загрузки" }
                        if (!continuation.isActive) return
                        authorized()
                        val mime = body.contentType()?.toString()?.substringBefore(';') ?: "application/octet-stream"
                        val fileName = name?.takeIf { value -> value.isNotBlank() } ?: android.webkit.URLUtil.guessFileName(request.url.toString(), it.header("Content-Disposition"), mime)
                        val sink = destination(UserDownloadStore.safeName(fileName), mime); target = sink
                        val count = body.byteStream().use { input -> stream(input, sink.output, limit) {
                            if (!continuation.isActive) throw CancellationException("Загрузка отменена")
                            authorized()
                        } }
                        if (!continuation.isActive) throw CancellationException("Загрузка отменена")
                        authorized()
                        val saved = sink.complete(count)
                        target = null // A completed user file is never removed by task cleanup.
                        if (continuation.isActive) continuation.resume(Outcome(file = saved)) { _, _, _ -> }
                    }
                } catch (e: Exception) {
                    target?.abort()
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }
    companion object {
        fun stream(input: InputStream, output: OutputStream, limit: Long = 0, active: () -> Unit = {}): Long {
            require(limit >= 0)
            val buffer = ByteArray(64 * 1024); var total = 0L
            while (true) {
                active(); val n = input.read(buffer); if (n < 0) break
                require(limit == 0L || n.toLong() <= limit - total) { "Размер превышает выбранный лимит загрузки" }
                output.write(buffer, 0, n); total += n
            }
            output.flush(); return total
        }
    }
}
