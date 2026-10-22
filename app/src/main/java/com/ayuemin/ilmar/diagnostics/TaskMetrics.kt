package com.ayuemin.ilmar.diagnostics

import com.ayuemin.ilmar.chat.UsageLedger
import com.ayuemin.ilmar.chat.UsageRecord
import com.ayuemin.ilmar.memory.ContextEstimate
import com.ayuemin.ilmar.model.TokenUsage
import org.json.JSONObject
import java.security.MessageDigest

/** Numeric run summary; never stores arguments, quotes, URLs, headers or secrets. */
internal class TaskMetrics(private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private val started = clock()
    private val models = mutableListOf<UsageRecord>()
    private val calls = mutableSetOf<String>()
    private val outputs = mutableSetOf<String>()
    private val errors = mutableSetOf<String>()
    private val fragments = mutableSetOf<String>()
    private val failedSignatures = mutableSetOf<String>()
    private val counts = mutableMapOf<String, Int>()
    private var repeats = 0
    private var retries = 0
    private var estimates = 0L
    private var historyEstimates = 0L
    private val modelFragments = mutableSetOf<String>()
    private var repeatedModelFragments = 0
    var stopReason = "FAILED"
    fun model(usage: TokenUsage, status: String, purpose: String, estimate: Int, toolHistory: List<JSONObject>) {
        models += UsageRecord(usage, status, purpose)
        if (status != "not_sent") {
            estimates += estimate; historyEstimates += ContextEstimate.text(toolHistory.joinToString())
            toolHistory.filter { it.optString("role") == "tool" }.forEach { item ->
                val data = runCatching { JSONObject(item.optString("content")) }.getOrNull()
                data?.optJSONArray("fragments")?.let { fs -> for (i in 0 until fs.length()) {
                    val f = fs.getJSONObject(i)
                    val key = hash(f.optString("document_id") + ":" + f.optString("fragment_id") + ":" + f.optString("text"))
                    if (!modelFragments.add(key)) repeatedModelFragments++
                } }
            }
        }
    }
    private fun hash(raw: String) = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    fun checkpoint(trace: String, messages: List<JSONObject>) {
        val assistant = messages.firstOrNull() ?: return
        val tools = assistant.optJSONArray("tool_calls") ?: return
        val startedCalls = assistant.optJSONArray("_started_calls")
        for (i in 0 until tools.length()) {
            val call = tools.getJSONObject(i); val id = "$trace:${call.optString("id")}"; val function = call.getJSONObject("function")
            val name = function.getString("name")
            val signature = name + ":" + com.ayuemin.ilmar.agent.toolArgumentsFingerprint(function.optString("arguments"))
            val entered = startedCalls != null && (0 until startedCalls.length()).any { startedCalls.optString(it) == call.optString("id") }
            if (entered && calls.add(id)) {
                counts[name] = (counts[name] ?: 0) + 1
                if (signature in failedSignatures) retries++
            }
            val output = messages.firstOrNull { it.optString("role") == "tool" && it.optString("tool_call_id") == call.optString("id") } ?: continue
            if (!outputs.add(id)) continue
            val result = runCatching { JSONObject(output.optString("content")) }.getOrNull() ?: continue
            if (result.has("error")) { errors.add(trace); failedSignatures.add(signature) } else failedSignatures.remove(signature)
            if (result.optString("error") == "TOOL_RETRY_EXHAUSTED") stopReason = "TOOL_RETRY_EXHAUSTED"
            result.optJSONArray("fragments")?.let { fs -> for (n in 0 until fs.length()) {
                val f = fs.getJSONObject(n)
                val fingerprint = hash(f.optString("document_id") + ":" + f.optString("fragment_id") + ":" + f.optString("text"))
                if (!fragments.add(fingerprint)) repeats++
            } }
        }
    }
    fun json(): JSONObject {
        val ledger = UsageLedger(models.filter { it.status != "not_sent" })
        return JSONObject().put("model_successes", ledger.completed).put("model_failures", ledger.sent - ledger.completed)
            .put("model_not_sent", models.count { it.status == "not_sent" }).put("compact_calls", ledger.compactCalls)
            .put("provider_input_tokens", ledger.input ?: JSONObject.NULL).put("provider_output_tokens", ledger.output ?: JSONObject.NULL)
            .put("provider_cached_tokens", ledger.cached ?: JSONObject.NULL).put("provider_cost_usd", ledger.costUsd ?: JSONObject.NULL)
            .put("provider_cost_incomplete", ledger.incompleteCost).put("provider_tokens_incomplete", models.any { it.status != "not_sent" && (it.usage.input == null || it.usage.output == null) })
            .put("local_estimated_input_tokens", estimates).put("local_estimated_tool_history_tokens", historyEstimates)
            .put("fetch_calls", counts["fetch_url"] ?: 0).put("local_search_calls", counts["document_search"] ?: 0)
            .put("local_read_calls", counts["document_read"] ?: 0).put("local_outline_calls", counts["document_outline"] ?: 0)
            .put("unique_fragments", fragments.size).put("repeated_fragments", repeats).put("tool_round_errors", errors.size)
            .put("unique_model_fragments", modelFragments.size).put("repeated_model_fragments", repeatedModelFragments)
            .put("retries", retries).put("stop_reason", stopReason).put("duration_ms", clock() - started)
    }
}
