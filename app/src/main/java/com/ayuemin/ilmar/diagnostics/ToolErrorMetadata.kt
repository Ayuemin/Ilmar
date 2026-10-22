package com.ayuemin.ilmar.diagnostics

import org.json.JSONObject
import java.security.MessageDigest

/** Failure evidence that survives metadata-only export without leaking tool arguments/documents. */
internal object ToolErrorMetadata {
    private val codes = setOf("INVALID_ARGUMENTS", "UNKNOWN_TOOL", "TOOL_TIMEOUT", "TOOL_IO_ERROR", "FETCH_FAILED", "DOCUMENT_UNAVAILABLE", "TOOL_RETRY_EXHAUSTED")
    fun summarize(round: List<JSONObject>): JSONObject {
        val calls = round.firstOrNull()?.optJSONArray("tool_calls") ?: return JSONObject()
        val errors = JSONObject()
        for (i in 0 until calls.length()) {
            val call = calls.getJSONObject(i)
            val result = round.firstOrNull { it.optString("role") == "tool" && it.optString("tool_call_id") == call.optString("id") }
                ?.let { runCatching { JSONObject(it.optString("content")) }.getOrNull() } ?: continue
            if (!result.has("error")) continue
            val function = call.getJSONObject("function")
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(function.optString("arguments").toByteArray())
                .joinToString("") { "%02x".format(it) }
            val code = result.optString("error").takeIf { it in codes } ?: "TOOL_FAILURE"
            errors.put("failure_$i", JSONObject().put("tool", function.optString("name")).put("error_code", code)
                .put("argument_fingerprint", fingerprint).put("retryable", result.optBoolean("retryable", code in setOf("TOOL_TIMEOUT", "TOOL_IO_ERROR"))))
        }
        return errors
    }
}
