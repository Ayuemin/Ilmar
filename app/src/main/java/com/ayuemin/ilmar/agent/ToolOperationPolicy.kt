package com.ayuemin.ilmar.agent

import org.json.JSONArray
import org.json.JSONObject

/** Only trusted built-in reads bypass the unresolved-side-effect gate. Unknown tools fail closed. */
internal object ToolOperationPolicy {
    fun safeToRepeat(name: String, arguments: String): Boolean = when (name) {
        "browser_batch", "browser_find", "browser_wait", "browser_screenshot", "browser_takeover", "browser_back", "browser_search", "browser_open", "browser_read", "browser_scroll", "calculator", "read_file", "fetch_url", "web_search", "search_history", "recall_memory", "ask_user" -> true
        "github" -> runCatching { val args = JSONObject(arguments); args.getString("method") == "GET" && com.ayuemin.ilmar.github.GitHubClient.validate("GET", args.getString("path"), args.optString("body")).host == "api.github.com" }.getOrDefault(false)
        "todo" -> runCatching { !JSONObject(arguments).has("steps") }.getOrDefault(false)
        else -> false
    }

    // Preserve strings and array order. Object whitespace/key order are not operation identity.
    fun canonical(arguments: String): String = runCatching { canonicalValue(JSONObject(arguments)) }.getOrElse { arguments }
    private fun canonicalValue(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonicalValue(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalValue(value.get(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
}
