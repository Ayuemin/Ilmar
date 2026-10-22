package com.ayuemin.ilmar.documents

import com.ayuemin.ilmar.agent.AgentFailure
import com.ayuemin.ilmar.agent.BoundTool
import com.ayuemin.ilmar.browser.BrowserAddress
import com.ayuemin.ilmar.browser.SearchRoute
import com.ayuemin.ilmar.model.ToolSchema
import org.json.JSONArray
import org.json.JSONObject

/** An additional experimental restriction; original executors still perform every authorization/consent check. */
internal object ExperimentalBrowserGate {
    fun route(route: SearchRoute, policy: ExperimentalBrowser) =
        if (route == SearchRoute.BROWSER && policy != ExperimentalBrowser.EXISTING) SearchRoute.OFF else route
    fun tools(tools: List<BoundTool>, policy: ExperimentalBrowser, approve: suspend (String, String) -> Boolean): List<BoundTool> {
        if (policy == ExperimentalBrowser.EXISTING) return tools
        if (policy == ExperimentalBrowser.DISABLED) return emptyList()
        var opened = false
        val reasons = setOf("javascript", "authentication", "interaction")
        return tools.filterNot { it.schema.name in setOf("browser_search", "browser_batch") }.map { original ->
            val open = original.schema.name == "browser_open"
            val schema = JSONObject(original.schema.parameters.toString())
            if (open) {
                schema.getJSONObject("properties").put("reason", JSONObject().put("type", "string").put("enum", JSONArray(reasons.toList())))
                schema.getJSONArray("required").put("reason")
            }
            fun cleaned(args: JSONObject) = JSONObject(args.toString()).apply { if (open) remove("reason") }
            var approved: String? = null
            suspend fun prepare(args: JSONObject) {
                if (!open && !opened) throw AgentFailure("BROWSER_ESCALATION_REQUIRED", "Сначала обоснуй browser_open: javascript/authentication/interaction")
                val clean = cleaned(args)
                original.beforeExecute(clean) // Original global/chat authorization and session consent come first.
                if (open) {
                    require(args.opt("reason") is String && args.getString("reason") in reasons && BrowserAddress.valid(args.getString("url")))
                    val key = args.toString()
                    if (approved != key) {
                        if (!approve(args.getString("url"), args.getString("reason"))) throw AgentFailure("BROWSER_ESCALATION_DENIED", "Эскалация браузера отменена")
                        original.beforeExecute(clean) // Consent never overrides revocation.
                        approved = key
                    }
                }
            }
            BoundTool(ToolSchema(original.schema.name, original.schema.description + if (open) " Ограниченная эскалация: обязательны reason и подтверждение пользователя." else "", schema),
                localPrimitive = original.localPrimitive, beforeExecute = { prepare(it) }, execute = { args ->
                    prepare(args); approved = null
                    val result = original.execute(cleaned(args))
                    if (open) opened = runCatching { !JSONObject(result).has("error") }.getOrDefault(false)
                    result
                })
        }
    }
}
