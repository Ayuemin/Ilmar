package com.ayuemin.ilmar.tools

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
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
        val parsed = url.toHttpUrl()
        require(parsed.scheme == "https" && parsed.port == 443 && parsed.username.isEmpty() && parsed.password.isEmpty()) { "Разрешены только публичные HTTPS URL на порту 443 без паролей" }
        require(!parsed.host.contains(':') && !parsed.host.matches(Regex("[0-9.]+"))) { "Используй доменное имя публичного сайта, а не IP-адрес" }
        val response = load(Request.Builder().url(parsed).header("User-Agent", "Ilmar/0.1 URL reader").build(), 1024 * 1024)
        require(response.first.startsWith("text/") || response.first.contains("json") || response.first.contains("xml")) { "URL должен возвращать текст или HTML" }
        val text = if (response.first.contains("html")) Jsoup.parse(response.second, url).apply {
            select("script,style,noscript,nav,footer,header,iframe,form,svg").remove()
        }.let { doc -> doc.selectFirst("article,main")?.text() ?: doc.body().text() } else response.second
        return JSONObject().put("url", parsed.toString()).put("text", text.take(24000))
            .put("truncated", text.length > 24000).put("untrusted", true).toString()
    }
    suspend fun search(query: String, key: String): String {
        require(query.isNotBlank() && query.length <= 1000) { "Поисковый запрос: до 1000 символов" }
        require(key.isNotBlank()) { "Настрой ключ Brave Search" }
        val url = "https://api.search.brave.com/res/v1/web/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("count", "5").build()
        val body = load(Request.Builder().url(url).header("Accept", "application/json").header("X-Subscription-Token", key).build(), 512 * 1024).second
        val results = JSONObject(body).optJSONObject("web")?.optJSONArray("results") ?: JSONArray()
        return JSONObject().put("results", JSONArray().apply { repeat(minOf(5, results.length())) { i ->
            val item = results.getJSONObject(i)
            put(JSONObject().put("title", item.optString("title").take(300)).put("url", item.optString("url").take(2048))
                .put("snippet", Jsoup.parse(item.optString("description")).text().take(2000)))
        } }).put("untrusted", true).toString()
    }
    private suspend fun load(request: Request, limit: Int): Pair<String, String> = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
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
