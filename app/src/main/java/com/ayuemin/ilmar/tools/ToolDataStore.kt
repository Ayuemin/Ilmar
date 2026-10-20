package com.ayuemin.ilmar.tools

import kotlinx.coroutines.sync.withLock
import com.ayuemin.ilmar.settings.RuntimeLimits as Limits
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
        require(text.isNotBlank() && text.length <= Limits.get("memory_chars")) { "Запись памяти превышает глобальный лимит" }
        val db = writableDatabase
        db.rawQuery("SELECT rowid FROM memories WHERE CAST(chat AS INTEGER)=CAST(? AS INTEGER) AND body=?", arrayOf(chat.toString(), text)).use { if (it.moveToFirst()) return it.getLong(0).toString() }
        val count = db.rawQuery("SELECT count(*) FROM memories WHERE CAST(chat AS INTEGER)=CAST(? AS INTEGER)", arrayOf(chat.toString())).use { it.moveToFirst(); it.getInt(0) }
        require(Limits.get("memory_records") == 0 || count < Limits.get("memory_records")) { "Достигнут глобальный лимит записей памяти" }
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
    @Synchronized fun plan(chat: Long, steps: JSONArray?): String {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val old = db.rawQuery("SELECT payload FROM plans WHERE chat=?", arrayOf(chat.toString())).use { if (it.moveToFirst()) JSONArray(it.getString(0)) else JSONArray() }
            var upgraded = false
            // Upgrade legacy plan rows in place without changing text/status/order.
            val seen = mutableSetOf<String>()
            repeat(old.length()) { i ->
                val step = old.getJSONObject(i); val id = step.opt("id") as? String
                if (id.isNullOrBlank() || !seen.add(id)) { val fresh = java.util.UUID.randomUUID().toString(); step.put("id", fresh); seen.add(fresh); upgraded = true }
            }
            val result = if (steps == null) old else {
                require(steps.length() <= Limits.get("plan_steps") && steps.toString().length <= Limits.bytes("argument_kib")) { "План превышает глобальный лимит" }
                val used = mutableSetOf<String>()
                JSONArray().apply { repeat(steps.length()) { i ->
                    val input = steps.getJSONObject(i)
                    require(input.keys().asSequence().all { it in setOf("id", "text", "status") }) { "Неизвестное поле шага" }
                    val text = input.getString("text"); require(text.length in 1..500)
                    val id = input.optString("id")
                    val previous = (0 until old.length()).map { old.getJSONObject(it) }.firstOrNull {
                        it.getString("id") !in used && if (id.isNotBlank()) it.getString("id") == id else it.getString("text") == text
                    }
                    require(id.isBlank() || previous != null) { "Неизвестный или повторяющийся ID шага; для нового шага не передавай id" }
                    val stable = previous?.getString("id") ?: java.util.UUID.randomUUID().toString()
                    require(used.add(stable)) { "Повторяющийся ID шага" }
                    val status = if (input.has("status")) input.getString("status") else previous?.getString("status") ?: "pending"
                    require(status in setOf("pending", "in_progress", "completed")) { "Неизвестный статус шага" }
                    put(JSONObject().put("id", stable).put("text", text).put("status", status))
                } }
            }
            require((0 until result.length()).count { result.getJSONObject(it).getString("status") == "in_progress" } <= 1)
            if (upgraded || steps != null) db.execSQL("INSERT OR REPLACE INTO plans VALUES(?,?)", arrayOf(chat, result.toString()))
            db.setTransactionSuccessful()
            return result.toString()
        } finally { db.endTransaction() }
    }
    fun planNote(chat: Long, maxTokens: Int): String {
        val plan = JSONArray(plan(chat, null))
        if (plan.length() == 0) return ""
        val all = (0 until plan.length()).map { plan.getJSONObject(it) }
        val selected = all.sortedBy { when (it.getString("status")) { "in_progress" -> 0; "pending" -> 1; else -> 2 } }
        var note = "\nФактический план todo из SQLite; не заменяй его сводкой модели. Всего шагов ${all.size}, завершено ${all.count { it.getString("status") == "completed" }}. Полный план: todo без steps.\n"
        for (step in selected) {
            val line = "${step.getString("id")}: ${step.getString("status")} · ${step.getString("text").take(160)}\n"
            if (com.ayuemin.ilmar.memory.ContextEstimate.text(note + line) > maxTokens) break
            note += line
        }
        return note
    }
    private val cacheLock = kotlinx.coroutines.sync.Mutex()
    suspend fun cached(id: String, load: suspend () -> String): String = cacheLock.withLock {
        if (Limits.get("cache_minutes") == 0) return@withLock load()
        val now = System.currentTimeMillis()
        readableDatabase.rawQuery("SELECT payload FROM cache WHERE id=? AND created>?", arrayOf(id, (now - Limits.get("cache_minutes").toLong() * 60 * 1000).toString())).use { if (it.moveToFirst()) return@withLock it.getString(0) }
        val result = load()
        writableDatabase.execSQL("INSERT OR REPLACE INTO cache VALUES(?,?,?)", arrayOf<Any>(id, now, result))
        writableDatabase.execSQL("DELETE FROM cache WHERE id NOT IN (SELECT id FROM cache ORDER BY created DESC LIMIT ${Limits.get("cache_entries")})")
        result
    }
    fun log(chat: Long, tool: String, args: JSONObject, result: String, duration: Long, status: String) {
        writableDatabase.execSQL("INSERT INTO calls(chat,tool,args,result,latency,status,cost) VALUES(?,?,?,?,?,?,NULL)",
            arrayOf<Any>(chat, tool, com.ayuemin.ilmar.diagnostics.Redaction.json(args).toString().take(Limits.get("log_chars")), com.ayuemin.ilmar.diagnostics.Redaction.text(result).take(Limits.get("log_chars")), duration, status))
        writableDatabase.execSQL("DELETE FROM calls WHERE id NOT IN (SELECT id FROM calls ORDER BY id DESC LIMIT ${Limits.get("log_events")})")
    }
    fun exportCalls(writer: java.io.Writer) {
        readableDatabase.rawQuery("SELECT id,chat,tool,args,result,latency,status,cost FROM calls ORDER BY id", null).use { rows ->
            while (rows.moveToNext()) writer.append(JSONObject().put("event", "TOOL_LOG").put("id", rows.getLong(0)).put("chat", rows.getLong(1))
                .put("tool", rows.getString(2)).put("arguments", com.ayuemin.ilmar.diagnostics.Redaction.text(rows.getString(3)))
                .put("result", com.ayuemin.ilmar.diagnostics.Redaction.text(rows.getString(4))).put("latency_ms", rows.getLong(5))
                .put("status", rows.getString(6)).put("cost", if (rows.isNull(7)) JSONObject.NULL else rows.getString(7)).toString()).append('\n')
        }
    }
    fun clear(chat: Long) {
        writableDatabase.execSQL("DELETE FROM memories WHERE CAST(chat AS INTEGER)=CAST(? AS INTEGER)", arrayOf(chat))
        writableDatabase.execSQL("DELETE FROM plans WHERE chat=?", arrayOf(chat))
        writableDatabase.execSQL("DELETE FROM calls WHERE chat=?", arrayOf(chat))
    }
}
