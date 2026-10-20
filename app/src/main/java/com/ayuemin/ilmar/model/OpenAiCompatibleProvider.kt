package com.ayuemin.ilmar.model

import com.ayuemin.ilmar.chat.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Minimal OpenAI-compatible chat-completions adapter. Never logs credentials. */
class OpenAiCompatibleProvider {
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
