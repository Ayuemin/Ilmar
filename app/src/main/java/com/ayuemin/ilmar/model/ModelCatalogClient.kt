package com.ayuemin.ilmar.model

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** OpenAI-compatible model discovery; one explicit request, no redirects or secret-bearing logs. */
class ModelCatalogClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()) {
    suspend fun load(endpoint: String, apiKey: String): List<String> = loadDetailed(endpoint, apiKey).map { it.id }
    suspend fun loadDetailed(endpoint: String, apiKey: String): List<CatalogModel> = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(modelsUrl(endpoint)).header("Authorization", "Bearer $apiKey").get().build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(IOException("Не удалось загрузить модели. Проверь сеть и подключение.")))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw ProviderFailure.http(it.code)
                        val body = it.body ?: throw IOException("Пустой каталог")
                        val bytes = body.byteStream().use { input ->
                            val data = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (data.size() + count > 8 * 1024 * 1024) throw IOException("Каталог слишком большой")
                                data.write(buffer, 0, count)
                            }
                            data.toString("UTF-8")
                        }
                        parseDetailed(bytes)
                    }
                }.recoverCatching { e ->
                    throw (e as? ProviderFailure ?: IOException("Некорректный или недоступный каталог моделей"))
                }
                continuation.resumeWith(result)
            }
        })
    }

    companion object {
        fun modelsUrl(endpoint: String): String {
            val chat = ChatEndpoint.resolve(endpoint).toHttpUrl()
            val path = chat.encodedPath.trimEnd('/')
            require(path.endsWith("/chat/completions")) { "Для каталога укажи стандартный базовый адрес API в подключениях" }
            val builder = chat.newBuilder().encodedPath(path.removeSuffix("/chat/completions") + "/models")
            // Same all-model query used by Umnik; do not send vendor-specific parameters elsewhere.
            if (chat.host == "openrouter.ai") builder.setQueryParameter("output_modalities", "all")
            return builder.build().toString()
        }
        fun parse(raw: String): List<String> = parseDetailed(raw).map { it.id }
        fun parseDetailed(raw: String): List<CatalogModel> {
            val data = JSONObject(raw).getJSONArray("data")
            val models = (0 until data.length()).mapNotNull {
                val item = data.optJSONObject(it) ?: return@mapNotNull null
                val id = (item.opt("id") as? String)?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                CatalogModel(id, ModelCapabilities.parse(item))
            }.distinctBy { it.id }.sortedBy { it.id }
            require(data.length() == 0 || models.isNotEmpty()) { "Неизвестная схема каталога" }
            return models
        }
    }
}
