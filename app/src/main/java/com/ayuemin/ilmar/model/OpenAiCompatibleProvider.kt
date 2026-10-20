package com.ayuemin.ilmar.model

import com.ayuemin.ilmar.chat.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Minimal OpenAI-compatible chat-completions adapter. Never logs credentials. */
class OpenAiCompatibleProvider {

    suspend fun stream(
        endpoint: String, model: String, apiKey: String,
        history: List<ChatMessage>, onDelta: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val url = URL(endpoint)
        require(url.protocol == "https" && url.userInfo == null && url.ref == null) {
            "Требуется корректный HTTPS URL"
        }
        require(model.isNotBlank()) { "Укажи модель" }
        val payload = JSONObject().put("model", model).put("stream", true)
            .put("messages", JSONArray().apply {
                history.forEach { message ->
                    put(JSONObject().put("role", if (message.fromUser) "user" else "assistant")
                        .put("content", message.text))
                }
            }).toString()
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 90000
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
        }
        try {
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Ошибка API: HTTP $code")
            connection.inputStream.bufferedReader().use { reader ->
                while (true) {
                    coroutineContext.ensureActive()
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    if (data.isEmpty()) continue
                    val choice = JSONObject(data).optJSONArray("choices")?.optJSONObject(0)
                    val delta = choice?.optJSONObject("delta")?.optString("content").orEmpty()
                    if (delta.isNotEmpty()) onDelta(delta)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun complete(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>): String =
        withContext(Dispatchers.IO) {
            val url = URL(endpoint)
            require(url.protocol == "https") { "Для API требуется HTTPS" }
            require(url.userInfo == null && url.ref == null) { "Недопустимый URL API" }
            require(model.isNotBlank()) { "Укажи модель" }
            val payload = JSONObject().put("model", model).put("stream", false)
                .put("messages", JSONArray().apply {
                    history.forEach { message ->
                        put(JSONObject().put("role", if (message.fromUser) "user" else "assistant")
                            .put("content", message.text))
                    }
                }).toString()
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 90000
                doOutput = true
                instanceFollowRedirects = false
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("Ошибка API: HTTP $code")
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                JSONObject(response).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
            } finally {
                connection.disconnect()
            }
        }
}
