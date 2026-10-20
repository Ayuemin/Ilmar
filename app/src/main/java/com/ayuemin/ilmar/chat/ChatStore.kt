package com.ayuemin.ilmar.chat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ChatStore(context: Context) : SQLiteOpenHelper(context, "ilmar_chats.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE chats (id INTEGER PRIMARY KEY, title TEXT NOT NULL)")
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER NOT NULL, text TEXT NOT NULL, from_user INTEGER NOT NULL, FOREIGN KEY(chat_id) REFERENCES chats(id))")
        createUsage(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createUsage(db)
    }

    private fun createUsage(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS request_usage (id TEXT PRIMARY KEY, chat_id INTEGER NOT NULL, status TEXT NOT NULL, input_tokens INTEGER, output_tokens INTEGER, total_tokens INTEGER, cached_tokens INTEGER, reasoning_tokens INTEGER, cost_usd TEXT)")
    }

    fun recordUsage(chatId: Long, requestId: String, usage: com.ayuemin.ilmar.model.TokenUsage, status: String) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO request_usage SELECT ?, ?, ?, ?, ?, ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM messages WHERE chat_id = ?)",
            arrayOf<Any?>(requestId, chatId, status, usage.input, usage.output, usage.total, usage.cached, usage.reasoning, usage.costUsd, chatId))
    }

    fun usage(chatId: Long): UsageLedger {
        val records = mutableListOf<UsageRecord>()
        readableDatabase.rawQuery("SELECT status, input_tokens, output_tokens, total_tokens, cached_tokens, reasoning_tokens, cost_usd FROM request_usage WHERE chat_id = ?", arrayOf(chatId.toString())).use { rows ->
            fun nullable(index: Int): Long? = if (rows.isNull(index)) null else rows.getLong(index)
            while (rows.moveToNext()) records += UsageRecord(com.ayuemin.ilmar.model.TokenUsage(
                nullable(1), nullable(2), nullable(3), nullable(4), nullable(5), if (rows.isNull(6)) null else rows.getString(6)), rows.getString(0))
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
                    "SELECT text, from_user FROM messages WHERE chat_id = ? ORDER BY id",
                    arrayOf(id.toString())
                ).use { rows ->
                    while (rows.moveToNext()) messages += ChatMessage(rows.getString(0), rows.getInt(1) != 0)
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
            writableDatabase.execSQL("DELETE FROM chats WHERE id = ?", arrayOf(chatId))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun append(chatId: Long, message: ChatMessage, newTitle: String?) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.execSQL(
                "INSERT INTO messages(chat_id, text, from_user) VALUES(?, ?, ?)",
                arrayOf(chatId, message.text, if (message.fromUser) 1 else 0)
            )
            if (newTitle != null) writableDatabase.execSQL(
                "UPDATE chats SET title = ? WHERE id = ?", arrayOf(newTitle, chatId)
            )
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }
}

