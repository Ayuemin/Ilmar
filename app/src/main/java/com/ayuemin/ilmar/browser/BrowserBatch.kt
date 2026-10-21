package com.ayuemin.ilmar.browser

import com.ayuemin.ilmar.agent.LocalToolExecutor
import com.ayuemin.ilmar.agent.ToolOperationPolicy
import com.ayuemin.ilmar.memory.ContextEstimate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** A model-authored plan, executed locally. No inference, arbitrary JS, screenshots or implicit retries. */
internal object BrowserBatch {
    private val fields = mapOf(
        "open" to setOf("url"), "search" to setOf("query"), "read" to setOf("full"),
        "find" to setOf("query", "exact"), "wait" to setOf("timeout_ms"), "back" to emptySet(),
        "scroll" to setOf("direction"),
        "click" to setOf("page_id", "ref", "x", "y", "target", "target_exact"),
        "type" to setOf("page_id", "ref", "text", "submit", "target", "target_exact"),
        "select" to setOf("page_id", "ref", "text", "target", "target_exact"))
    private val strings = setOf("url", "query", "direction", "page_id", "text", "target")
    private val booleans = setOf("full", "exact", "submit", "target_exact")
    fun schema(): JSONObject {
        val properties = JSONObject().put("action", JSONObject().put("type", "string").put("enum", JSONArray(fields.keys.toList())))
        (strings + booleans + setOf("ref", "x", "y", "timeout_ms")).forEach { key ->
            properties.put(key, JSONObject().put("type", when (key) { in strings -> "string"; in booleans -> "boolean"; else -> "integer" }))
        }
        return JSONObject().put("type", "object").put("properties", JSONObject().put("steps", JSONObject().put("type", "array").put("items",
            JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(listOf("action"))).put("additionalProperties", false))))
            .put("required", JSONArray(listOf("steps"))).put("additionalProperties", false)
    }
    fun validate(args: JSONObject, policy: BrowserPolicy): JSONArray {
        require(args.keys().asSequence().toSet() == setOf("steps")) { "Нужен только массив steps" }
        val steps = args.getJSONArray("steps")
        require(policy.batchSteps > 0 && steps.length() in 1..policy.batchSteps) { "Лимит цепочки: ${policy.batchSteps} шагов" }
        repeat(steps.length()) { index ->
            val step = steps.getJSONObject(index)
            val action = step.getString("action")
            val allowed = requireNotNull(fields[action]) { "Действие $action запрещено внутри цепочки" }
            require(step.keys().asSequence().all { it == "action" || it in allowed }) { "Недопустимые поля шага ${index + 1}" }
            for (key in step.keys()) if (key != "action") require(when (key) {
                in strings -> step.get(key) is String
                in booleans -> step.get(key) is Boolean
                else -> step.get(key) is Int || step.get(key) is Long
            }) { "Неверный тип $key в шаге ${index + 1}" }
            when (action) {
                "open" -> BrowserAddress.parse(step.getString("url"))
                "search" -> BrowserAddress.search(step.getString("query"))
                "find" -> require(step.getString("query").isNotBlank() && step.getString("query").length <= 300)
                "scroll" -> require(step.getString("direction") in setOf("up", "down"))
                "wait" -> if (step.has("timeout_ms")) require(step.getLong("timeout_ms") in 0..policy.waitMs.toLong())
            }
            if (action in setOf("click", "type", "select")) {
                if (step.has("target")) {
                    require(step.getString("target").isNotBlank() && step.getString("target").length <= 300)
                    require(listOf("page_id", "ref", "x", "y").none { step.has(it) }) { "Нельзя смешивать target и устаревающие координаты/ref" }
                } else {
                    require(!step.has("target_exact"))
                    val details = JSONObject(step.toString()).apply { remove("action") }
                    BrowserActions.script(action, details)
                }
                if (action != "click") require(step.has("text") && step.getString("text").length <= 8000)
            }
        }
        return steps
    }
    suspend fun execute(args: JSONObject, policy: BrowserPolicy): String {
        val steps = validate(args, policy)
        val executor = LocalToolExecutor.current()
        val plan = ContextEstimate.hash(ToolOperationPolicy.canonical(args.toString()))
        val progress = JSONArray()
        var localCalls = 0
        var latest: JSONObject? = null
        suspend fun invoke(name: String, parameters: JSONObject, identity: String? = null): JSONObject {
            localCalls++
            return JSONObject(executor.execute(name, parameters, identity))
        }
        fun response(error: String? = null, reason: String? = null): JSONObject = JSONObject()
            .put("batch_id", plan).put("completed_steps", progress.length()).put("steps", progress)
            .put("local_tool_calls", localCalls).put("model_requests_inside", 0).put("untrusted", true).apply {
                if (latest != null) put("observation", latest)
                if (error != null) put("error", error)
                if (reason != null) put("halt_reason", reason)
            }
        repeat(steps.length()) { index ->
            currentCoroutineContext().ensureActive()
            val step = steps.getJSONObject(index)
            val action = step.getString("action")
            val identity = "$plan:$index"
            if (action in setOf("click", "type", "select")) {
                val prior = executor.confirmed(identity)
                if (prior != null) {
                    // Historical success is not proof of today's DOM. Stop rather than continue from stale state.
                    latest = invoke("browser_read", JSONObject())
                    if (latest!!.optBoolean("_awaiting_user")) return response("USER_REQUIRED").put("_awaiting_user", true).put("message", latest!!.optString("message")).toString()
                    if (latest!!.has("error")) return response(latest!!.getString("error"), "FINAL_READ_FAILED").toString()
                    return response(reason = "CONFIRMED_STEP_ALREADY_EXECUTED").put("reused_execution_id", prior.executionId).toString()
                }
            }
            val parameters = JSONObject(step.toString()).apply { remove("action"); remove("target"); remove("target_exact") }
            if (step.has("target")) {
                latest = invoke("browser_find", JSONObject().put("query", step.getString("target")).put("exact", step.optBoolean("target_exact", true)))
                if (latest!!.optBoolean("_awaiting_user")) return response("USER_REQUIRED").put("_awaiting_user", true).put("message", latest!!.optString("message")).toString()
                val matches = latest!!.optJSONArray("matches") ?: JSONArray()
                if (latest!!.has("error") || matches.length() != 1 || latest!!.optBoolean("matches_truncated"))
                    return response("TARGET_NOT_UNIQUE", "Модель должна уточнить элемент; клик или ввод не выполнены").toString()
                parameters.put("page_id", latest!!.getString("page_id")).put("ref", matches.getJSONObject(0).getInt("ref"))
            }
            latest = invoke("browser_$action", parameters, identity)
            if (latest!!.optBoolean("_awaiting_user")) return response("USER_REQUIRED").put("_awaiting_user", true).put("message", latest!!.optString("message")).toString()
            if (latest!!.has("error")) return response(latest!!.getString("error"), "STEP_FAILED").toString()
            progress.put(JSONObject().put("index", index + 1).put("action", action).put("status", "SUCCEEDED").put("trace_id", latest!!.optString("_local_trace_id")))
        }
        // Intermediate snapshots were never sent to the model. Its final observation must be self-contained.
        if (steps.getJSONObject(steps.length() - 1).getString("action") != "read") latest = invoke("browser_read", JSONObject())
        if (latest!!.optBoolean("_awaiting_user")) return response("USER_REQUIRED").put("_awaiting_user", true).put("message", latest!!.optString("message")).toString()
        if (latest!!.has("error")) return response(latest!!.getString("error"), "FINAL_READ_FAILED").toString()
        return response().toString()
    }
}
