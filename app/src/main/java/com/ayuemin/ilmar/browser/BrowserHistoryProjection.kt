package com.ayuemin.ilmar.browser

import com.ayuemin.ilmar.agent.AgentFailure
import com.ayuemin.ilmar.memory.ContextEstimate
import org.json.JSONObject

/** Deterministic working context only. Original call/result groups stay in the durable trace. */
internal object BrowserHistoryProjection {
    fun project(history: List<JSONObject>, policy: BrowserPolicy): List<JSONObject> {
        val result = history.map { JSONObject(it.toString()) }
        val names = mutableMapOf<String, String>()
        val traces = mutableMapOf<String, String>()
        val records = mutableListOf<Pair<JSONObject, String>>()
        val snapshots = linkedMapOf<String, JSONObject>()
        val observations = mutableListOf<JSONObject>()
        for (message in result) {
            val calls = message.optJSONArray("tool_calls")
            if (calls != null) repeat(calls.length()) { i ->
                val call = calls.getJSONObject(i); val id = call.getString("id")
                names[id] = call.getJSONObject("function").optString("name")
                traces[id] = message.optString("_trace_id")
            }
            val id = message.optString("tool_call_id")
            val name = names[id].orEmpty()
            if (message.optString("role") != "tool" || !name.startsWith("browser_")) continue
            records += message to name
            val payload = runCatching { JSONObject(message.optString("content")) }.getOrNull() ?: continue
            val page = payload.optJSONObject("observation") ?: payload
            if (!page.has("text") || page.optString("page_id").isBlank()) continue
            val base = snapshots[page.optString("base_snapshot_id")]
            val full = JSONObject(page.toString())
            if (page.optString("representation") == "DOM_DELTA") {
                if (base == null || base.optString("page_id") != page.optString("page_id")) {
                    full.put("observation_required", true).put("notice", "База DOM отсутствует в контексте; запроси browser_read или browser_find.")
                } else {
                    for (field in listOf("text", "controls", "links")) {
                        if (page.optBoolean("${field}_unchanged")) {
                            full.put(field, base.opt(field))
                            if (field == "text" && base.optBoolean("context_excerpt")) full.put("context_excerpt", true).put("text_truncated", true)
                        }
                        full.remove("${field}_unchanged")
                    }
                    full.remove("base_snapshot_id"); full.put("representation", "DOM_COMPACT")
                }
            }
            snapshots[full.optString("snapshot_id")] = full
            // Batch keeps only its final observation in the model-facing response.
            if (payload.has("observation")) payload.put("observation", full)
            else full.keys().forEach { payload.put(it, full.get(it)) }.also { payload.remove("base_snapshot_id"); listOf("text_unchanged", "controls_unchanged", "links_unchanged").forEach { payload.remove(it) } }
            message.put("content", payload.toString())
            observations += message
        }
        if (records.isEmpty()) return history
        val keep = observations.takeLast(policy.historyItems).toSet() + listOf(records.last().first)
        fun summarize(message: JSONObject, name: String, tiny: Boolean) {
            val raw = runCatching { JSONObject(message.optString("content")) }.getOrNull()
            val page = raw?.optJSONObject("observation") ?: raw
            val summary = JSONObject().put("historical", true).put("tool", name)
                .put("trace_id", traces[message.optString("tool_call_id")].orEmpty())
            for (key in listOf("error", "_awaiting_user", "action_performed", "completed_steps", "halt_reason", "download_id", "status", "location", "user_only", "content_available_to_model")) raw?.opt(key)?.let { summary.put(key, it) }
            page?.opt("url")?.let { summary.put("url", it) }
            if (!tiny) summary.put("excerpt", page?.optString("text").orEmpty().take(120))
            // No historical refs: they must never look like current actionable elements.
            summary.put("original_available", true)
            message.put("content", summary.toString())
        }
        for ((message, name) in records) if (message !in keep) summarize(message, name, false)
        fun size() = records.sumOf { ContextEstimate.text(it.first.optString("content")) }
        if (size() > policy.historyTokens) for ((message, name) in records) {
            if (message !== records.last().first) summarize(message, name, true)
        }
        if (size() > policy.historyTokens) {
            val latest = records.last().first
            val payload = runCatching { JSONObject(latest.optString("content")) }.getOrNull()
            val page = payload?.optJSONObject("observation") ?: payload
            val text = page?.optString("text").orEmpty()
            if (text.isNotEmpty()) {
                var low = 0; var high = text.length
                page!!.put("text_truncated", true).put("context_excerpt", true)
                while (low < high) {
                    val middle = (low + high + 1) / 2
                    page.put("text", text.take(middle)); latest.put("content", payload.toString())
                    if (size() <= policy.historyTokens) low = middle else high = middle - 1
                }
                page.put("text", text.take(low)); latest.put("content", payload.toString())
            }
        }
        if (size() > policy.historyTokens) throw AgentFailure("BROWSER_CONTEXT_LIMIT",
            "Бюджет браузерного контекста ${policy.historyTokens} токенов не вмещает актуальные элементы и результаты. Увеличь его или уменьши число ссылок/элементов в настройках. Оригиналы сохранены.")
        return result
    }
}
