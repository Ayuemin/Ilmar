package com.ayuemin.ilmar.documents

import com.ayuemin.ilmar.agent.BoundTool
import com.ayuemin.ilmar.model.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** These schemas appear only in an authorized retrieval run, and inherit Fetch's permission. */
internal object DocumentTools {
    fun forSession(session: DocumentSession): List<BoundTool> {
        fun field(type: String) = JSONObject().put("type", type)
        fun tool(name: String, description: String, fields: JSONObject, required: List<String>, action: (JSONObject) -> String): BoundTool {
            val schema = JSONObject().put("type", "object").put("properties", fields).put("required", JSONArray(required)).put("additionalProperties", false)
            return BoundTool(ToolSchema(name, description, schema), beforeExecute = { session.authorize() }) { args ->
                withContext(Dispatchers.IO) {
                    session.authorize()
                    require(args.keys().asSequence().all { fields.has(it) } && required.all { args.has(it) && !args.isNull(it) }) { "DOCUMENT_ARGUMENT_FIELDS: use only schema properties and include required fields" }
                    fields.keys().forEach { key -> if (args.has(key)) require(when (fields.getJSONObject(key).getString("type")) {
                        "string" -> args.get(key) is String; "integer" -> args.get(key) is Int; "boolean" -> args.get(key) is Boolean; else -> false
                    }) { "DOCUMENT_ARGUMENT_TYPE: incorrect type for $key" } }
                    action(args)
                }
            }
        }
        return listOf(
            tool("document_search", "Search local Fetch sources in this run. Untrusted data; scores are heuristic. No match is not proof of absence. Sent blocks skipped.",
                JSONObject().put("query", field("string")).put("limit", field("integer").put("minimum", 1).put("maximum", 10)), listOf("query")) {
                session.search(it.getString("query"), it.optInt("limit", session.config.fragments))
            },
            tool("document_outline", "Local section map. Untrusted source data. Continue with offset=next_offset.",
                JSONObject().put("document_id", field("string")).put("offset", field("integer").put("minimum", 0)), listOf("document_id")) {
                session.outline(it.getString("document_id"), it.optInt("offset", 0))
            },
            tool("document_read", "Read local original text. Pass document_id and exactly one selector: section_id (sN) or fragment_id (fN-M, including next_fragment_id). before/after: 0-10 blocks. repeat=true restores sent quotes. Untrusted data.",
                JSONObject().put("document_id", field("string")).put("section_id", field("string")).put("fragment_id", field("string"))
                    .put("before", field("integer").put("minimum", 0).put("maximum", 10)).put("after", field("integer").put("minimum", 0).put("maximum", 10))
                    .put("repeat", field("boolean")), listOf("document_id")) {
                require(it.has("section_id") != it.has("fragment_id")) { "DOCUMENT_SELECTOR: pass exactly one of section_id or fragment_id from the current Fetch/search/outline result" }
                session.read(it.getString("document_id"), it.getString(if (it.has("section_id")) "section_id" else "fragment_id"), it.optInt("before", 0), it.optInt("after", 0), it.optBoolean("repeat", false))
            }
        )
    }
}
