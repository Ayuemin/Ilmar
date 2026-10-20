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
    suspend fun index(chat: Long, history: List<ChatMessage>, space: String, embed: suspend (List<String>) -> List<FloatArray>, progress: (Int) -> Unit = {}) {
        for (message in history) {
            currentCoroutineContext().ensureActive()
            val source = message.text + message.attachments.joinToString("") { "\nФайл ${it.name}; file_id=${it.id}\n${it.text.orEmpty()}" }
            if (source.isBlank()) continue
            val hash = ContextEstimate.hash(source)
            val done = db.rawQuery("SELECT 1 FROM semantic_sources WHERE chat_id=? AND message_id=? AND space=? AND source_hash=?", arrayOf(chat.toString(), message.id.toString(), space, hash)).use { it.moveToFirst() }
            if (done) continue
            val size = Limits.get("embedding_chars").coerceAtLeast(256)
            val chunks = generateSequence(0) { start -> if (start + size >= source.length) null else start + size - 200 }.map { start -> source.substring(start, minOf(source.length, start + size)) }.toList()
            // Persist batches, including paid chunks from an interrupted source; retries buy only missing chunks.
            chunks.withIndex().toList().chunked(Limits.get("embedding_batch")).forEach { batch ->
                currentCoroutineContext().ensureActive()
                val missing = batch.filter { entry -> !db.rawQuery("SELECT 1 FROM semantic_chunks WHERE chat_id=? AND message_id=? AND space=? AND source_hash=? AND part=?", arrayOf(chat.toString(), message.id.toString(), space, hash, entry.index.toString())).use { it.moveToFirst() } }
                if (missing.isNotEmpty()) {
                    val vectors = embed(missing.map { it.value })
                    require(vectors.size == missing.size)
                    db.beginTransaction()
                    try {
                        val exists = db.rawQuery("SELECT 1 FROM messages WHERE chat_id=? AND id=?", arrayOf(chat.toString(), message.id.toString())).use { it.moveToFirst() }
                        if (exists) missing.zip(vectors).forEach { (chunk, vector) ->
                            db.execSQL("INSERT OR REPLACE INTO semantic_chunks(chat_id,message_id,space,source_hash,part,body,vector) VALUES(?,?,?,?,?,?,?)", arrayOf(chat, message.id, space, hash, chunk.index, chunk.value, encode(vector)))
                        }
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                }
            }
            db.execSQL("INSERT OR REPLACE INTO semantic_sources SELECT ?,?,?,? WHERE EXISTS(SELECT 1 FROM messages WHERE chat_id=? AND id=?)", arrayOf<Any>(chat, message.id, space, hash, chat, message.id))
            progress(count(chat, space))
        }
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
        fun create(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE semantic_chunks(chat_id INTEGER NOT NULL,message_id INTEGER NOT NULL,space TEXT NOT NULL,source_hash TEXT NOT NULL,part INTEGER NOT NULL,body TEXT NOT NULL,vector BLOB NOT NULL,PRIMARY KEY(chat_id,message_id,space,source_hash,part))")
            db.execSQL("CREATE TABLE semantic_sources(chat_id INTEGER NOT NULL,message_id INTEGER NOT NULL,space TEXT NOT NULL,source_hash TEXT NOT NULL,PRIMARY KEY(chat_id,message_id,space))")
            db.execSQL("CREATE INDEX semantic_lookup ON semantic_chunks(chat_id,space,message_id)")
            listOf("DELETE", "UPDATE").forEach { operation -> db.execSQL("CREATE TRIGGER semantic_${operation.lowercase()} AFTER $operation ON messages BEGIN DELETE FROM semantic_chunks WHERE chat_id=old.chat_id AND message_id=old.id; DELETE FROM semantic_sources WHERE chat_id=old.chat_id AND message_id=old.id; END") }
        }
        private fun encode(vector: FloatArray) = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { vector.forEach { putFloat(it) } }.array()
        private fun decode(bytes: ByteArray): FloatArray { val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); return FloatArray(bytes.size / 4) { buffer.float } }
    }
}
