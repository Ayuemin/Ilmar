package com.ayuemin.ilmar.model

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

data class TokenUsage(val input: Long? = null, val output: Long? = null, val total: Long? = null,
    val cached: Long? = null, val reasoning: Long? = null, val costUsd: String? = null)
data class ModelToolCall(val id: String, val name: String, val arguments: String)
data class GenerationResult(val text: String, val usage: TokenUsage = TokenUsage(),
    val finishReason: String? = null, val toolCalls: List<ModelToolCall> = emptyList())
data class ReasoningConfig(val encoding: String, val effort: String? = null)
data class ToolSchema(val name: String, val description: String, val parameters: JSONObject)
data class GenerationOptions(val reasoning: ReasoningConfig? = null,
    val tools: List<ToolSchema> = emptyList(), val toolHistory: List<JSONObject> = emptyList(),
    val includeUsage: Boolean = false)

internal fun nonNegativeLong(json: JSONObject?, key: String): Long? =
    json?.opt(key)?.toString()?.toLongOrNull()?.takeIf { it >= 0 }
internal fun exactCost(value: Any?): String? = value?.takeUnless { it == JSONObject.NULL }?.toString()?.let {
    runCatching { BigDecimal(it).takeIf { amount -> amount >= BigDecimal.ZERO }?.stripTrailingZeros()?.toPlainString() }.getOrNull()
}

internal object GenerationPayload {
    fun build(model: String, history: List<com.ayuemin.ilmar.chat.ChatMessage>, options: GenerationOptions): JSONObject {
        val messages = JSONArray().apply {
            history.forEach { put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text)) }
            options.toolHistory.forEach { put(it) }
        }
        return JSONObject().put("model", model).put("stream", true).put("messages", messages).apply {
            if (options.includeUsage) put("stream_options", JSONObject().put("include_usage", true))
            options.reasoning?.let {
                when (it.encoding) {
                    "openrouter" -> put("reasoning", JSONObject().put("enabled", true).apply { it.effort?.let { effort -> put("effort", effort) } })
                    "effort" -> { require(!it.effort.isNullOrBlank()); put("reasoning_effort", it.effort) }
                    else -> error("Неподдерживаемый режим reasoning")
                }
            }
            if (options.tools.isNotEmpty()) put("tools", JSONArray().apply { options.tools.forEach {
                put(JSONObject().put("type", "function").put("function", JSONObject()
                    .put("name", it.name).put("description", it.description).put("parameters", it.parameters)))
            } })
        }
    }
}
