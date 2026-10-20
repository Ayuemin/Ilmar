package com.ayuemin.ilmar.model

import android.content.Context
import com.ayuemin.ilmar.security.CredentialStore
import com.ayuemin.ilmar.security.SecretStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

data class ProviderConnection(val id: String, val name: String, val endpoint: String)
data class RegisteredModel(val id: String, val providerId: String, val name: String)
data class RegistrySnapshot(
    val providers: List<ProviderConnection> = emptyList(),
    val models: List<RegisteredModel> = emptyList(),
    val defaultModelId: String? = null
)
data class ModelTarget(val connection: ProviderConnection, val model: RegisteredModel, val apiKey: String)

/** Metadata only. Keys remain in CredentialStore; chat preferences hold stable model IDs. */
class ProviderRegistry(context: Context, private val credentials: SecretStore = CredentialStore(context)) {
    private val prefs = context.getSharedPreferences("ilmar_models", Context.MODE_PRIVATE)
    private val legacy = context.getSharedPreferences("ilmar_ui", Context.MODE_PRIVATE)
    private val modes = context.getSharedPreferences("ilmar_chat_modes", Context.MODE_PRIVATE)

    init { migrateLegacy() }

    fun snapshot(): RegistrySnapshot = decode(prefs.getString("registry", null))

    private fun write(value: RegistrySnapshot) {
        check(prefs.edit().putString("registry", encode(value)).commit()) { "Не удалось сохранить модели" }
    }

    private fun migrateLegacy() {
        if (prefs.contains("registry")) return
        val endpoint = legacy.getString("api_endpoint", null)
        val model = legacy.getString("api_model", null)
        // The old UI supplied these defaults without persisting them when only a key was saved.
        val hasKey = credentials.hasApiKey()
        if (endpoint == null && model == null && !hasKey) return
        val connection = ProviderConnection("legacy", "Основное подключение",
            endpoint ?: "https://api.openai.com/v1/chat/completions")
        val registered = RegisteredModel("legacy-model", "legacy", model ?: "gpt-4o-mini")
        write(RegistrySnapshot(listOf(connection), listOf(registered), registered.id))
        // Leave old ciphertext untouched: migration never needs plaintext or a new Keystore key.
    }

    fun saveConnection(id: String?, name: String, endpoint: String, modelNames: List<String>, key: String) {
        require(name.isNotBlank()) { "Укажи название подключения" }
        require(validEndpoint(endpoint)) { "Требуется корректный HTTPS адрес API" }
        val names = modelNames.map(String::trim).filter(String::isNotEmpty).distinct()
        require(names.isNotEmpty()) { "Добавь хотя бы один ID модели" }
        val old = snapshot()
        val providerId = id ?: UUID.randomUUID().toString()
        val connection = ProviderConnection(providerId, name.trim(), endpoint.trim())
        val models = names.map { modelName ->
            old.models.find { it.providerId == providerId && it.name == modelName }
                ?: RegisteredModel(UUID.randomUUID().toString(), providerId, modelName)
        }
        // Write a new secret only when explicitly entered; blank means preserve the saved key.
        if (key.isNotBlank()) credentials.saveApiKey(key.trim(), providerId)
        val allModels = old.models.filterNot { it.providerId == providerId } + models
        write(RegistrySnapshot(old.providers.filterNot { it.id == providerId } + connection,
            allModels, old.defaultModelId?.takeIf { default -> allModels.any { it.id == default } } ?: allModels.first().id))
    }

    fun setDefault(modelId: String) {
        val old = snapshot()
        require(old.models.any { it.id == modelId })
        write(old.copy(defaultModelId = modelId))
    }

    fun hasKey(providerId: String) = credentials.hasApiKey(providerId)
    fun clearKey(providerId: String) = credentials.clearApiKey(providerId)

    fun selectedModelId(chatId: Long): String? {
        val key = "${chatId}_registered_model"
        if (modes.contains(key)) return modes.getString(key, null)
        // Preserve a legacy override only if it matches a registered connection and model.
        val name = modes.getString("${chatId}_api_model", null)
        val endpoint = modes.getString("${chatId}_api_endpoint", null)
        if (name != null && name != "Автоматически") {
            val data = snapshot()
            val found = data.models.find { model -> model.name == name &&
                data.providers.any { it.id == model.providerId && it.endpoint == endpoint } }
            val result = found?.id ?: "unavailable-legacy"
            check(modes.edit().putString(key, result).commit())
            return result
        }
        return null
    }

    fun select(chatId: Long, modelId: String?) {
        if (modelId != null) require(snapshot().models.any { it.id == modelId })
        check(modes.edit().apply {
            if (modelId == null) remove("${chatId}_registered_model")
            else putString("${chatId}_registered_model", modelId)
            remove("${chatId}_api_model"); remove("${chatId}_api_endpoint")
        }.commit())
    }

    fun modelForChat(chatId: Long): RegisteredModel? {
        val data = snapshot()
        val id = selectedModelId(chatId) ?: data.defaultModelId
        return data.models.find { it.id == id }
    }

    fun target(chatId: Long): ModelTarget {
        val model = modelForChat(chatId) ?: error("Выбери доступную модель в настройках чата")
        val connection = snapshot().providers.find { it.id == model.providerId }
            ?: error("Подключение модели недоступно")
        require(validEndpoint(connection.endpoint)) { "Исправь HTTPS адрес в настройках подключения" }
        val key = credentials.loadApiKey(connection.id) ?: error("Добавь API-ключ подключения «${connection.name}»")
        return ModelTarget(connection, model, key)
    }

    companion object {
        fun validEndpoint(value: String): Boolean = try {
            val uri = URI(value.trim())
            uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535)
        } catch (_: Exception) { false }

        fun encode(data: RegistrySnapshot): String = JSONObject().put("version", 1)
            .put("providers", JSONArray().apply { data.providers.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("endpoint", it.endpoint))
            } }).put("models", JSONArray().apply { data.models.forEach {
                put(JSONObject().put("id", it.id).put("providerId", it.providerId).put("name", it.name))
            } }).put("default", data.defaultModelId).toString()

        fun decode(raw: String?): RegistrySnapshot {
            if (raw == null) return RegistrySnapshot()
            val json = JSONObject(raw)
            require(json.getInt("version") == 1) { "Неподдерживаемая версия реестра моделей" }
            val providers = json.getJSONArray("providers")
            val models = json.getJSONArray("models")
            return RegistrySnapshot((0 until providers.length()).map {
                val p = providers.getJSONObject(it)
                ProviderConnection(p.getString("id"), p.getString("name"), p.getString("endpoint"))
            }, (0 until models.length()).map {
                val m = models.getJSONObject(it)
                RegisteredModel(m.getString("id"), m.getString("providerId"), m.getString("name"))
            }, if (json.has("default") && !json.isNull("default")) json.getString("default") else null)
        }
    }
}
