package com.ayuemin.ilmar.chat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ChatStore(context: Context) : SQLiteOpenHelper(context, "ilmar_chats.db", null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE chats (id INTEGER PRIMARY KEY, title TEXT NOT NULL)")
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER NOT NULL, text TEXT NOT NULL, from_user INTEGER NOT NULL, FOREIGN KEY(chat_id) REFERENCES chats(id))")
        createUsage(db)
        createDetails(db)
        createContext(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createUsage(db)
        if (oldVersion < 3) createDetails(db)
        if (oldVersion < 4) createContext(db)
    }

    private fun createContext(db: SQLiteDatabase) {
        com.ayuemin.ilmar.memory.ContextStore.create(db)
        db.execSQL("ALTER TABLE request_usage ADD COLUMN purpose TEXT NOT NULL DEFAULT 'answer'")
        db.execSQL("ALTER TABLE request_usage ADD COLUMN estimated_saved INTEGER NOT NULL DEFAULT 0")
    }

    private fun createDetails(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN attachments TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN reply_info TEXT")
        db.execSQL("CREATE TABLE draft_attachments (chat_id INTEGER PRIMARY KEY, payload TEXT NOT NULL)")
    }

    fun drafts(): Map<Long, List<com.ayuemin.ilmar.attachments.ChatAttachment>> = buildMap {
        readableDatabase.rawQuery("SELECT chat_id, payload FROM draft_attachments", null).use { rows ->
            while (rows.moveToNext()) put(rows.getLong(0), com.ayuemin.ilmar.attachments.ChatAttachment.decode(rows.getString(1)))
        }
    }
    fun saveDraft(chatId: Long, attachments: List<com.ayuemin.ilmar.attachments.ChatAttachment>) {
        if (attachments.isEmpty()) writableDatabase.execSQL("DELETE FROM draft_attachments WHERE chat_id = ?", arrayOf(chatId))
        else writableDatabase.execSQL("INSERT OR REPLACE INTO draft_attachments VALUES(?, ?)",
            arrayOf<Any>(chatId, com.ayuemin.ilmar.attachments.ChatAttachment.encode(attachments)))
    }
    fun branch(source: Long, target: Long, through: Long, title: String) {
        writableDatabase.beginTransaction()
        try {
            require(load().first { it.id == source }.messages.any { it.id == through })
            create(target, title)
            writableDatabase.execSQL("INSERT INTO messages(chat_id,text,from_user,attachments,reply_info) SELECT ?,text,from_user,attachments,reply_info FROM messages WHERE chat_id = ? AND id <= ? ORDER BY id", arrayOf(target, source, through))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    private fun createUsage(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS request_usage (id TEXT PRIMARY KEY, chat_id INTEGER NOT NULL, status TEXT NOT NULL, input_tokens INTEGER, output_tokens INTEGER, total_tokens INTEGER, cached_tokens INTEGER, reasoning_tokens INTEGER, cost_usd TEXT)")
    }

    fun recordUsage(chatId: Long, requestId: String, usage: com.ayuemin.ilmar.model.TokenUsage, status: String, purpose: String = "answer", estimatedSaved: Int = 0,
        completion: com.ayuemin.ilmar.model.GenerationResult? = null) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO request_usage SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM messages WHERE chat_id = ?)",
                arrayOf<Any?>(requestId, chatId, status, usage.input, usage.output, usage.total, usage.cached, usage.reasoning, usage.costUsd, purpose, estimatedSaved, chatId))
            // The paid Compact result and its usage become durable together. A restart can validate it without buying the same packet again.
            if (purpose == "compact" && completion != null) {
                val memory = com.ayuemin.ilmar.memory.ContextStore(this)
                memory.checkpoint(chatId)?.let { raw ->
                    memory.checkpoint(chatId, org.json.JSONObject(raw).put("pending_result", completion.text.take(20000))
                        .put("pending_finish", completion.finishReason).put("pending_tools", completion.toolCalls.isNotEmpty()))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun usage(chatId: Long): UsageLedger {
        val records = mutableListOf<UsageRecord>()
        readableDatabase.rawQuery("SELECT status, input_tokens, output_tokens, total_tokens, cached_tokens, reasoning_tokens, cost_usd, purpose, estimated_saved FROM request_usage WHERE chat_id = ?", arrayOf(chatId.toString())).use { rows ->
            fun nullable(index: Int): Long? = if (rows.isNull(index)) null else rows.getLong(index)
            while (rows.moveToNext()) records += UsageRecord(com.ayuemin.ilmar.model.TokenUsage(
                nullable(1), nullable(2), nullable(3), nullable(4), nullable(5), if (rows.isNull(6)) null else rows.getString(6)), rows.getString(0), rows.getString(7), rows.getInt(8))
        }
        return UsageLedger(records)
    }

    fun load(): List<ChatSession> {
        val result = mutableListOf<ChatSession>()
        readableDatabase.rawQuery("SELECT id, title FROM chats ORDER BY id", null).use { chats ->
            while (chats.moveToNext()) {
                val id = chats.getLong(0)
                val messages = mutableListOf<ChatMessage>()
                readableDatabase.rawQuery(
                    "SELECT text, from_user, id, attachments, reply_info FROM messages WHERE chat_id = ? ORDER BY id",
                    arrayOf(id.toString())
                ).use { rows ->
                    while (rows.moveToNext()) messages += ChatMessage(rows.getString(0), rows.getInt(1) != 0, rows.getLong(2), com.ayuemin.ilmar.attachments.ChatAttachment.decode(rows.getString(3)), ReplyInfo.parse(rows.getString(4)))
                }
                result += ChatSession(id, chats.getString(1), messages)
            }
        }
        return result
    }

    fun create(id: Long, title: String) {
        writableDatabase.execSQL("INSERT INTO chats(id, title) VALUES(?, ?)", arrayOf(id, title))
    }

    fun clearMessages(chatId: Long) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.execSQL("DELETE FROM messages WHERE chat_id = ?", arrayOf(chatId))
            writableDatabase.execSQL("DELETE FROM request_usage WHERE chat_id = ?", arrayOf(chatId))
            com.ayuemin.ilmar.memory.ContextStore(this).clear(chatId)
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun rename(chatId: Long, title: String) {
        writableDatabase.execSQL("UPDATE chats SET title = ? WHERE id = ?", arrayOf(title, chatId))
    }

    fun delete(chatId: Long) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.execSQL("DELETE FROM messages WHERE chat_id = ?", arrayOf(chatId))
            writableDatabase.execSQL("DELETE FROM request_usage WHERE chat_id = ?", arrayOf(chatId))
            com.ayuemin.ilmar.memory.ContextStore(this).clear(chatId)
            writableDatabase.execSQL("DELETE FROM draft_attachments WHERE chat_id = ?", arrayOf(chatId))
            writableDatabase.execSQL("DELETE FROM chats WHERE id = ?", arrayOf(chatId))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun append(chatId: Long, message: ChatMessage, newTitle: String?): ChatMessage {
        writableDatabase.beginTransaction()
        try {
            val values = android.content.ContentValues().apply {
                put("chat_id", chatId); put("text", message.text); put("from_user", if (message.fromUser) 1 else 0)
                put("attachments", com.ayuemin.ilmar.attachments.ChatAttachment.encode(message.attachments)); put("reply_info", message.info?.json())
            }
            val messageId = writableDatabase.insertOrThrow("messages", null, values)
            if (message.fromUser) writableDatabase.execSQL("DELETE FROM draft_attachments WHERE chat_id = ?", arrayOf(chatId))
            if (newTitle != null) writableDatabase.execSQL(
                "UPDATE chats SET title = ? WHERE id = ?", arrayOf(newTitle, chatId)
            )
            writableDatabase.setTransactionSuccessful()
            return message.copy(id = messageId)
        } finally {
            writableDatabase.endTransaction()
        }
    }
}

