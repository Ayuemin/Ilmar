package com.ayuemin.ilmar.memory

import android.content.Context
import com.ayuemin.ilmar.chat.ChatStore
import com.ayuemin.ilmar.model.ProviderRegistry
import com.ayuemin.ilmar.diagnostics.Diagnostics
import com.ayuemin.ilmar.settings.RuntimeLimits as Limits
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

/** Network indexing is optional and independent of generation. Retrieval failures leave local FTS available. */
internal class ArchiveRetrieval(private val context: Context, private val registry: ProviderRegistry, private val client: EmbeddingClient = EmbeddingClient()) {
    private val preferences = EmbeddingPreferences(context)
    private val diagnostics = Diagnostics(context)
    private val jobs = mutableMapOf<Long, Job>()
    private suspend fun embed(store: ChatStore, chat: Long, config: EmbeddingConfig, endpoint: String, key: String, texts: List<String>, purpose: String): List<FloatArray> {
        check(preferences.load() == config) { "Настройки эмбеддингов изменились; индексация приостановлена" }
        val ledger = EmbeddingLedger(store)
        val id = ledger.start(chat, purpose, config.model, texts.size, texts.sumOf { it.length })
        val details = org.json.JSONObject().put("request_id", id).put("purpose", purpose).put("model", config.model)
            .put("inputs", texts.size).put("characters", texts.sumOf { it.length })
        diagnostics.record("EMBEDDING_REQUEST", chat, details)
        try {
            val response = client.embedResult(endpoint, key, config.model, texts)
            ledger.finish(id, "completed", response.usage)
            diagnostics.record("EMBEDDING_RESPONSE", chat, details.put("usage", EmbeddingLedger.json(response.usage)))
            return response.vectors
        } catch (e: CancellationException) { ledger.finish(id, "cancelled"); throw e }
        catch (e: Exception) { ledger.finish(id, "failed"); diagnostics.failure("EMBEDDING_REQUEST_ERROR", chat, e, details); throw e }
    }
    fun schedule(scope: CoroutineScope, chat: Long, progress: (String) -> Unit, force: Boolean = false) {
        if (!preferences.load().enabled || jobs[chat]?.isCompleted == false) return
        jobs[chat] = scope.launch(Dispatchers.IO) {
            try {
                val config = preferences.load(); if (!config.enabled) return@launch
                val (connection, key) = registry.catalogTarget(config.provider)
                val space = config.space(connection.endpoint)
                ChatStore(context).use { store ->
                    val history = store.load().find { it.id == chat }?.messages.orEmpty().dropLast(maxOf(1, ContextPreferences(context).load().recent))
                    progress("Индексируется архив…")
                    var deferred = 0
                    SemanticArchive(store).indexBatched(chat, history, space, { texts ->
                        embed(store, chat, config, config.endpoint(connection.endpoint), key, texts, "index")
                    }, flushPartial = force, deferred = { deferred = it }, progress = { progress("Семантический архив: $it фрагментов") })
                    progress("Семантический архив: ${SemanticArchive(store).count(chat, space)} фрагментов" + if (deferred > 0) " · $deferred ожидают общего пакета; локальный поиск доступен сразу" else "")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { diagnostics.failure("EMBEDDING_ERROR", chat, e); progress("Индексирование недоступно: ${e.message}. Локальный поиск работает.") }
        }
    }
    fun cancel(chat: Long) { jobs[chat]?.cancel() }
    suspend fun search(chat: Long, query: String, through: Long): String {
        if (!preferences.load().enabled || query.isBlank() || ContinuationPolicy.isControl(query)) return ""
        return withContext(Dispatchers.IO) {
        val config = preferences.load()
        if (!config.enabled || query.isBlank()) return@withContext ""
        try {
            val (connection, key) = registry.catalogTarget(config.provider)
            val space = config.space(connection.endpoint)
            ChatStore(context).use { store ->
                val archive = SemanticArchive(store)
                if (archive.count(chat, space) == 0) return@use ""
                val text = query.take(Limits.get("embedding_chars"))
                val vector = queryLock.withLock {
                    archive.queryVector(chat, space, text)?.also {
                        diagnostics.record("EMBEDDING_CACHE_HIT", chat, org.json.JSONObject().put("model", config.model).put("characters", text.length))
                    } ?: embed(store, chat, config, config.endpoint(connection.endpoint), key, listOf(text), "query").single().also {
                        archive.saveQueryVector(chat, space, text, it)
                    }
                }
                archive.search(chat, space, vector, through)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { diagnostics.failure("RETRIEVAL_ERROR", chat, e); "" }
        }
    }
    companion object { private val queryLock = kotlinx.coroutines.sync.Mutex() }
}
