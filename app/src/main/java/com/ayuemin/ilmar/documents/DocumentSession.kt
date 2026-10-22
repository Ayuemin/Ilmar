package com.ayuemin.ilmar.documents

import com.ayuemin.ilmar.agent.AgentFailure
import com.ayuemin.ilmar.memory.ContextEstimate
import com.ayuemin.ilmar.settings.RuntimeLimits as Limits
import com.ayuemin.ilmar.tools.FetchedDocument
import com.ayuemin.ilmar.tools.WebTools
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.BitSet
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A capability carried only by the active run's coroutine. Never registered as a global document catalogue. */
internal class DocumentSession(val chat: Long, run: String, val config: DocumentExperiment,
    private val query: String, private val allowed: () -> Boolean,
    private val comparison: (JSONObject) -> Unit = {}, private val ttlMillis: Long = 15 * 60 * 1000L
) : AbstractCoroutineContextElement(Key), AutoCloseable {
    companion object Key : CoroutineContext.Key<DocumentSession>
    private data class State(val entry: TemporaryDocuments.Entry, val index: DocumentIndex, val sent: BitSet = BitSet())
    private val store = TemporaryDocuments(chat, run, ttlMillis = ttlMillis)
    private val states = linkedMapOf<String, State>()
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val timers = mutableMapOf<String, Job>()
    private var closed = false
    private val byteBudget get() = minOf(24000, (Limits.bytes("result_kib") - 1024).coerceAtLeast(256))
    private val charBudget get() = minOf(8000, Limits.get("excerpt_chars"))
    @Synchronized fun authorize() {
        check(!closed) { "DOCUMENT_SESSION_CLOSED" }
        if (!allowed()) { close(); throw AgentFailure("PERMISSION_REVOKED", "Разрешение Fetch отозвано; временные документы удалены") }
        val live = store.all().map { it.id }.toSet()
        states.keys.filter { it !in live }.toList().forEach(::remove)
    }
    @Synchronized private fun remove(id: String) {
        states.remove(id)?.index?.close(); timers.remove(id)?.cancel()
        if (!closed) store.remove(id)
    }
    private fun urlKey(url: String) = url.toHttpUrl().newBuilder().fragment(null).build().toString()
    @Synchronized private fun existing(url: String): String? { authorize(); return states.values.firstOrNull { urlKey(it.entry.fetched.url) == urlKey(url) }?.entry?.id }
    @Synchronized private fun insert(fetched: FetchedDocument): String {
        authorize()
        val entry = store.put(fetched)
        val index = try { DocumentIndex(entry.structure, maxBytes = 16L * 1024 * 1024 - states.values.sumOf { it.index.retainedBytes }) }
        catch (e: Exception) { store.remove(entry.id); throw e }
        states[entry.id] = State(entry, index)
        val remaining = (entry.created + ttlMillis - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)
        timers[entry.id] = cleanup.launch { delay(remaining); synchronized(this@DocumentSession) { if (!closed) remove(entry.id) } }
        return entry.id
    }
    suspend fun fetch(url: String, web: WebTools): String {
        authorize()
        val cached = existing(url)
        if (cached != null) return initial(cached, true)
        val fetched = web.fetchDocument(url)
        currentCoroutineContext().ensureActive()
        val id = insert(fetched) // Rechecks authorization after network IO, before retaining the source.
        return initial(id, false)
    }
    private fun root() = JSONObject().put("untrusted", true).put("fragments", JSONArray())
        .put("selection_only", true).put("score_is_probability", false)
    private fun state(id: String): State { authorize(); store.get(id); return states[id] ?: error("DOCUMENT_UNAVAILABLE") }
    private fun fragment(state: State, first: Int, until: Int, score: Double?): JSONObject {
        val entry = state.entry; val doc = entry.structure
        val start = doc.blocks[first].start; val end = doc.blocks[until - 1].end
        val section = doc.sections.first { it.id == if (config.options.structure) doc.blocks[first].section else "s0" }
        return JSONObject().put("document_id", entry.id).put("fragment_id", "f$first-$until").put("section_id", section.id)
            .put("url", entry.fetched.url).put("title", doc.title.take(240)).put("title_complete", doc.title.length <= 240)
            .put("version", doc.version).put("heading_path", JSONArray(section.path.map { it.take(160) }))
            .put("first_block", first).put("until_block", until).put("start_char", start).put("end_char", end)
            .put("offset_space", "extracted_utf16").put("kinds", JSONArray(doc.blocks.subList(first, until).map { it.kind }.distinct()))
            .put("text", doc.text.substring(start, end)).apply { score?.let { put("heuristic_score", it) } }
    }
    /** Append complete contiguous, unsent units; a continuation addresses the remaining original range. */
    private fun append(root: JSONObject, state: State, first: Int, until: Int, score: Double? = null, repeat: Boolean = false, maxFragments: Int = 10, allowance: Int = charBudget): Int {
        val output = root.getJSONArray("fragments"); val doc = state.entry.structure
        var left = minOf(allowance, charBudget - (0 until output.length()).sumOf { output.getJSONObject(it).getString("text").length })
        fun delivered(block: Int): Boolean = !repeat && (state.sent[block] || (0 until output.length()).any { n ->
            val f = output.getJSONObject(n)
            f.getString("document_id") == state.entry.id && block >= f.getInt("first_block") && block < f.getInt("until_block")
        })
        var i = first
        while (i < until && output.length() < maxFragments) {
            if (delivered(i)) { i++; continue }
            val start = i; var candidate: JSONObject? = null
            while (i < until && !delivered(i) && doc.blocks[i].section == doc.blocks[start].section) {
                if (doc.blocks[i].end - doc.blocks[start].start > left) break
                val next = fragment(state, start, i + 1, score)
                if (root.toString().toByteArray().size + next.toString().toByteArray().size + 516 > byteBudget) break
                candidate = next; i++
            }
            if (candidate == null) {
                root.put("output_limited", true).put("next_fragment_id", "f$i-$until").put("next_document_id", state.entry.id)
                if (i == start && output.length() == 0 && allowance >= charBudget) root.put("atomic_block_too_large", true)
                return i
            }
            output.put(candidate); left -= candidate.getString("text").length
        }
        if (i < until) root.put("next_fragment_id", "f$i-$until").put("next_document_id", state.entry.id).put("output_limited", true)
        return i
    }
    private fun checked(value: JSONObject): String {
        val result = value.toString()
        require(result.toByteArray().size <= byteBudget) { "DOCUMENT_OUTPUT_LIMIT: метаданные не помещаются в лимит tools" }
        value.optJSONArray("fragments")?.let { fragments -> (0 until fragments.length()).forEach { i ->
            val fragment = fragments.getJSONObject(i)
            states[fragment.getString("document_id")]?.sent?.set(fragment.getInt("first_block"), fragment.getInt("until_block"))
        } }
        return result
    }
    @Synchronized private fun initial(id: String, reused: Boolean): String {
        val state = state(id); val entry = state.entry; val doc = entry.structure
        val result = root().put("document_id", id).put("url", entry.fetched.url).put("title", doc.title.take(240))
            .put("source_complete", true).put("full_text_chars", doc.text.length).put("reused", reused)
            .put("backend", state.index.backend).put("expires_in_seconds", ((entry.created + ttlMillis - android.os.SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0))
            .put("note", "Complete source is local to this run. Use outline/search/read; no match is not proof of absence.")
        if (doc.text.length <= 3000) {
            if (doc.blocks.isNotEmpty() && state.sent.cardinality() < doc.blocks.size) {
                // One provenance record is cheaper than repeating it for every section of a short page.
                if (state.sent.isEmpty) result.getJSONArray("fragments").put(fragment(state, 0, doc.blocks.size, null)
                    .put("section_id", "s0").put("heading_path", JSONArray()))
                else append(result, state, 0, doc.blocks.size)
            }
        }
        else initialSearch(state, result)
        val output = checked(result)
        if (config.mode == DocumentMode.COMPARE && !reused) {
            val legacy = entry.fetched.legacyResult(Limits.get("excerpt_chars"))
            comparison(JSONObject().put("comparison", true).put("fetch_calls", 1).put("browser_calls", 0).put("model_calls", 0)
                .put("legacy_chars", legacy.length).put("selected_chars", output.length).put("legacy_estimated_tokens", ContextEstimate.text(legacy))
                .put("selected_estimated_tokens", ContextEstimate.text(output)).put("fts5", state.index.backend == "fts5-bm25")
                .put("retained_payload_bytes", entry.retainedBytes + state.index.retainedBytes))
        }
        return output
    }
    /** One fair pass per question, then bounded local expansion if lexical coverage is weak. */
    private fun initialSearch(state: State, result: JSONObject) {
        val queries = DocumentQueries.split(query, state.entry.structure.title)
        val candidates = queries.map { q ->
            val hits = state.index.search(q, 10, config.options).hits
            val selected = hits.take(2).joinToString(" ") { hit ->
                val d = state.entry.structure
                d.text.substring(d.blocks[hit.first].start, d.blocks[hit.until - 1].end)
            }
            val anchor = DocumentQueries.anchors(q)
            val extra = if (DocumentQueries.coverage(q, selected) < 0.5 && anchor.isNotBlank() && anchor != q)
                state.index.search(anchor, 10, config.options).hits else emptyList()
            (hits.take(2) + extra + hits.drop(2)).distinctBy { it.first to it.until }
        }
        // At most eight questions/160 windows, independent of agent/model rounds.
        for (rank in 0 until 20) for (hits in candidates) {
            val hit = hits.getOrNull(rank) ?: continue
            if (result.getJSONArray("fragments").length() >= config.fragments) break
            append(result, state, hit.first, hit.until, hit.score, maxFragments = config.fragments,
                allowance = if (rank == 0) charBudget / queries.size else charBudget)
        }
        val fragments = result.getJSONArray("fragments")
        val text = (0 until fragments.length()).joinToString(" ") { fragments.getJSONObject(it).getString("text") }
        result.put("query_coverage", JSONArray(queries.mapIndexed { i, q -> JSONObject().put("question", i + 1)
            .put("term_fraction", DocumentQueries.coverage(q, text)).put("candidate_windows", candidates[i].size) }))
            .put("coverage_is_heuristic", true)
    }
    private fun searchObject(query: String, limit: Int, only: String? = null, result: JSONObject = root()): JSONObject {
        authorize(); require(query.isNotBlank() && query.length <= 1000 && limit in 1..10)
        val root = result; val results = states.values.filter { only == null || it.entry.id == only }.flatMap { state ->
            state.index.search(query, 10, config.options).hits.map { state to it }
        }.sortedByDescending { it.second.score }
        for ((state, hit) in results) {
            if (root.getJSONArray("fragments").length() >= limit) break
            append(root, state, hit.first, hit.until, hit.score, maxFragments = limit)
        }
        val empty = root.getJSONArray("fragments").length() == 0
        if (empty) root.put("document_ids", JSONArray(states.keys.toList()))
        root.put("delivered_windows", JSONArray(results.filter { (s, hit) -> (hit.first until hit.until).all { s.sent[it] } }.take(10).map { (s, hit) ->
            JSONObject().put("document_id", s.entry.id).put("fragment_id", "f${hit.first}-${hit.until}")
        })).put("reread_hint", "Already delivered windows remain in prior results. If quotes left context, use document_read with repeat=true.") // Recover navigation after tool-history projection drops old quotes.
        return root.put("matched_windows", results.size).put("already_delivered_or_no_match", empty)
    }
    @Synchronized fun search(query: String, limit: Int) = checked(searchObject(query, limit))
    @Synchronized fun outline(id: String, offset: Int): String {
        val state = state(id); val doc = state.entry.structure
        require(offset in 0..doc.sections.size)
        val sections = JSONArray(); val result = JSONObject().put("document_id", id).put("url", state.entry.fetched.url).put("untrusted", true).put("sections", sections)
        val source = if (config.options.structure) doc.sections else doc.sections.take(1)
        var next = offset
        for (section in source.drop(offset).take(40)) {
            val row = JSONObject().put("section_id", section.id).put("title", section.title.take(160)).put("title_complete", section.title.length <= 160)
                .put("level", section.level).put("path", JSONArray(section.path.map { it.take(160) }))
                .put("first_block", section.first).put("until_block", section.until)
                .put("characters", if (section.until > section.first) doc.blocks[section.until - 1].end - doc.blocks[section.first].start else 0)
            if (result.toString().toByteArray().size + row.toString().toByteArray().size + 100 > byteBudget) break
            sections.put(row); next++
        }
        return checked(result.put("next_offset", if (next < source.size) next else JSONObject.NULL))
    }
    @Synchronized fun read(id: String, section: String, before: Int, after: Int, repeat: Boolean): String {
        val state = state(id); val doc = state.entry.structure
        require(before in 0..10 && after in 0..10 && (config.options.context || before == 0 && after == 0)) { "Расширение контекста выключено или превышает 10 блоков" }
        val range = doc.sections.find { it.id == section && (config.options.structure || it.id == "s0") }?.let { it.first to it.until } ?: Regex("f([0-9]+)-([0-9]+)").matchEntire(section)?.let {
            val first = it.groupValues[1].toIntOrNull(); val until = it.groupValues[2].toIntOrNull()
            require(first != null && until != null && first >= 0 && first < until && until <= doc.blocks.size) { "Некорректные границы фрагмента" }; first to until
        } ?: error("DOCUMENT_SECTION_UNAVAILABLE")
        val root = root().put("document_id", id).put("url", state.entry.fetched.url)
        append(root, state, (range.first - before).coerceAtLeast(0), (range.second + after).coerceAtMost(doc.blocks.size), repeat = repeat)
        return checked(root.put("already_delivered", root.getJSONArray("fragments").length() == 0)
            .put("requested_section_id", section).put("reread_hint", "Use prior quotes, or repeat=true to restore original text if it left context."))
    }
    @Synchronized override fun close() {
        if (closed) return
        cleanup.cancel(); timers.clear(); states.values.forEach { runCatching { it.index.close() } }; states.clear(); store.close(); closed = true
    }
}
