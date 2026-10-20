package com.ayuemin.ilmar.attachments

import org.json.JSONArray
import org.json.JSONObject

/** Private-file identifiers, never external URIs or absolute paths in provider requests. */
data class ChatAttachment(val id: String, val name: String, val mime: String, val size: Long,
    val text: String? = null, val images: List<String> = emptyList(), val version: Int? = null, val previousId: String? = null, val versionKey: String? = null, val browserObservation: Boolean = false) {
    fun json() = JSONObject().put("id", id).put("name", name).put("mime", mime).put("size", size)
        .put("text", text).put("images", JSONArray(images)).apply { version?.let { put("version", it) }; previousId?.let { put("previous_file_id", it) }; versionKey?.let { put("version_key", it) }; if (browserObservation) put("browser_observation", true) }
    companion object {
        fun encode(items: List<ChatAttachment>) = JSONArray().apply { items.forEach { put(it.json()) } }.toString()
        fun decode(raw: String?): List<ChatAttachment> = if (raw.isNullOrBlank()) emptyList() else {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val j = a.getJSONObject(i); val images = j.getJSONArray("images")
                ChatAttachment(j.getString("id"), j.getString("name"), j.getString("mime"), j.getLong("size"),
                    j.optString("text").takeUnless { it == "null" || !j.has("text") },
                    (0 until images.length()).map { images.getString(it) }, j.optInt("version", 0).takeIf { it > 0 }, j.optString("previous_file_id").takeIf { it.isNotBlank() && it != "null" }, j.optString("version_key").takeIf { it.isNotBlank() && it != "null" }, j.optBoolean("browser_observation"))
            }
        }
    }
}
