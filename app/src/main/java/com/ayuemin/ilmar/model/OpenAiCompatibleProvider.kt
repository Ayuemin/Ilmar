package com.ayuemin.ilmar.model

import com.ayuemin.ilmar.chat.ChatMessage
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.TimeUnit

interface ChatProvider {
    suspend fun stream(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, onDelta: (String) -> Unit): String
}

class ProviderFailure(message: String) : IOException(message)

/** No redirects, response-body logging, automatic retries, or implicit provider changes. */
class OpenAiCompatibleProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()
) : ChatProvider {
    override suspend fun stream(endpoint: String, model: String, apiKey: String,
        history: List<ChatMessage>, onDelta: (String) -> Unit): String {
        require(ProviderRegistry.validEndpoint(endpoint)) { "Требуется корректный HTTPS URL" }
        require(model.isNotBlank()) { "Укажи модель" }
        val payload = JSONObject().put("model", model).put("stream", true)
            .put("messages", JSONArray().apply { history.forEach {
                put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text))
            } }).toString()
        val request = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $apiKey").header("Accept", "text/event-stream")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(IOException("Нет соединения или истекло время ожидания")))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        Result.success(response.use {
                            if (!it.isSuccessful) throw ProviderFailure("Ошибка API: HTTP ${it.code}")
                            val body = it.body ?: throw IOException("Пустой ответ")
                            body.charStream().buffered().use { reader ->
                                SseReader.read(reader) { delta -> if (continuation.isActive) onDelta(delta) }
                            }
                        })
                    } catch (e: Exception) {
                        Result.failure(e as? ProviderFailure ?: ProviderFailure("Поток ответа прерван или имеет неподдерживаемый формат"))
                    }
                    continuation.resumeWith(result)
                }
            })
        }
    }
}

internal object SseReader {
    fun read(reader: BufferedReader, onDelta: (String) -> Unit): String {
        val result = StringBuilder()
        val event = mutableListOf<String>()
        var done = false
        fun dispatch() {
            if (event.isEmpty()) return
            val data = event.joinToString("\n").trim()
            event.clear()
            if (data == "[DONE]") { done = true; return }
            val json = JSONObject(data)
            if (json.has("error")) throw IOException("Ошибка потока")
            val choice = json.optJSONArray("choices")?.optJSONObject(0)
            val delta = choice?.optJSONObject("delta")?.opt("content") as? String
            if (!delta.isNullOrEmpty()) { result.append(delta); onDelta(delta) }
        }
        while (!done) {
            val line = reader.readLine() ?: break
            when {
                line.isEmpty() -> dispatch()
                line.startsWith("data:") -> event.add(line.substring(5).trimStart())
            }
        }
        if (!done) dispatch()
        if (!done) throw EOFException("Нет окончания SSE")
        if (result.isBlank()) throw IOException("Пустой ответ")
        return result.toString()
    }
}
