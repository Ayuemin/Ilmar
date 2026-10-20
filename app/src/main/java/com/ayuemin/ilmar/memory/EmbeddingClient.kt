package com.ayuemin.ilmar.memory

import android.content.Context
import com.ayuemin.ilmar.model.ProviderRegistry
import com.ayuemin.ilmar.settings.RuntimeLimits as Limits
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

internal data class EmbeddingConfig(val enabled: Boolean = false, val provider: String = "", val model: String = "", val endpoint: String = "") {
    fun endpoint(base: String): String {
        if (endpoint.isNotBlank()) return endpoint
        val url = base.toHttpUrl()
        val path = url.encodedPath.trimEnd('/')
        require(path.isEmpty() || path.endsWith("/v1") || path.endsWith("/chat/completions")) { "Укажи полный адрес API эмбеддингов" }
        return url.newBuilder().encodedPath(when {
            path.endsWith("/chat/completions") -> path.removeSuffix("/chat/completions") + "/embeddings"
            path.isEmpty() -> "/v1/embeddings"
            else -> "$path/embeddings"
        }).query(null).build().toString()
    }
    fun space(base: String) = ContextEstimate.hash("chunks-v1|${endpoint(base)}|$provider|$model|${Limits.get("embedding_chars")}")
}
internal class EmbeddingPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("ilmar_embeddings", Context.MODE_PRIVATE)
    fun load() = EmbeddingConfig(prefs.getBoolean("enabled", false), prefs.getString("provider", "").orEmpty(), prefs.getString("model", "").orEmpty(), prefs.getString("endpoint", "").orEmpty())
    fun save(config: EmbeddingConfig) {
        require(!config.enabled || config.provider.isNotBlank() && config.model.isNotBlank()) { "Выбери провайдера и укажи ID модели эмбеддингов" }
        require(config.endpoint.isBlank() || ProviderRegistry.validEndpoint(config.endpoint)) { "Нужен HTTPS адрес эмбеддингов" }
        check(prefs.edit().putBoolean("enabled", config.enabled).putString("provider", config.provider).putString("model", config.model.trim()).putString("endpoint", config.endpoint.trim()).commit())
    }
}
internal class EmbeddingClient(private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()) {
    suspend fun embed(endpoint: String, key: String, model: String, texts: List<String>): List<FloatArray> {
        require(ProviderRegistry.validEndpoint(endpoint)) { "Нужен HTTPS адрес эмбеддингов" }
        val payload = JSONObject().put("model", model).put("input", JSONArray(texts)).put("encoding_format", "float")
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $key").post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val raw: String = suspendCancellableCoroutine { continuation ->
            val call = client.newBuilder().callTimeout(Limits.get("embedding_seconds").toLong(), TimeUnit.SECONDS).readTimeout(Limits.get("embedding_seconds").toLong(), TimeUnit.SECONDS).build().newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWith(Result.failure(IOException("API эмбеддингов: ${e.javaClass.simpleName}"))) }
                override fun onResponse(call: Call, response: Response) { continuation.resumeWith(runCatching { response.use {
                    require(it.isSuccessful) { "API эмбеддингов: HTTP ${it.code}" }
                    val body = it.body ?: error("Пустой ответ эмбеддингов")
                    val limit = Limits.bytes("embedding_response_kib")
                    require(body.contentLength() <= limit) { "Слишком большой ответ эмбеддингов" }
                    val output = java.io.ByteArrayOutputStream()
                    body.byteStream().use { stream -> val buffer = ByteArray(8192); while (true) { val n = stream.read(buffer); if (n < 0) break; require(output.size() + n <= limit) { "Слишком большой ответ эмбеддингов" }; output.write(buffer, 0, n) } }
                    output.toString("UTF-8")
                } }) }
            })
        }
        try {
        val data = JSONObject(raw).getJSONArray("data")
        require(data.length() == texts.size) { "API вернул не все эмбеддинги" }
        val vectors = arrayOfNulls<FloatArray>(texts.size)
        repeat(data.length()) { i ->
            val item = data.getJSONObject(i); val index = item.getInt("index"); val values = item.getJSONArray("embedding")
            require(index in vectors.indices && vectors[index] == null && values.length() in 1..16384) { "Неверная структура эмбеддингов" }
            val vector = FloatArray(values.length()) { values.getDouble(it).toFloat() }
            require(vector.all { it.isFinite() }) { "Некорректный вектор" }
            val norm = sqrt(vector.sumOf { it.toDouble() * it })
            require(norm > 0 && norm.isFinite()) { "Пустой вектор" }
            vectors[index] = FloatArray(vector.size) { (vector[it] / norm).toFloat() }
        }
        require(vectors.filterNotNull().map { it.size }.distinct().size == 1) { "Размеры векторов различаются" }
        return vectors.map { it!! }
        } catch (_: org.json.JSONException) { throw IllegalArgumentException("API эмбеддингов вернул неверный формат JSON") }
    }
}
