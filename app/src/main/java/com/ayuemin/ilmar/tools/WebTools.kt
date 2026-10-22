package com.ayuemin.ilmar.tools

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import com.ayuemin.ilmar.settings.RuntimeLimits as Limits
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** DNS answers used for the socket are validated, including every redirect (redirects are disabled). */
internal object PublicAddressPolicy {
    fun allowed(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 4) return !(b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224 ||
            b[0] == 169 && b[1] == 254 || b[0] == 172 && b[1] in 16..31 ||
            b[0] == 192 && (b[1] == 168 || b[1] == 0 || b[1] == 2) ||
            b[0] == 192 && b[1] == 88 && b[2] == 99 ||
            b[0] == 100 && b[1] in 64..127 || b[0] == 198 && b[1] in 18..19 ||
            b[0] == 198 && b[1] == 51 || b[0] == 203 && b[1] == 0 && b[2] == 113)
        // Only global unicast IPv6. Exclude documentation, transition and translation ranges.
        return b.size == 16 && b[0] in 0x20..0x3f &&
            !(b[0] == 0x20 && b[1] == 0x01 && (b[2] < 0x20 || b[2] == 0x0d && b[3] == 0xb8)) &&
            !(b[0] == 0x20 && b[1] == 0x02) && !(b[0] == 0x3f && b[1] == 0xff)
    }
    val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
            if (addresses.isEmpty() || !addresses.all(::allowed)) throw java.net.UnknownHostException("Адрес локальной или специальной сети запрещён")
        }
    }
}

internal class WebTools(private val client: OkHttpClient = OkHttpClient.Builder()
    .dns(PublicAddressPolicy.dns).proxy(Proxy.NO_PROXY)
    .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()) {
    suspend fun fetch(url: String): String {
        return fetchDocument(url).legacyResult(Limits.get("excerpt_chars"))
    }
    /** Complete HTTP payload; presentation limits must never modify this source. */
    suspend fun fetchDocument(url: String): FetchedDocument {
        val parsed = url.toHttpUrl()
        require(parsed.scheme == "https" && parsed.username.isEmpty() && parsed.password.isEmpty()) { "Разрешены только публичные HTTPS URL без паролей" }
        validatePublicUrl(parsed)
        val response = load(Request.Builder().url(parsed).header("User-Agent", "Ilmar/0.1 URL reader").build(), Limits.bytes("fetch_kib"))
        require(response.first.startsWith("text/") || response.first.contains("json") || response.first.contains("xml")) { "URL должен возвращать текст или HTML" }
        if (response.first.contains("html")) require(response.second.count { it == '<' } <= 64000) {
            "DOCUMENT_PARSE_LIMIT: слишком сложный HTML; документ не индексирован"
        }
        val text = if (response.first.contains("html")) Jsoup.parse(response.second, url).apply {
            select("script,style,noscript,nav,footer,header,iframe,form,svg").remove()
        }.let { doc -> doc.selectFirst("article,main")?.text() ?: doc.body().text() } else response.second
        return FetchedDocument(parsed.toString(), response.first, response.second, text)
    }
    suspend fun search(query: String, key: String): String = search(query, SearchTarget(SearchProfile.preset("Brave"), key, JSONObject(SearchProfile.headers("Brave"))))
    suspend fun search(query: String, target: SearchTarget): String {
        require(query.isNotBlank() && query.length <= 8000) { "Поисковый запрос: до 8000 символов" }
        val profile = target.profile
        val count = Limits.get("search_results")
        fun substitute(value: String) = value.replace("{query}", query).replace("{api_key}", target.key).replace("{count}", count.toString())
        val original = profile.endpoint.toHttpUrl()
        validatePublicUrl(original)
        val url = original.newBuilder().query(null).apply {
            repeat(original.querySize) { index -> addQueryParameter(original.queryParameterName(index), original.queryParameterValue(index)?.let(::substitute)) }
            if (profile.method == "GET" && profile.queryParameter.isNotBlank()) setQueryParameter(profile.queryParameter, query)
        }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json")
        target.headers.keys().forEach { name ->
            require(!name.equals("Host", true) && !name.equals("Content-Length", true)) { "Host и Content-Length задаются клиентом" }
            request.header(name, substitute(target.headers.getString(name)))
        }
        fun bodyValue(value: Any?): Any? = when (value) {
            is JSONObject -> JSONObject().apply { value.keys().forEach { put(it, bodyValue(value.opt(it))) } }
            is JSONArray -> JSONArray().apply { repeat(value.length()) { put(bodyValue(value.opt(it))) } }
            is String -> if (value == "{count}") count else substitute(value)
            else -> value
        }
        if (profile.method == "POST") request.post(bodyValue(JSONObject(profile.body)).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        val body = load(request.build(), Limits.bytes("fetch_kib")).second
        val root: Any = if (body.trimStart().startsWith('[')) JSONArray(body) else JSONObject(body)
        val results = JsonFieldPath.get(root, profile.resultsPath) as? JSONArray
            ?: error("По пути '${profile.resultsPath}' нет массива результатов. Проверь схему ответа API поиска.")
        return JSONObject().put("provider", profile.name).put("results", JSONArray().apply { repeat(minOf(count, results.length())) { i ->
            val item = results.opt(i)
            val urlValue = JsonFieldPath.get(item, profile.urlPath)?.toString().orEmpty()
            val parsed = runCatching { urlValue.toHttpUrl() }.getOrNull()
            if (parsed != null && parsed.scheme in setOf("http", "https")) put(JSONObject()
                .put("title", org.jsoup.Jsoup.parse(JsonFieldPath.get(item, profile.titlePath)?.toString().orEmpty()).text().take(1000))
                .put("url", urlValue.take(4096)).put("snippet", Jsoup.parse(JsonFieldPath.get(item, profile.snippetPath)?.toString().orEmpty()).text().take(8000)))
        } }).put("untrusted", true).toString()
    }
    private fun validatePublicUrl(url: HttpUrl) {
        require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty()) { "Для tools нужен HTTPS URL без встроенного пароля" }
        if (url.host.contains(':') || url.host.matches(Regex("[0-9.]+"))) require(PublicAddressPolicy.allowed(InetAddress.getByName(url.host))) { "Локальные и служебные IP запрещены" }
    }
    private suspend fun load(request: Request, limit: Int): Pair<String, String> = suspendCancellableCoroutine { continuation ->
        val call = client.newBuilder().callTimeout(Limits.get("web_seconds").toLong(), TimeUnit.SECONDS)
            .readTimeout(Limits.get("web_seconds").toLong(), TimeUnit.SECONDS).build().newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWith(Result.failure(IOException("Нет соединения или истекло время ожидания"))) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use {
                    require(it.isSuccessful) { "HTTP ${it.code}; перенаправления не выполняются" }
                    val body = it.body ?: error("Пустой ответ")
                    require(body.contentLength() <= limit) { "Ответ слишком большой" }
                    val bytes = body.byteStream().use { stream ->
                        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val n = stream.read(buffer); if (n < 0) break
                            require(output.size() + n <= limit) { "Ответ слишком большой" }; output.write(buffer, 0, n) }
                        output.toByteArray()
                    }
                    body.contentType()?.toString().orEmpty() to String(bytes, body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                } }
                continuation.resumeWith(result)
            }
        })
    }
}
