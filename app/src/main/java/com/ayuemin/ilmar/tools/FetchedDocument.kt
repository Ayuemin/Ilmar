package com.ayuemin.ilmar.tools

import org.json.JSONObject

/** Constructed only after the bounded transport has reached EOF successfully. */
internal data class FetchedDocument(val url: String, val mediaType: String, val source: String, val legacyText: String) {
    fun legacyResult(chars: Int): String = JSONObject().put("url", url).put("text", legacyText.take(chars))
        .put("truncated", legacyText.length > chars).put("untrusted", true).toString()
    val retainedBytes: Long get() = 2L * (url.length + mediaType.length + source.length + legacyText.length)
}
