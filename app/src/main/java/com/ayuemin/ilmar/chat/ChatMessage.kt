package com.ayuemin.ilmar.chat

import com.ayuemin.ilmar.attachments.ChatAttachment
import com.ayuemin.ilmar.model.TokenUsage
import org.json.JSONObject

data class ChatMessage(val text: String, val fromUser: Boolean, val id: Long = 0,
    val attachments: List<ChatAttachment> = emptyList(), val info: ReplyInfo? = null)

data class ReplyInfo(val model: String, val provider: String, val createdAt: Long, val durationMs: Long,
    val usage: TokenUsage, val requests: Int, val incompleteUsage: Boolean = false) {
    fun json() = JSONObject().put("model", model).put("provider", provider).put("created", createdAt)
        .put("incomplete", incompleteUsage).put("duration", durationMs).put("requests", requests).put("input", usage.input).put("output", usage.output)
        .put("total", usage.total).put("cached", usage.cached).put("reasoning", usage.reasoning).put("cost", usage.costUsd).toString()
    companion object {
        fun parse(raw: String?): ReplyInfo? = raw?.let { runCatching {
            val j = JSONObject(it)
            fun number(key: String) = if (j.isNull(key)) null else j.optLong(key)
            ReplyInfo(j.getString("model"), j.getString("provider"), j.getLong("created"), j.getLong("duration"),
                TokenUsage(number("input"), number("output"), number("total"), number("cached"), number("reasoning"),
                    j.optString("cost").takeIf { cost -> cost.isNotBlank() && cost != "null" }), j.getInt("requests"), j.optBoolean("incomplete"))
        }.getOrNull() }
    }
}
