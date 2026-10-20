package com.ayuemin.ilmar.memory

import android.database.sqlite.SQLiteDatabase
import com.ayuemin.ilmar.chat.ChatStore
import com.ayuemin.ilmar.chat.ChatMessage
import com.ayuemin.ilmar.settings.RuntimeLimits as Limits
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue

/** Optional derived index. Originals stay in messages; deleting/editing them invalidates their vectors. */
internal class SemanticArchive(private val store: ChatStore) {
    private val db get() = store.writableDatabase
    fun count(chat: Long, space: String): Int = db.rawQuery("SELECT count(*) FROM semantic_chunks WHERE chat_id=? AND space=?", arrayOf(chat.toString(), space)).use { it.moveToFirst(); it.getInt(0) }
    suspend fun index(chat: Long, history: List<ChatMessage>, space: String, embed: suspend (List<String>) -> List<FloatArray>, progress: (Int) -> Unit = {}) =
        indexBatched(chat, history, space, embed, true, {}, progress)
    suspend fun indexBatched(chat: Long, history: List<ChatMessage>, space: String, embed: suspend (List<String>) -> List<FloatArray>,
        flushPartial: Boolean, deferred: (Int) -> Unit = {}, progress: (Int) -> Unit = {}) {
        data class Part(val message: Long, val hash: String, val index: Int, val text: String)
        val pending = mutableListOf<Part>()
        val sources = mutableListOf<Pair<Long, String>>()
        fun currentHash(message: Long): String? = db.rawQuery("SELECT text,attachments FROM messages WHERE chat_id=? AND id=?", arrayOf(chat.toString(), message.toString())).use { rows ->
            if (!rows.moveToFirst()) null else ContextEstimate.hash(rows.getString(0) + com.ayuemin.ilmar.attachments.ChatAttachment.decode(rows.getString(1)).joinToString("") { "\nФайл ${it.name}; file_id=${it.id}\n${it.text.orEmpty()}" })
        }
        suspend fun flush() {
            if (pending.isEmpty()) return
            currentCoroutineContext().ensureActive()
            val vectors = embed(pending.map { it.text })
            require(vectors.size == pending.size)
            db.beginTransaction()
            try {
                val hashes = pending.map { it.message }.distinct().associateWith { currentHash(it) }
                pending.zip(vectors).forEach { (part, vector) ->
                    // A response arriving after an edit/deletion must not resurrect a stale vector.
                    if (hashes[part.message] == part.hash) db.execSQL("INSERT OR REPLACE INTO semantic_chunks(chat_id,message_id,space,source_hash,part,body,vector) VALUES(?,?,?,?,?,?,?)",
                        arrayOf(chat, part.message, space, part.hash, part.index, part.text, encode(vector)))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            pending.clear(); progress(count(chat, space))
        }
        for (message in history) {
            currentCoroutineContext().ensureActive()
            if (message.attachments.isEmpty() && ContinuationPolicy.isControl(message.text)) {
                db.execSQL("DELETE FROM semantic_chunks WHERE chat_id=? AND message_id=? AND space=?", arrayOf(chat, message.id, space))
                db.execSQL("DELETE FROM semantic_sources WHERE chat_id=? AND message_id=? AND space=?", arrayOf(chat, message.id, space))
                continue
            }
            val source = message.text + message.attachments.joinToString("") { "\nФайл ${it.name}; file_id=${it.id}\n${it.text.orEmpty()}" }
            if (source.isBlank()) continue
            val hash = ContextEstimate.hash(source)
            val done = db.rawQuery("SELECT 1 FROM semantic_sources WHERE chat_id=? AND message_id=? AND space=? AND source_hash=?", arrayOf(chat.toString(), message.id.toString(), space, hash)).use { it.moveToFirst() }
            if (done) continue
            sources += message.id to hash
            val size = Limits.get("embedding_chars").coerceAtLeast(256)
            val chunks = generateSequence(0) { start -> if (start + size >= source.length) null else start + size - 200 }
            chunks.forEachIndexed { index, start ->
                val exists = db.rawQuery("SELECT 1 FROM semantic_chunks WHERE chat_id=? AND message_id=? AND space=? AND source_hash=? AND part=?", arrayOf(chat.toString(), message.id.toString(), space, hash, index.toString())).use { it.moveToFirst() }
                if (!exists) pending += Part(message.id, hash, index, source.substring(start, minOf(source.length, start + size)))
                if (pending.size >= Limits.get("embedding_batch")) flush()
            }
        }
        if (flushPartial) flush()
        val unfinished = pending.map { it.message }.toSet()
        deferred(pending.size)
        sources.forEach { (message, hash) ->
            if (message !in unfinished && currentHash(message) == hash) db.execSQL("INSERT OR REPLACE INTO semantic_sources VALUES(?,?,?,?)", arrayOf(chat, message, space, hash))
        }
        progress(count(chat, space))
    }
    fun queryVector(chat: Long, space: String, query: String): FloatArray? = db.rawQuery(
        "SELECT vector FROM embedding_queries WHERE chat_id=? AND space=? AND query_hash=?",
        arrayOf(chat.toString(), space, ContextEstimate.hash(query))).use { if (it.moveToFirst()) decode(it.getBlob(0)) else null }
    fun saveQueryVector(chat: Long, space: String, query: String, vector: FloatArray) {
        db.execSQL("INSERT OR REPLACE INTO embedding_queries SELECT ?,?,?,?,? WHERE EXISTS(SELECT 1 FROM chats WHERE id=?)",
            arrayOf(chat, space, ContextEstimate.hash(query), encode(vector), System.currentTimeMillis(), chat))
        db.execSQL("DELETE FROM embedding_queries WHERE chat_id=? AND rowid NOT IN (SELECT rowid FROM embedding_queries WHERE chat_id=? ORDER BY updated_at DESC,rowid DESC LIMIT 32)", arrayOf(chat, chat))
    }
    suspend fun search(chat: Long, space: String, query: FloatArray, through: Long = Long.MAX_VALUE): String {
        data class Hit(val score: Double, val id: Long, val part: Int, val text: String)
        val hits = PriorityQueue<Hit>(compareBy { it.score })
        val top = Limits.get("retrieval_hits")
        // Streaming exact cosine search: bounded RAM, no server vector database. Replaceable with ANN for measured scale.
        db.rawQuery("SELECT message_id,part,body,vector FROM semantic_chunks WHERE chat_id=? AND space=? AND message_id<=?", arrayOf(chat.toString(), space, through.toString())).use { rows ->
            while (rows.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val vector = decode(rows.getBlob(3)); if (vector.size != query.size) continue
                val score = vector.indices.sumOf { vector[it].toDouble() * query[it] }
                if (score > 0 && (hits.size < top || score > (hits.peek()?.score ?: Double.NEGATIVE_INFINITY))) {
                    hits.add(Hit(score, rows.getLong(0), rows.getInt(1), rows.getString(2)))
                    if (hits.size > top) hits.poll()
                }
            }
        }
        return hits.sortedByDescending { it.score }.joinToString("\n") { "Сообщение ${it.id}, фрагмент ${it.part}; оригинал read_file(file_id=message:${it.id}):\n${it.text}" }
    }
    companion object {
        fun createEconomyTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS embedding_queries(chat_id INTEGER NOT NULL,space TEXT NOT NULL,query_hash TEXT NOT NULL,vector BLOB NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(chat_id,space,query_hash))")
            EmbeddingLedger.create(db)
        }
        fun create(db: SQLiteDatabase) {
            createEconomyTables(db)
            db.execSQL("CREATE TABLE semantic_chunks(chat_id INTEGER NOT NULL,message_id INTEGER NOT NULL,space TEXT NOT NULL,source_hash TEXT NOT NULL,part INTEGER NOT NULL,body TEXT NOT NULL,vector BLOB NOT NULL,PRIMARY KEY(chat_id,message_id,space,source_hash,part))")
            db.execSQL("CREATE TABLE semantic_sources(chat_id INTEGER NOT NULL,message_id INTEGER NOT NULL,space TEXT NOT NULL,source_hash TEXT NOT NULL,PRIMARY KEY(chat_id,message_id,space))")
            db.execSQL("CREATE INDEX semantic_lookup ON semantic_chunks(chat_id,space,message_id)")
            listOf("DELETE", "UPDATE").forEach { operation -> db.execSQL("CREATE TRIGGER semantic_${operation.lowercase()} AFTER $operation ON messages BEGIN DELETE FROM semantic_chunks WHERE chat_id=old.chat_id AND message_id=old.id; DELETE FROM semantic_sources WHERE chat_id=old.chat_id AND message_id=old.id; END") }
        }
        private fun encode(vector: FloatArray) = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { vector.forEach { putFloat(it) } }.array()
        private fun decode(bytes: ByteArray): FloatArray { val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); return FloatArray(bytes.size / 4) { buffer.float } }
    }
}
