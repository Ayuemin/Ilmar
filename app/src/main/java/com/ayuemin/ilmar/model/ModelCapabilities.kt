package com.ayuemin.ilmar.model

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class CatalogModel(val id: String, val capabilities: ModelCapabilities = ModelCapabilities())
data class ModelCapabilities(val parameters: Set<String> = emptySet(), val efforts: Set<String> = emptySet(),
    val contextLength: Long? = null, val maxOutputTokens: Long? = null, val known: Boolean = false) {
    val tools: Boolean get() = "tools" in parameters
    fun supportsReasoning(endpoint: String): Boolean =
        (host(endpoint) == "openrouter.ai" && parameters.any { it == "reasoning" || it.startsWith("reasoning.") }) ||
            ("reasoning_effort" in parameters && efforts.isNotEmpty())
    fun reasoningConfig(endpoint: String, enabled: Boolean, effort: String?): ReasoningConfig? {
        if (!enabled || !supportsReasoning(endpoint)) return null
        val selected = effort?.takeIf { it in efforts }
        return if (host(endpoint) == "openrouter.ai") ReasoningConfig("openrouter", selected)
        else ReasoningConfig("effort", selected ?: efforts.first())
    }
    fun includeUsage(endpoint: String) = host(endpoint) in setOf("openrouter.ai", "api.openai.com") || "stream_options" in parameters
    fun toJson() = JSONObject().put("parameters", JSONArray(parameters.toList())).put("efforts", JSONArray(efforts.toList()))
        .put("context", contextLength).put("output", maxOutputTokens).put("known", known)
    companion object {
        fun host(endpoint: String) = runCatching { URI(endpoint).host?.lowercase() }.getOrNull()
        fun fromJson(j: JSONObject?) = if (j == null) ModelCapabilities() else ModelCapabilities(
            strings(j.optJSONArray("parameters")), strings(j.optJSONArray("efforts")),
            nonNegativeLong(j, "context"), nonNegativeLong(j, "output"), j.optBoolean("known"))
        fun parse(model: JSONObject): ModelCapabilities {
            val supported = model.opt("supported_parameters")
            val names = mutableSetOf<String>()
            val efforts = mutableSetOf<String>()
            fun collect(name: String, values: JSONObject?) {
                names += name
                if (name == "reasoning_effort" || name == "reasoning.effort") {
                    efforts += strings(values?.optJSONArray("values") ?: values?.optJSONArray("supported_values"))
                }
            }
            if (supported is JSONArray) for (i in 0 until supported.length()) when (val p = supported.opt(i)) {
                is String -> collect(p, null)
                is JSONObject -> (p.opt("name") as? String)?.let { collect(it, p) }
            }
            if (supported is JSONObject) supported.keys().forEach { collect(it, supported.optJSONObject(it)) }
            efforts += strings(model.optJSONObject("reasoning")?.optJSONArray("supported_efforts"))
            return ModelCapabilities(names, efforts.filter { it in setOf("minimal", "low", "medium", "high", "xhigh", "max", "none") }.toSet(),
                nonNegativeLong(model, "context_length"), nonNegativeLong(model.optJSONObject("top_provider"), "max_completion_tokens"), supported != null && supported != JSONObject.NULL)
        }
        private fun strings(a: JSONArray?): Set<String> = if (a == null) emptySet() else
            (0 until a.length()).mapNotNull { a.opt(it) as? String }.toSet()
    }
}
