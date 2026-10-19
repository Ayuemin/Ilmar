package com.ayuemin.ilmar.chat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ChatStore(context: Context) : SQLiteOpenHelper(context, "ilmar_chats.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE chats (id INTEGER PRIMARY KEY, title TEXT NOT NULL)")
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER NOT NULL, text TEXT NOT NULL, from_user INTEGER NOT NULL, FOREIGN KEY(chat_id) REFERENCES chats(id))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations must preserve existing chats.
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
