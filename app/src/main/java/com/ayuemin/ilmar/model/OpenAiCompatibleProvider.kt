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
    suspend fun generate(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>,
        options: GenerationOptions, onDelta: (String) -> Unit): GenerationResult =
        GenerationResult(stream(endpoint, model, apiKey, history, onDelta))
}

class ProviderFailure(message: String, val httpStatus: Int? = null, val details: String? = null, val reportedUsage: TokenUsage? = null) : IOException(message) {
    companion object {
        fun http(status: Int, details: String? = null) = ProviderFailure(when (status) {
            401 -> "Ошибка API: HTTP 401. Проверь API-ключ подключения."
            403 -> "Ошибка API: HTTP 403. У подключения нет доступа к этой модели."
            404 -> "Ошибка API: HTTP 404. Проверь адрес API и ID модели в настройках подключения."
            429 -> "Ошибка API: HTTP 429. Достигнут лимит запросов или квоты. Повтори позже."
            else -> "Ошибка API: HTTP $status. Попробуй позже или проверь подключение."
        }, status, details)
    }
}

/** No redirects, response-body logging, automatic retries, or implicit provider changes. */
class OpenAiCompatibleProvider(
    private val client: OkHttpClient? = null
) : ChatProvider {
    private val defaultClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()
    override suspend fun stream(endpoint: String, model: String, apiKey: String,
        history: List<ChatMessage>, onDelta: (String) -> Unit): String =
        generate(endpoint, model, apiKey, history, GenerationOptions(), onDelta).text

    override suspend fun generate(endpoint: String, model: String, apiKey: String,
        history: List<ChatMessage>, options: GenerationOptions, onDelta: (String) -> Unit): GenerationResult {
        require(ProviderRegistry.validEndpoint(endpoint)) { "Требуется корректный HTTPS URL" }
        require(model.isNotBlank()) { "Укажи модель" }
        val payload = GenerationPayload.build(model, history, options).toString()
        val request = Request.Builder().url(ChatEndpoint.resolve(endpoint))
            .header("Authorization", "Bearer $apiKey").header("Accept", "text/event-stream")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val timeout = com.ayuemin.ilmar.settings.RuntimeLimits.get("model_seconds").toLong()
            val call = (client ?: defaultClient.newBuilder().callTimeout(timeout, TimeUnit.SECONDS).readTimeout(timeout, TimeUnit.SECONDS).build()).newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(ProviderFailure("Нет соединения или истекло время ожидания", details = e.javaClass.simpleName + ": " + com.ayuemin.ilmar.diagnostics.Redaction.text(e.message.orEmpty()))))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        Result.success(response.use {
                            if (!it.isSuccessful) throw ProviderFailure.http(it.code, com.ayuemin.ilmar.diagnostics.Redaction.text(it.body?.charStream()?.readTextBounded(16000).orEmpty().let { body -> if (apiKey.isNotBlank()) body.replace(apiKey, "[REDACTED]") else body }))
                            val body = it.body ?: throw IOException("Пустой ответ")
                            body.charStream().buffered().use { reader ->
                                SseReader.readResult(reader) { delta -> if (continuation.isActive) onDelta(delta) }
                            }
                        })
                    } catch (e: Exception) {
                        Result.failure(e as? ProviderFailure ?: ProviderFailure("Поток ответа прерван или имеет неподдерживаемый формат", details = com.ayuemin.ilmar.diagnostics.Redaction.text(e.message.orEmpty().let { detail -> if (apiKey.isBlank()) detail else detail.replace(apiKey, "[REDACTED]") }), reportedUsage = (e as? StreamInterruption)?.reportedUsage))
                    }
                    continuation.resumeWith(result)
                }
            })
        }
    }
}

private fun java.io.Reader.readTextBounded(limit: Int): String { val chars = CharArray(limit); var total = 0; while (total < limit) { val n = read(chars, total, limit - total); if (n < 0) break; total += n }; return String(chars, 0, total) }
