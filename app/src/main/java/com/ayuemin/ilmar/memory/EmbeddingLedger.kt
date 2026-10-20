package com.ayuemin.ilmar.memory

import android.database.sqlite.SQLiteDatabase
import com.ayuemin.ilmar.chat.ChatStore
import com.ayuemin.ilmar.model.TokenUsage
import org.json.JSONObject

/** Reported usage only. Unknown prices/tokens never turn into a zero-cost claim. */
internal class EmbeddingLedger(private val store: ChatStore) {
    fun start(chat: Long, purpose: String, model: String, inputs: Int, characters: Int): String {
        val id = java.util.UUID.randomUUID().toString()
        store.writableDatabase.execSQL("INSERT INTO embedding_requests SELECT ?,?,?,?,?,?,'started',NULL WHERE EXISTS(SELECT 1 FROM chats WHERE id=?)",
            arrayOf(id, chat, purpose, model, inputs, characters, chat))
        return id
    }
    fun finish(id: String, status: String, usage: TokenUsage? = null) {
        store.writableDatabase.execSQL("UPDATE embedding_requests SET status=?,usage=? WHERE id=? AND status='started'",
            arrayOf(status, usage?.let { json(it).toString() }, id))
    }
    fun interruptOpen() { store.writableDatabase.execSQL("UPDATE embedding_requests SET status='interrupted' WHERE status='started'") }
    fun summary(chat: Long): JSONObject {
        var calls = 0; var known = 0; var tokens: Long? = null; var cached: Long? = null
        var cost = java.math.BigDecimal.ZERO
        store.readableDatabase.rawQuery("SELECT usage FROM embedding_requests WHERE chat_id=?", arrayOf(chat.toString())).use { rows ->
            while (rows.moveToNext()) {
                calls++
                val u = rows.getString(0)?.let { JSONObject(it) }
                u?.opt("input_tokens")?.toString()?.toLongOrNull()?.let { tokens = (tokens ?: 0) + it }
                u?.opt("cached_tokens")?.toString()?.toLongOrNull()?.let { cached = (cached ?: 0) + it }
                u?.optString("cost_usd")?.toBigDecimalOrNull()?.let { known++; cost += it }
            }
        }
        return JSONObject().put("calls", calls).put("input_tokens", tokens ?: JSONObject.NULL).put("cached_tokens", cached ?: JSONObject.NULL)
            .put("known_cost_usd", if (known > 0) cost.toPlainString() else JSONObject.NULL).put("incomplete_cost", known < calls)
    }
    companion object {
        fun create(db: SQLiteDatabase) { db.execSQL("CREATE TABLE IF NOT EXISTS embedding_requests(id TEXT PRIMARY KEY,chat_id INTEGER NOT NULL,purpose TEXT NOT NULL,model TEXT NOT NULL,inputs INTEGER NOT NULL,characters INTEGER NOT NULL,status TEXT NOT NULL,usage TEXT)") }
        fun json(usage: TokenUsage) = JSONObject().put("input_tokens", usage.input ?: JSONObject.NULL).put("total_tokens", usage.total ?: JSONObject.NULL)
            .put("cached_tokens", usage.cached ?: JSONObject.NULL).put("cost_usd", usage.costUsd ?: JSONObject.NULL)
    }
}
