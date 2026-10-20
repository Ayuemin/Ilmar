package com.ayuemin.ilmar.attachments

import org.json.JSONArray
import org.json.JSONObject

/** Private-file identifiers, never external URIs or absolute paths in provider requests. */
data class ChatAttachment(val id: String, val name: String, val mime: String, val size: Long,
    val text: String? = null, val images: List<String> = emptyList()) {
    fun json() = JSONObject().put("id", id).put("name", name).put("mime", mime).put("size", size)
        .put("text", text).put("images", JSONArray(images))
    companion object {
        fun encode(items: List<ChatAttachment>) = JSONArray().apply { items.forEach { put(it.json()) } }.toString()
        fun decode(raw: String?): List<ChatAttachment> = if (raw.isNullOrBlank()) emptyList() else {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val j = a.getJSONObject(i); val images = j.getJSONArray("images")
                ChatAttachment(j.getString("id"), j.getString("name"), j.getString("mime"), j.getLong("size"),
                    j.optString("text").takeUnless { it == "null" || !j.has("text") },
                    (0 until images.length()).map { images.getString(it) })
            }
        }
    }
}
