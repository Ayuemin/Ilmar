package com.ayuemin.ilmar.memory

import android.database.sqlite.SQLiteDatabase
import com.ayuemin.ilmar.settings.RuntimeLimits as Limits
import com.ayuemin.ilmar.chat.ChatStore
import org.json.JSONObject

internal data class Capsule(val id: Long, val through: Long, val text: String, val hash: String, val created: Long,
    val before: Int, val after: Int, val active: Boolean)
internal data class MemorySnapshot(val rules: String, val capsules: List<Capsule>, val checkpoint: String?)
internal class ContextStore(private val store: ChatStore) {
    fun recoveryNote(chat: Long): String = store.recoveryNote(chat)
    fun saveToolTrace(chat: Long, id: String, messages: List<JSONObject>) = store.saveToolTrace(chat, id, messages)
    fun checkpointToolExecutions(chat: Long, id: String, messages: List<JSONObject>) = store.checkpointExecutions(chat, id, messages)
    private val db get() = store.writableDatabase
    fun rules(chat: Long): String = db.rawQuery("SELECT text FROM context_rules WHERE chat_id=?", arrayOf(chat.toString())).use {
        if (it.moveToFirst()) it.getString(0) else ""
    }
    fun setRules(chat: Long, text: String) {
        require(text.length <= Limits.get("rules_chars")) { "Правила превышают глобальный лимит; измени расширенные настройки" }
        db.execSQL("INSERT OR REPLACE INTO context_rules VALUES(?,?)", arrayOf(chat, text))
        db.execSQL("DELETE FROM context_checkpoint WHERE chat_id=?", arrayOf(chat))
    }
    fun snapshot(chat: Long): MemorySnapshot {
        val capsules = mutableListOf<Capsule>()
        db.rawQuery("SELECT id,through_id,text,source_hash,created,before_tokens,after_tokens,active FROM context_capsules WHERE chat_id=? ORDER BY id DESC LIMIT 20", arrayOf(chat.toString())).use {
            while (it.moveToNext()) capsules += Capsule(it.getLong(0), it.getLong(1), it.getString(2), it.getString(3), it.getLong(4), it.getInt(5), it.getInt(6), it.getInt(7) != 0)
        }
        return MemorySnapshot(rules(chat), capsules, checkpoint(chat))
    }
    fun active(chat: Long) = snapshot(chat).capsules.firstOrNull { it.active }
    fun checkpoint(chat: Long): String? = db.rawQuery("SELECT payload FROM context_checkpoint WHERE chat_id=?", arrayOf(chat.toString())).use { if (it.moveToFirst()) it.getString(0) else null }
    fun checkpoint(chat: Long, data: JSONObject) {
        db.execSQL("INSERT OR REPLACE INTO context_checkpoint SELECT ?,? WHERE EXISTS(SELECT 1 FROM messages WHERE chat_id=? AND id=?)",
            arrayOf(chat, data.toString(), chat, data.getLong("through")))
    }
    fun interrupt(chat: Long) {
        checkpoint(chat)?.let { checkpoint(chat, JSONObject(it).put("state", "interrupted")) }
    }
    fun clearCheckpoint(chat: Long) { db.execSQL("DELETE FROM context_checkpoint WHERE chat_id=?", arrayOf(chat)) }
    fun save(chat: Long, through: Long, text: String, hash: String, before: Int, after: Int) {
        db.beginTransaction()
        try {
            val exists = db.rawQuery("SELECT 1 FROM messages WHERE chat_id=? AND id=?", arrayOf(chat.toString(), through.toString())).use { it.moveToFirst() }
            if (!exists) throw ContextFailure("История была очищена. Результат сжатия не сохранён.")
            db.execSQL("UPDATE context_capsules SET active=0 WHERE chat_id=?", arrayOf(chat))
            db.execSQL("INSERT INTO context_capsules(chat_id,through_id,text,source_hash,created,before_tokens,after_tokens,active) VALUES(?,?,?,?,?,?,?,1)",
                arrayOf(chat, through, text, hash, System.currentTimeMillis(), before, after))
            clearCheckpoint(chat); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun delete(chat: Long, id: Long) { db.execSQL("DELETE FROM context_capsules WHERE chat_id=? AND id=?", arrayOf(chat, id)); clearCheckpoint(chat) }
    fun activate(chat: Long, id: Long) {
        db.beginTransaction()
        try {
            db.execSQL("UPDATE context_capsules SET active=CASE WHEN id=? THEN 1 ELSE 0 END WHERE chat_id=?", arrayOf(id, chat))
            clearCheckpoint(chat); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun clear(chat: Long) {
        db.execSQL("DELETE FROM context_capsules WHERE chat_id=?", arrayOf(chat))
        db.execSQL("DELETE FROM context_rules WHERE chat_id=?", arrayOf(chat)); clearCheckpoint(chat)
    }
    /** Indexed search is local and never calls a model. Old rows are indexed lazily on a worker. */
    fun search(chat: Long, query: String, through: Long = Long.MAX_VALUE): String {
        val words = Regex("[\\p{L}\\p{N}_]{2,}").findAll(query).map { it.value }.distinct().take(6).toList()
        if (words.isEmpty()) return ""
        db.execSQL("INSERT INTO message_search(rowid,chat_id,body) SELECT id,chat_id,text || ' ' || coalesce(attachments,'') FROM messages WHERE id NOT IN (SELECT rowid FROM message_search)")
        return db.rawQuery("SELECT rowid,snippet(message_search,'','', ' … ', -1, 30) FROM message_search WHERE message_search MATCH ? AND CAST(chat_id AS INTEGER)=CAST(? AS INTEGER) AND rowid<=? ORDER BY rowid DESC LIMIT ?",
            arrayOf(words.joinToString(" OR ") { "\"$it\"" }, chat.toString(), through.toString(), Limits.get("retrieval_hits").toString())).use { rows ->
            buildString { while (rows.moveToNext()) append("Сообщение ${rows.getLong(0)}: ${rows.getString(1).take(600)}\n") }.take(Limits.get("excerpt_chars"))
        }
    }
    companion object {
        fun create(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE context_capsules(id INTEGER PRIMARY KEY AUTOINCREMENT,chat_id INTEGER NOT NULL,through_id INTEGER NOT NULL,text TEXT NOT NULL,source_hash TEXT NOT NULL,created INTEGER NOT NULL,before_tokens INTEGER NOT NULL,after_tokens INTEGER NOT NULL,active INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE context_rules(chat_id INTEGER PRIMARY KEY,text TEXT NOT NULL)")
            db.execSQL("CREATE TABLE context_checkpoint(chat_id INTEGER PRIMARY KEY,payload TEXT NOT NULL)")
            db.execSQL("CREATE VIRTUAL TABLE message_search USING fts4(chat_id,body,notindexed=chat_id)")
            db.execSQL("CREATE TRIGGER messages_search_insert AFTER INSERT ON messages BEGIN INSERT INTO message_search(rowid,chat_id,body) VALUES(new.id,new.chat_id,new.text || ' ' || coalesce(new.attachments,'')); END")
            db.execSQL("CREATE TRIGGER messages_search_delete AFTER DELETE ON messages BEGIN DELETE FROM message_search WHERE rowid=old.id; END")
            db.execSQL("CREATE TRIGGER messages_search_update AFTER UPDATE ON messages BEGIN DELETE FROM message_search WHERE rowid=old.id; INSERT INTO message_search(rowid,chat_id,body) VALUES(new.id,new.chat_id,new.text || ' ' || coalesce(new.attachments,'')); END")
        }
    }
}
