package com.ayuemin.ilmar.tools

import android.content.Context
import com.ayuemin.ilmar.security.CredentialStore
import com.ayuemin.ilmar.security.SecretStore
import org.json.JSONArray
import org.json.JSONObject

internal data class SearchProfile(val id: String, val name: String, val endpoint: String, val method: String = "GET",
    val queryParameter: String = "q", val body: String = "{}", val resultsPath: String = "results",
    val titlePath: String = "title", val urlPath: String = "url", val snippetPath: String = "description") {
    fun json() = JSONObject().put("id", id).put("name", name).put("endpoint", endpoint).put("method", method)
        .put("query_parameter", queryParameter).put("body", body).put("results_path", resultsPath)
        .put("title_path", titlePath).put("url_path", urlPath).put("snippet_path", snippetPath)
    companion object {
        fun decode(j: JSONObject) = SearchProfile(j.getString("id"), j.getString("name"), j.getString("endpoint"), j.getString("method"),
            j.getString("query_parameter"), j.getString("body"), j.getString("results_path"), j.getString("title_path"), j.getString("url_path"), j.getString("snippet_path"))
        fun preset(kind: String, id: String = java.util.UUID.randomUUID().toString()) = when (kind) {
            "Brave" -> SearchProfile(id, "Brave", "https://api.search.brave.com/res/v1/web/search?count={count}", resultsPath = "web.results")
            "Tavily" -> SearchProfile(id, "Tavily", "https://api.tavily.com/search", "POST", "", """{"query":"{query}","api_key":"{api_key}","max_results":"{count}"}""", snippetPath = "content")
            "SerpAPI" -> SearchProfile(id, "SerpAPI", "https://serpapi.com/search.json?api_key={api_key}&num={count}", resultsPath = "organic_results", urlPath = "link", snippetPath = "snippet")
            else -> SearchProfile(id, "Мой поиск", "https://example.com/search")
        }
        fun headers(kind: String) = if (kind == "Brave") """{"X-Subscription-Token":"{api_key}"}""" else "{}"
    }
}
internal data class SearchTarget(val profile: SearchProfile, val key: String, val headers: JSONObject)

/** Profile metadata is separate from encrypted keys and arbitrary custom HTTP headers. */
internal class SearchRegistry(context: Context, private val secrets: SecretStore = CredentialStore(context)) {
    private val prefs = context.getSharedPreferences("ilmar_search", Context.MODE_PRIVATE)
    fun profiles(): List<SearchProfile> {
        val data = prefs.getString("profiles", null) ?: return listOf(SearchProfile.preset("Brave", "brave-legacy"))
        val array = JSONArray(data); return (0 until array.length()).map { SearchProfile.decode(array.getJSONObject(it)) }
    }
    fun selected() = prefs.getString("selected", null) ?: profiles().firstOrNull()?.id
    fun select(id: String) { require(profiles().any { it.id == id }); check(prefs.edit().putString("selected", id).commit()) }
    fun save(profile: SearchProfile, key: String, headers: String?) {
        require(profile.name.isNotBlank() && profile.method in setOf("GET", "POST")) { "Укажи название и GET/POST" }
        require(com.ayuemin.ilmar.model.ProviderRegistry.validEndpoint(profile.endpoint.replace("{query}", "query").replace("{api_key}", "key").replace("{count}", "10"))) { "Нужен корректный HTTPS endpoint" }
        require(!profile.endpoint.substringBefore('?').contains('{')) { "Шаблоны допускаются в параметрах запроса, не в адресе сервера" }
        require(profile.body.length <= 64000 && profile.resultsPath.length <= 1000)
        JSONObject(profile.body)
        if (headers != null) {
            val values = JSONObject(headers)
            values.keys().forEach { name -> require(name.matches(Regex("[A-Za-z0-9-]+")) && values.get(name) is String && !values.getString(name).contains(Regex("[\\r\\n]"))) { "Неверные HTTP-заголовки" } }
            secrets.saveApiKey(values.toString(), "search-headers:${profile.id}")
        }
        if (key.isNotBlank()) secrets.saveApiKey(key, secretId(profile.id))
        val profiles = profiles().filterNot { it.id == profile.id } + profile
        check(prefs.edit().putString("profiles", JSONArray().apply { profiles.forEach { put(it.json()) } }.toString()).putString("selected", profile.id).commit())
    }
    fun delete(id: String) {
        val remaining = profiles().filterNot { it.id == id }
        secrets.clearApiKey(secretId(id)); secrets.clearApiKey("search-headers:$id")
        check(prefs.edit().putString("profiles", JSONArray().apply { remaining.forEach { put(it.json()) } }.toString()).apply { if (selected() == id) remove("selected") }.commit())
    }
    fun clearKey(id: String) = secrets.clearApiKey(secretId(id))
    fun hasKey(id: String) = secrets.hasApiKey(secretId(id))
    fun ready() = runCatching { target(); true }.getOrDefault(false)
    fun target(): SearchTarget {
        val profile = profiles().find { it.id == selected() } ?: error("Добавь и выбери API поиска в настройках инструментов")
        val key = secrets.loadApiKey(secretId(profile.id)).orEmpty()
        val headers = JSONObject(secrets.loadApiKey("search-headers:${profile.id}") ?: if (profile.id == "brave-legacy") SearchProfile.headers("Brave") else "{}")
        require(key.isNotBlank() || !(profile.endpoint + profile.body + headers).contains("{api_key}")) { "Для выбранного API поиска нужен ключ" }
        return SearchTarget(profile, key, headers)
    }
    private fun secretId(id: String) = if (id == "brave-legacy") NativeTools.SEARCH_KEY else "search-key:$id"
}

internal object JsonFieldPath {
    fun get(root: Any?, path: String): Any? {
        val clean = path.removePrefix("$.")
        if (clean.isEmpty() || clean == "$") return root
        val parts = if (clean.startsWith('/')) clean.drop(1).split('/').map { it.replace("~1", "/").replace("~0", "~") } else clean.split('.')
        return parts.fold(root) { value, field -> when (value) { is JSONObject -> value.opt(field); is JSONArray -> field.toIntOrNull()?.let { value.opt(it) }; else -> null } }
    }
}
