package com.ayuemin.ilmar.agent

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Umnik's repeat/progress guard, with canonical JSON instead of collapsing whitespace in string values. */
internal class AgentToolLoopGuard(private val threshold: Int = 3, private val maxTrace: Int = 36) {
    private val trace = mutableListOf<String>()
    private var strikes = 0
    private var progress = 0
    fun observe(name: String, arguments: String, result: String): Boolean {
        trace += "$name:${fingerprint(arguments)}:${fingerprint(result)}"
        while (trace.size > maxTrace) trace.removeAt(0)
        val repeated = (1..4).any { size ->
            val count = threshold.coerceAtLeast(2)
            val tail = trace.takeLast(size * count)
            tail.size == size * count && (1 until count).all { part -> tail.subList(part * size, (part + 1) * size) == tail.take(size) }
        }
        if (!repeated) { if (++progress >= 8) { strikes = 0; progress = 0 }; return false }
        progress = 0; trace.clear(); strikes++
        return strikes >= 2
    }
    private fun fingerprint(raw: String): String {
        val value = runCatching { canonical(JSONObject(raw)) }.getOrElse { raw }
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.opt(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
}
