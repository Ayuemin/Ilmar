package com.ayuemin.ilmar.agent

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal fun toolArgumentsFingerprint(args: String): String {
    fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.opt(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
    val raw = runCatching { canonical(JSONObject(args)) }.getOrDefault(args)
    return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Run-owned failure budget. Independent of changing error text and successful reads. */
internal class ToolFailureGuard {
    private data class Failure(val code: String, val retryable: Boolean, val attempts: Int)
    private val failures = mutableMapOf<String, Failure>()
    private val invalidCorrections = mutableMapOf<String, Int>()
    fun correctionBudgetExhausted(name: String) = (invalidCorrections[name] ?: 0) >= 4
    private fun key(name: String, args: String) = name + ":" + toolArgumentsFingerprint(args)
    fun blocked(name: String, args: String): String? {
        val prior = failures[key(name, args)] ?: return null
        if (prior.retryable && prior.attempts < 2) return null
        return JSONObject().put("error", "TOOL_RETRY_EXHAUSTED").put("cause", prior.code).put("retryable", false)
            .put("message", "Identical failed call blocked. Use available evidence and explain the limitation; do not repeat this call.").toString()
    }
    fun observe(name: String, args: String, output: String) {
        val data = runCatching { JSONObject(output) }.getOrNull() ?: return
        val signature = key(name, args)
        if (!data.has("error")) { failures.remove(signature); return }
        val code = data.optString("error")
        if (code == "INVALID_ARGUMENTS") invalidCorrections[name] = (invalidCorrections[name] ?: 0) + 1
        val transient = data.optBoolean("retryable", code in setOf("TOOL_TIMEOUT", "TOOL_IO_ERROR"))
        failures[signature] = Failure(code, transient, (failures[signature]?.attempts ?: 0) + 1)
    }
}
