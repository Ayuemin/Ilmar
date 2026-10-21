package com.ayuemin.ilmar.github

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fixed-origin REST client. Tokens never enter tool arguments, URLs, results or redirect requests. */
internal class GitHubClient(
    private val token: () -> String?,
    private val allowed: (Long) -> Boolean,
    private val approve: suspend (Long, String, String) -> Boolean,
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(45, TimeUnit.SECONDS).build(),
    private val origin: HttpUrl = "https://api.github.com/".toHttpUrl(),
    private val logDestinationAllowed: (HttpUrl) -> Boolean = { url ->
        url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            (url.host.endsWith(".blob.core.windows.net") || url.host.endsWith(".actions.githubusercontent.com") || url.host.endsWith(".githubusercontent.com"))
    }
) {
    companion object {
        const val KEY = "tool-github"
        const val ID = "github"
        const val MAX_BODY = 65536
        const val MAX_RESPONSE = 262144
        fun validate(method: String, path: String, body: String): HttpUrl {
            require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) { "Метод GitHub не поддерживается" }
            require(path.startsWith('/') && !path.startsWith("//") && path.length <= 8192 &&
                path.none { it.isISOControl() || it == '\\' } && '#' !in path) { "Укажи относительный путь REST API GitHub" }
            require(path.substringBefore('?').split('/').none { it == "." || it == ".." }) { "Путь не должен содержать dot segments" }
            val url = ("https://api.github.com" + path).toHttpUrl()
            require(url.host == "api.github.com" && url.username.isEmpty() && url.password.isEmpty() && url.port == 443)
            require(url.pathSegments.none { it == "." || it == ".." } && !Regex("(?i)%2e|%2f|%5c").containsMatchIn(path)) { "Закодированные разделители запрещены" }
            require(url.pathSegments.firstOrNull() in setOf("user", "users", "repos", "repositories", "orgs", "search", "rate_limit", "gists", "notifications", "issues", "teams")) { "Нужен путь REST API GitHub" }
            require(url.queryParameterNames.none { it.lowercase() in setOf("access_token", "token", "client_secret") }) { "Секреты в URL запрещены" }
            require(body.toByteArray().size <= MAX_BODY) { "JSON-тело GitHub превышает 64 КиБ; разбей операцию" }
            require(method != "GET" || body.isBlank()) { "GET не принимает тело" }
            if (body.isNotBlank()) JSONObject(body)
            return url
        }
    }

    suspend fun request(chat: Long, method: String, path: String, body: String = "", responseLimit: Int = 32768): String {
        require(responseLimit in 1024..MAX_RESPONSE) { "Лимит ответа GitHub: 1024–262144 байт" }
        val url = validate(method, path, body)
        check(allowed(chat)) { "Разрешение GitHub отозвано" }
        val credential = token()?.takeIf { it.isNotBlank() } ?: error("Добавь токен GitHub в настройках")
        if (method != "GET") {
            check(approve(chat, "GitHub · $method", "$path\n\n${body.ifBlank { "Без тела" }}\n\nОперация выполнится один раз. При сетевой ошибке сначала проверь результат в GitHub.")) { "Операция GitHub отменена" }
        }
        check(allowed(chat) && token() == credential) { "Разрешение или токен GitHub изменены" }
        val destination = origin.newBuilder().encodedPath(url.encodedPath).encodedQuery(url.encodedQuery).build()
        val request = Request.Builder().url(destination).header("Authorization", "Bearer $credential")
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Ilmar-GitHub").method(method,
                if (method == "GET" || method == "DELETE" && body.isBlank()) null
                else body.ifBlank { "{}" }.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return execute(request, credential, responseLimit)
    }

    private suspend fun execute(request: Request, credential: String, limit: Int): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        val activeCall = java.util.concurrent.atomic.AtomicReference(call)
        continuation.invokeOnCancellation { activeCall.get().cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("GitHub: результат запроса неизвестен; автоматический повтор отключён"))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    // Jobs/logs uses a short-lived signed redirect. Follow only this read endpoint,
                    // only to GitHub's log storage, and construct a fresh request without Authorization.
                    if (response.code in setOf(301, 302, 303, 307, 308) && call.request() === request && request.method == "GET" &&
                        request.url.encodedPath.matches(Regex("/repos/[^/]+/[^/]+/actions/jobs/[0-9]+/logs"))) {
                        val location = response.header("Location")?.let { runCatching { it.toHttpUrl() }.getOrNull() }
                        if (location != null && logDestinationAllowed(location)) {
                            response.close()
                            val download = client.newCall(Request.Builder().url(location).header("Accept", "text/plain").build())
                            activeCall.set(download)
                            if (continuation.isActive) download.enqueue(this) else download.cancel()
                            return
                        }
                    }
                    val result = response.use { reply ->
                        val bytes = reply.body?.byteStream()?.use { input ->
                            val buffer = java.io.ByteArrayOutputStream()
                            val chunk = ByteArray(8192)
                            while (buffer.size() <= limit) {
                                val count = input.read(chunk, 0, minOf(chunk.size, limit + 1 - buffer.size()))
                                if (count < 0) break
                                buffer.write(chunk, 0, count)
                            }
                            buffer.toByteArray()
                        } ?: ByteArray(0)
                        val truncated = bytes.size > limit
                        val text = String(bytes.take(limit).toByteArray(), Charsets.UTF_8).replace(credential, "[REDACTED]")
                        JSONObject().put("status", reply.code).put("ok", reply.isSuccessful).put("body", text)
                            .put("truncated", truncated).put("untrusted", true)
                            .put("next", reply.header("Link")?.replace(credential, "[REDACTED]") ?: JSONObject.NULL)
                            .put("rate_remaining", reply.header("X-RateLimit-Remaining") ?: JSONObject.NULL)
                            .put("rate_reset", reply.header("X-RateLimit-Reset") ?: JSONObject.NULL)
                            .put("retry_after", reply.header("Retry-After") ?: JSONObject.NULL)
                            .put("note", if (reply.code in 300..399) "Перенаправление не выполнено; используй актуальный путь API." else if (truncated) "Ответ ограничен $limit байт. Используй per_page/page, фильтры или отдельный объект." else "").toString()
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("GitHub: ответ не прочитан; проверь состояние операции перед повтором"))
                }
            }
        })
    }
}
