package com.ayuemin.ilmar.browser

import android.content.Context
import com.ayuemin.ilmar.model.ModelCapabilities

/** Protocol adapters, independent of model IDs. A new provider need not impersonate OpenRouter. */
internal enum class ProviderSearchProtocol(val title: String) {
    AUTO("Определять по каталогу и адресу API"),
    CHAT_COMPLETIONS("Совместимый веб-поиск Chat Completions"),
    OPENROUTER("Веб-поиск OpenRouter"),
    NATIVE("Поиск встроен в модель поставщика"),
    DISABLED("Встроенный поиск недоступен")
}
internal enum class ProviderSearchEngine(val title: String, val wire: String?) {
    AUTO("Автоматически у поставщика", null), EXA("Exa", "exa"), PARALLEL("Parallel", "parallel"),
    NATIVE("Движок модели", "native"), FIRECRAWL("Firecrawl", "firecrawl"), PERPLEXITY("Perplexity", "perplexity")
}
internal class ProviderSearchPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("ilmar_provider_search", Context.MODE_PRIVATE)
    fun selected(provider: String) = runCatching { ProviderSearchProtocol.valueOf(prefs.getString(provider, "AUTO")!!) }.getOrDefault(ProviderSearchProtocol.AUTO)
    fun save(provider: String, protocol: ProviderSearchProtocol) { check(prefs.edit().putString(provider, protocol.name).commit()) }
    fun engine(provider: String) = runCatching { ProviderSearchEngine.valueOf(prefs.getString("$provider.engine", "AUTO")!!) }.getOrDefault(ProviderSearchEngine.AUTO)
    fun saveEngine(provider: String, engine: ProviderSearchEngine) { check(prefs.edit().putString("$provider.engine", engine.name).commit()) }
}
internal object ProviderSearch {
    fun resolve(selection: ProviderSearchProtocol, endpoint: String, caps: ModelCapabilities): ProviderSearchProtocol? {
        if (selection == ProviderSearchProtocol.DISABLED) return null
        if (selection != ProviderSearchProtocol.AUTO) return selection
        return when {
            "web_search_options" in caps.parameters -> ProviderSearchProtocol.CHAT_COMPLETIONS
            ModelCapabilities.host(endpoint) == "openrouter.ai" -> ProviderSearchProtocol.OPENROUTER
            ModelCapabilities.host(endpoint) in setOf("api.perplexity.ai") -> ProviderSearchProtocol.NATIVE
            else -> null // Never infer a provider's proprietary request format from tools support.
        }
    }
}
