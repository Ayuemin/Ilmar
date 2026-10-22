package com.ayuemin.ilmar.documents

import org.json.JSONArray
import org.json.JSONObject

/** Working prompt only: exact quotations survive; repeated provenance is moved to the result root. */
internal object DocumentHistoryProjection {
    fun compact(content: String): String {
        val data = runCatching { JSONObject(content) }.getOrNull() ?: return content
        val fragments = data.optJSONArray("fragments") ?: return content
        if (!data.optBoolean("untrusted")) return content
        val origins = JSONObject()
        for (i in 0 until fragments.length()) {
            val f = fragments.getJSONObject(i)
            val id = f.optString("document_id")
            if (id.isBlank()) continue
            if (!origins.has(id) && f.has("url")) origins.put(id, JSONObject().apply {
                listOf("url", "title", "version").forEach { key -> f.opt(key)?.let { put(key, it) } }
            })
            listOf("url", "title", "title_complete", "version", "offset_space", "kinds", "heuristic_score").forEach(f::remove)
        }
        if (origins.length() > 0) data.put("sources", origins)
        return data.toString()
    }
    fun references(data: JSONObject): String? {
        val fragments = data.optJSONArray("fragments") ?: return null
        val refs = JSONArray()
        for (i in 0 until fragments.length()) {
            val f = fragments.getJSONObject(i)
            refs.put(JSONObject().put("document_id", f.opt("document_id")).put("fragment_id", f.opt("fragment_id")))
        }
        return "document_id=${data.optString("document_id")}; windows=$refs; original quotes: document_read(document_id, section_id=fragment_id, repeat=true); saved trace also contains the original."
    }
}
