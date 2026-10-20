package com.ayuemin.ilmar.tools

import kotlinx.coroutines.sync.withLock
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** Local bounded data. Memory and plans are scoped to a chat; network cache is local to this install. */
internal class ToolDataStore(context: Context) : SQLiteOpenHelper(context, "ilmar_tools.db", null, 1), java.io.Closeable {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE VIRTUAL TABLE memories USING fts4(chat,body,notindexed=chat)")
        db.execSQL("CREATE TABLE plans(chat INTEGER PRIMARY KEY,payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE cache(id TEXT PRIMARY KEY,created INTEGER NOT NULL,payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE calls(id INTEGER PRIMARY KEY,chat INTEGER,tool TEXT,args TEXT,result TEXT,latency INTEGER,status TEXT,cost TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun saveMemory(chat: Long, text: String): String {
        require(text.isNotBlank() && text.length <= 4000) { "Запись памяти: до 4000 символов" }
        val db = writableDatabase
        db.rawQuery("SELECT rowid FROM memories WHERE CAST(chat AS INTEGER)=CAST(? AS INTEGER) AND body=?", arrayOf(chat.toString(), text)).use { if (it.moveToFirst()) return it.getLong(0).toString() }
        val count = db.rawQuery("SELECT count(*) FROM memories WHERE CAST(chat AS INTEGER)=CAST(? AS INTEGER)", arrayOf(chat.toString())).use { it.moveToFirst(); it.getInt(0) }
        require(count < 200) { "Лимит: 200 записей памяти на чат" }
        val values = android.content.ContentValues().apply { put("chat", chat); put("body", text) }
        return db.insertOrThrow("memories", null, values).toString()
    }
    fun recall(chat: Long, query: String): String {
        require(query.length <= 1000)
        val words = Regex("[\\p{L}\\p{N}_]{2,}").findAll(query).map { it.value }.distinct().take(8).toList()
        if (words.isEmpty()) return "[]"
        return readableDatabase.rawQuery("SELECT rowid,body FROM memories WHERE memories MATCH ? AND CAST(chat AS INTEGER)=CAST(? AS INTEGER) LIMIT 8",
            arrayOf(words.joinToString(" OR ") { "\"$it\"" }, chat.toString())).use { rows ->
            JSONArray().apply { while (rows.moveToNext()) put(JSONObject().put("id", rows.getLong(0)).put("text", rows.getString(1))) }.toString()
        }
    }
    fun plan(chat: Long, steps: JSONArray?): String {
        if (steps != null) {
            require(steps.length() <= 30 && steps.toString().length <= 12000) { "План: до 30 шагов и 12 тысяч символов" }
            repeat(steps.length()) { i -> val step = steps.getJSONObject(i)
                require(step.getString("text").length in 1..500 && step.getString("status") in setOf("pending", "in_progress", "completed")) }
            require((0 until steps.length()).count { steps.getJSONObject(it).getString("status") == "in_progress" } <= 1)
            writableDatabase.execSQL("INSERT OR REPLACE INTO plans VALUES(?,?)", arrayOf<Any>(chat, steps.toString()))
        }
        return readableDatabase.rawQuery("SELECT payload FROM plans WHERE chat=?", arrayOf(chat.toString())).use { if (it.moveToFirst()) it.getString(0) else "[]" }
    }
    private val cacheLock = kotlinx.coroutines.sync.Mutex()
    suspend fun cached(id: String, load: suspend () -> String): String = cacheLock.withLock {
        val now = System.currentTimeMillis()
        readableDatabase.rawQuery("SELECT payload FROM cache WHERE id=? AND created>?", arrayOf(id, (now - 5 * 60 * 1000).toString())).use { if (it.moveToFirst()) return@withLock it.getString(0) }
        val result = load()
        writableDatabase.execSQL("INSERT OR REPLACE INTO cache VALUES(?,?,?)", arrayOf<Any>(id, now, result))
        writableDatabase.execSQL("DELETE FROM cache WHERE id NOT IN (SELECT id FROM cache ORDER BY created DESC LIMIT 32)")
        result
    }
    fun log(chat: Long, tool: String, args: JSONObject, result: String, duration: Long, status: String) {
        writableDatabase.execSQL("INSERT INTO calls(chat,tool,args,result,latency,status,cost) VALUES(?,?,?,?,?,?,NULL)",
            arrayOf<Any>(chat, tool, args.toString().take(2048), result.take(2048), duration, status))
        writableDatabase.execSQL("DELETE FROM calls WHERE id NOT IN (SELECT id FROM calls ORDER BY id DESC LIMIT 100)")
    }
    fun clear(chat: Long) {
        writableDatabase.execSQL("DELETE FROM memories WHERE CAST(chat AS INTEGER)=CAST(? AS INTEGER)", arrayOf(chat))
        writableDatabase.execSQL("DELETE FROM plans WHERE chat=?", arrayOf(chat))
        writableDatabase.execSQL("DELETE FROM calls WHERE chat=?", arrayOf(chat))
    }
}
