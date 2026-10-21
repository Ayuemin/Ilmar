package com.ayuemin.ilmar.memory

import com.ayuemin.ilmar.chat.ChatMessage
import com.ayuemin.ilmar.model.GenerationOptions
import org.json.JSONObject

/** Component estimates explain context growth. Only provider usage measures actual billed tokens. */
internal object ContextInputMetrics {
    fun estimate(history: List<ChatMessage>, options: GenerationOptions): JSONObject {
        val names = mutableMapOf<String, String>()
        var browser = 0; var other = 0; var resultBytes = 0
        for (message in options.toolHistory) {
            val calls = message.optJSONArray("tool_calls")
            var browserMessage = false
            if (calls != null) repeat(calls.length()) { i ->
                val call = calls.getJSONObject(i)
                val name = call.getJSONObject("function").optString("name")
                names[call.optString("id")] = name
                browserMessage = browserMessage || name.startsWith("browser_")
            }
            val browserResult = names[message.optString("tool_call_id")]?.startsWith("browser_") == true
            if (browserMessage || browserResult) browser += ContextEstimate.text(message.toString()) else other += ContextEstimate.text(message.toString())
            if (browserResult) resultBytes += message.optString("content").toByteArray(Charsets.UTF_8).size
        }
        return JSONObject().put("method", "local_heuristic_not_provider_usage")
            .put("chat_tokens_estimate", ContextEstimate.messages(history))
            .put("system_tokens_estimate", ContextEstimate.text(options.systemPrefix))
            .put("retrieval_tokens_estimate", ContextEstimate.text(options.retrieval))
            .put("schemas_tokens_estimate", ContextEstimate.text(options.tools.joinToString { it.name + it.description + it.parameters.toString() }))
            .put("browser_history_tokens_estimate", browser).put("other_tool_history_tokens_estimate", other)
            .put("browser_result_utf8_bytes", resultBytes).put("browser_image_attached", options.observation != null)
    }
}
