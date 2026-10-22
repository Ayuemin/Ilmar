package com.ayuemin.ilmar.documents

import com.ayuemin.ilmar.tools.FetchedDocument
import java.util.UUID

/** One owner per agent run. No files, SQLite rows, backup or persistent-memory writes.
 * Process death releases everything; close handles success, cancellation and errors.
 * Quota counts UTF-16 payload conservatively (not measured JVM heap overhead).
 */
internal class TemporaryDocuments(
    val chat: Long, val run: String,
    private val maxDocuments: Int = 8, private val maxBytes: Long = 24L * 1024 * 1024,
    private val ttlMillis: Long = 15 * 60 * 1000L,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) : AutoCloseable {
    data class Entry(val id: String, val fetched: FetchedDocument, val created: Long, val structure: StructuredDocument) {
        val retainedBytes get() = fetched.retainedBytes + structure.retainedBytes
    }
    private val entries = linkedMapOf<String, Entry>()
    private var closed = false
    init { require(run.isNotBlank() && maxDocuments > 0 && maxBytes > 0 && ttlMillis > 0) }
    @Synchronized fun put(document: FetchedDocument): Entry {
        checkOpen(); expire()
        require(entries.size < maxDocuments) { "DOCUMENT_COUNT_LIMIT: временное хранилище заполнено" }
        val structure = DocumentExtraction.extract(document)
        require(document.retainedBytes + structure.retainedBytes <= maxBytes - entries.values.sumOf { it.retainedBytes }) {
            "DOCUMENT_MEMORY_LIMIT: полный документ не сохранён; частичный документ не индексируется"
        }
        val entry = Entry("d-" + UUID.randomUUID().toString().replace("-", "").take(16), document, clock(), structure)
        entries[entry.id] = entry
        return entry
    }
    @Synchronized fun get(id: String): Entry {
        checkOpen(); require(id.matches(Regex("d-[a-f0-9]{16}"))) { "Некорректный ID документа" }; expire()
        return entries[id] ?: error("DOCUMENT_UNAVAILABLE: документ отсутствует в текущем запуске или истёк")
    }
    @Synchronized fun all(): List<Entry> { checkOpen(); expire(); return entries.values.toList() }
    @Synchronized fun remove(id: String) { checkOpen(); entries.remove(id) }
    @Synchronized override fun close() { entries.clear(); closed = true }
    private fun checkOpen() = check(!closed) { "DOCUMENT_SESSION_CLOSED" }
    private fun expire() { val now = clock(); entries.entries.removeAll { now - it.value.created >= ttlMillis } }
}
