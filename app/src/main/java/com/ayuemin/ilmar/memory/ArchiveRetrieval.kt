package com.ayuemin.ilmar.memory

import android.content.Context
import com.ayuemin.ilmar.chat.ChatStore
import com.ayuemin.ilmar.model.ProviderRegistry
import com.ayuemin.ilmar.diagnostics.Diagnostics
import com.ayuemin.ilmar.settings.RuntimeLimits as Limits
import kotlinx.coroutines.*

/** Network indexing is optional and independent of generation. Retrieval failures leave local FTS available. */
internal class ArchiveRetrieval(private val context: Context, private val registry: ProviderRegistry) {
    private val preferences = EmbeddingPreferences(context)
    private val diagnostics = Diagnostics(context)
    private val client = EmbeddingClient()
    private val jobs = mutableMapOf<Long, Job>()
    private val queryCache = object : LinkedHashMap<String, FloatArray>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 32
    }
    fun schedule(scope: CoroutineScope, chat: Long, progress: (String) -> Unit) {
        if (!preferences.load().enabled || jobs[chat]?.isActive == true) return
        jobs[chat] = scope.launch(Dispatchers.IO) {
            try {
                val config = preferences.load(); if (!config.enabled) return@launch
                val (connection, key) = registry.catalogTarget(config.provider)
                val space = config.space(connection.endpoint)
                ChatStore(context).use { store ->
                    val history = store.load().find { it.id == chat }?.messages.orEmpty().dropLast(maxOf(1, ContextPreferences(context).load().recent))
                    progress("Индексируется архив…")
                    SemanticArchive(store).index(chat, history, space, { texts ->
                        // Recheck opt-in before every paid batch.
                        check(preferences.load() == config) { "Настройки эмбеддингов изменились; индексация приостановлена" }
                        val started = android.os.SystemClock.elapsedRealtime()
                        client.embed(config.endpoint(connection.endpoint), key, config.model, texts).also {
                            diagnostics.record("EMBEDDING_INDEX", chat, org.json.JSONObject().put("model", config.model).put("chunks", texts.size).put("characters", texts.sumOf { it.length }).put("latency_ms", android.os.SystemClock.elapsedRealtime() - started))
                        }
                    }, { progress("Семантический архив: $it фрагментов") })
                    progress("Семантический архив: ${SemanticArchive(store).count(chat, space)} фрагментов")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { diagnostics.failure("EMBEDDING_ERROR", chat, e); progress("Индексирование недоступно: ${e.message}. Локальный поиск работает.") }
        }
    }
    fun cancel(chat: Long) { jobs.remove(chat)?.cancel() }
    suspend fun search(chat: Long, query: String, through: Long): String {
        if (!preferences.load().enabled || query.isBlank()) return ""
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
                val cacheKey = "$chat|$space|${ContextEstimate.hash(text)}"
                val vector = synchronized(queryCache) { queryCache[cacheKey] } ?: client.embed(config.endpoint(connection.endpoint), key, config.model, listOf(text)).single().also {
                    synchronized(queryCache) { queryCache[cacheKey] = it }
                    diagnostics.record("EMBEDDING_QUERY", chat, org.json.JSONObject().put("model", config.model).put("characters", text.length))
                }
                archive.search(chat, space, vector, through)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { diagnostics.failure("RETRIEVAL_ERROR", chat, e); "" }
        }
    }
}
