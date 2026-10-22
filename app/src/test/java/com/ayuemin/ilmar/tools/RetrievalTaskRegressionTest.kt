package com.ayuemin.ilmar.tools

import com.ayuemin.ilmar.agent.*
import com.ayuemin.ilmar.documents.*
import com.ayuemin.ilmar.diagnostics.TaskMetrics
import com.ayuemin.ilmar.model.*
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RetrievalTaskRegressionTest {
    private val query = """
        https://www.sqlite.org/fts5.html
        1. Как работает bm25()?
        2. Что означает отрицательный знак результата BM25?
        3. Какие параметры принимает highlight()?
        4. Что такое external content FTS5 table?
    """.trimIndent()
    private fun web(html: String) = WebTools(OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(html.toResponseBody("text/html".toMediaType())).build()
    }.build())
    private fun texts(data: JSONObject) = data.getJSONArray("fragments").let { a ->
        (0 until a.length()).joinToString("\n") { a.getJSONObject(it).getString("text") }
    }
    @Test fun numberedQuestionsAreSplitWithoutAnExtraModelAndQuotesStayExact() {
        assertEquals(4, DocumentQueries.split(query, "title").size)
        assertEquals(listOf("First?", "Second?"), DocumentQueries.split("1. First?\n2. Second?", "title"))
        val original = "Not valid unless approved; -1.0 is not a probability."
        val f = JSONObject().put("document_id", "d-123").put("fragment_id", "f1-2").put("url", "https://example.com")
            .put("text", original).put("version", "version")
        val root = JSONObject().put("untrusted", true).put("fragments", org.json.JSONArray().put(f))
        val compact = DocumentHistoryProjection.compact(root.toString())
        assertEquals(original, texts(JSONObject(compact)))
        assertEquals(compact, DocumentHistoryProjection.compact(compact))
    }
    @Test fun fourDistantSectionsAreDeliveredFairlyAndNotRepeated() = runTest {
        val html = "<h1>Guide</h1>" + "<p>Background paragraph.</p>".repeat(300) +
            "<h2>bm25</h2><p>bm25() computes relevance using phrase frequency and document length.</p>" +
            "<h2>BM25 sign</h2><p>BM25 returns a negative value. It is not positive; better matches sort first.</p>" +
            "<h2>highlight</h2><p>highlight() accepts table, column index, opening marker and closing marker.</p>" +
            "<h2>external content FTS5 table</h2><p>external content FTS5 table stores an index. Content is not copied; it is read from a separate table.</p>"
        DocumentSession(1, "questions", DocumentExperiment(DocumentMode.LOCAL), query, { true }).use { s ->
            val first = JSONObject(s.fetch("https://example.com/manual", web(html)))
            val text = texts(first)
            listOf("phrase frequency", "not positive", "column index", "Content is not copied").forEach { assertTrue(it, text.contains(it)) }
            assertEquals(4, first.getJSONArray("query_coverage").length())
            val again = JSONObject(s.search("external content FTS5 table", 5))
            assertFalse(texts(again).contains("Content is not copied"))
            assertTrue(again.getJSONArray("delivered_windows").length() > 0)
            val restored = JSONObject(s.read(first.getString("document_id"), "s0", 0, 0, true))
            assertTrue(restored.getJSONArray("fragments").length() > 0)
        }
    }
    @Test fun scriptedFetchSearchReadOtherSectionAnswerAndCleanup() = runTest {
        val html = "<h1>Guide</h1>" + "<p>Background.</p>".repeat(400) +
            "<h2>Quota</h2><p>Quota is 25. Not available unless approved.</p><h2>Cancellation</h2><p>Cancellation takes 30 days.</p>"
        val s = DocumentSession(1, "script", DocumentExperiment(DocumentMode.LOCAL), "absent", { true })
        var id = ""; var round = 0
        val metrics = TaskMetrics()
        val fetch = BoundTool(ToolSchema("fetch_url", "test", JSONObject())) {
            s.fetch("https://example.com/doc", web(html)).also { id = JSONObject(it).getString("document_id") }
        }
        s.use {
            val result = AgentLoopRunner(onCheckpoint = metrics::checkpoint).run(listOf(fetch) + DocumentTools.forSession(s)) { _, _ ->
                val call = when (round++) {
                    0 -> ModelToolCall("0", "fetch_url", "{}")
                    1 -> ModelToolCall("1", "document_search", "{\"query\":\"Quota\"}")
                    2 -> ModelToolCall("2", "document_read", JSONObject().put("document_id", id).put("section_id", "s2").toString())
                    3 -> ModelToolCall("3", "document_read", JSONObject().put("document_id", id).put("section_id", "s1").put("repeat", true).toString())
                    else -> null
                }
                if (call == null) GenerationResult("Quota 25 only after approval; cancellation 30 days.")
                else GenerationResult("", toolCalls = listOf(call))
            }
            assertTrue(result.text.contains("approval")); assertEquals(5, round)
            assertEquals(0, metrics.json().getInt("tool_round_errors"))
            assertEquals(1, metrics.json().getInt("fetch_calls"))
            assertEquals(2, metrics.json().getInt("local_read_calls"))
        }
        assertTrue(runCatching { s.search("Quota", 5) }.isFailure)
    }
    @Test fun missingIdProducesToolResultAndCanBeCorrectedWithoutBlockingAnotherSection() = runTest {
        DocumentSession(1, "missing", DocumentExperiment(DocumentMode.LOCAL), "quota", { true }).use { s ->
            val id = JSONObject(s.fetch("https://example.com/doc", web("<h1>Quota</h1><p>Not permitted without approval.</p>"))).getString("document_id")
            var round = 0
            AgentLoopRunner().run(DocumentTools.forSession(s)) { history, _ ->
                when (round++) {
                    0 -> GenerationResult("", toolCalls = listOf(ModelToolCall("0", "document_read", "{\"document_id\":\"d-0000000000000000\",\"section_id\":\"s0\"}")))
                    1 -> {
                        assertTrue(history.last().getString("content").contains("DOCUMENT_UNAVAILABLE"))
                        GenerationResult("", toolCalls = listOf(ModelToolCall("1", "document_read", JSONObject().put("document_id", id).put("section_id", "s0").put("repeat", true).toString())))
                    }
                    else -> GenerationResult("done")
                }
            }
            assertEquals(3, round)
        }
    }
    @Test fun pinnedSqlitePageLegacyAndLocalUseTheSameScriptAndSource() = runTest {
        val html = javaClass.classLoader!!.getResource("documents/sqlite-fts5.html")!!.readText()
        val report = org.json.JSONArray()
        val gold = listOf("hard-coded at 1.2 and 0.75", "multiplies the result", "exactly three arguments following", "within the same database")
        for (mode in listOf(DocumentMode.LEGACY, DocumentMode.LOCAL)) {
            val s = DocumentSession(1, "benchmark-$mode", DocumentExperiment(mode), query, { true })
            var fetches = 0; var rounds = 0; var id = ""; var accumulated = ""; var promptEstimate = 0L
            var initialGold = 0
            val fetch = BoundTool(ToolSchema("fetch_url", "test", JSONObject())) {
                fetches++
                (if (mode == DocumentMode.LEGACY) web(html).fetch("https://www.sqlite.org/fts5.html")
                else s.fetch("https://www.sqlite.org/fts5.html", web(html))).also { raw ->
                    val result = JSONObject(raw)
                    id = result.optString("document_id")
                    accumulated += if (mode == DocumentMode.LOCAL) texts(result) else raw
                    initialGold = gold.count { accumulated.contains(it) }
                }
            }
            s.use {
                val tools = listOf(fetch) + if (mode == DocumentMode.LOCAL) DocumentTools.forSession(s) else emptyList()
                val result = AgentLoopRunner(onCheckpoint = { _, messages ->
                    // Collect newly produced source text for deterministic evidence checks, not a generated quality score.
                    messages.filter { it.optString("role") == "tool" }.forEach { item ->
                        val data = runCatching { JSONObject(item.optString("content")) }.getOrNull()
                        if (data?.has("fragments") == true) accumulated += texts(data)
                    }
                }).run(tools) { history, schemas ->
                    promptEstimate += com.ayuemin.ilmar.memory.ContextEstimate.text(history.joinToString() + schemas.joinToString())
                    val calls = when (rounds++) {
                        0 -> listOf(ModelToolCall("fetch", "fetch_url", "{}"))
                        1 -> if (mode == DocumentMode.LOCAL) listOf(ModelToolCall("search", "document_search", "{\"query\":\"highlight parameters\"}")) else emptyList()
                        2 -> if (mode == DocumentMode.LOCAL) {
                            val outline = JSONObject(s.outline(id, 0)).getJSONArray("sections")
                            val sections = (0 until outline.length()).map { outline.getJSONObject(it) }.filter {
                                val title = it.getString("title").lowercase()
                                title.contains("bm25() function") || title.contains("highlight() function") || title.endsWith("external content tables")
                            }
                            assertEquals(3, sections.size)
                            sections.mapIndexed { n, section -> ModelToolCall("read$n", "document_read", JSONObject()
                                .put("document_id", id).put("section_id", section.getString("section_id")).put("repeat", true).toString()) }
                        } else emptyList()
                        else -> emptyList()
                    }
                    if (calls.isEmpty()) GenerationResult("Script finished; evidence completeness recorded separately.") else GenerationResult("", toolCalls = calls)
                }
                assertTrue(result.toolCalls.isEmpty()); assertEquals(1, fetches)
                val found = gold.count { accumulated.contains(it) }
                if (mode == DocumentMode.LOCAL) assertEquals(gold.size, found)
                report.put(JSONObject().put("mode", mode.name).put("scripted_model_calls", rounds).put("fetch_calls", fetches)
                    .put("initial_gold_found", initialGold).put("final_gold_found", found).put("gold_count", gold.size)
                    .put("local_estimated_tool_context_tokens_sum", promptEstimate))
            }
        }
        val evidence = JSONObject().put("method", "Pinned SQLite HTML, same four questions and scripted responder. No real model, network or provider billing. Legacy terminates when unavailable local tools cannot supply more evidence. Local script Fetch/Search/three section Reads/Answer uses four model callbacks; this is not a prediction of phone rounds or answer accuracy.")
            .put("source_sha256", java.security.MessageDigest.getInstance("SHA-256").digest(html.toByteArray()).joinToString("") { "%02x".format(it) })
            .put("cases", report)
        java.io.File("build/reports/retrieval-task-benchmark.json").apply { parentFile.mkdirs(); writeText(evidence.toString(2)) }
    }
    @Test fun providerCostsAndEstimatesRemainDistinctIncludingFailures() {
        val m = TaskMetrics { 123L }
        m.model(TokenUsage(10, 2, cached = 3, costUsd = "0.01"), "completed", "answer", 100, emptyList())
        m.model(TokenUsage(input = 5, costUsd = "0.002"), "failed", "answer", 90, emptyList())
        val data = m.json()
        assertEquals(15, data.getLong("provider_input_tokens")); assertEquals(190, data.getLong("local_estimated_input_tokens"))
        assertEquals("0.012", data.getString("provider_cost_usd")); assertTrue(data.getBoolean("provider_tokens_incomplete"))
        assertEquals(1, data.getInt("model_failures"))
    }
    @Test fun exportedTaskAndFailureMetadataContainsNoArgumentsOrQuotes() {
        val messages = listOf(JSONObject().put("role", "assistant").put("tool_calls", org.json.JSONArray().put(JSONObject().put("id", "c")
            .put("function", JSONObject().put("name", "document_read").put("arguments", "{\"private\":\"secret document\"}")))),
            JSONObject().put("role", "tool").put("tool_call_id", "c").put("content", "{\"error\":\"DOCUMENT_UNAVAILABLE\",\"message\":\"secret document\"}"))
        val safe = com.ayuemin.ilmar.diagnostics.Redaction.metadata(JSONObject().put("stop_reason", "TOOL_RETRY_EXHAUSTED")
            .put("tool_failures", com.ayuemin.ilmar.diagnostics.ToolErrorMetadata.summarize(messages)))
        assertFalse(safe.toString().contains("secret document"))
        assertTrue(safe.toString().contains("DOCUMENT_UNAVAILABLE")); assertTrue(safe.toString().contains("argument_fingerprint"))
        assertEquals("TOOL_RETRY_EXHAUSTED", safe.getString("stop_reason"))
    }
    @Test fun errorClosesSessionAndInvalidatesAllOldDocuments() = runTest {
        val s = DocumentSession(1, "cleanup", DocumentExperiment(DocumentMode.LOCAL), "quota", { true })
        var id = ""
        assertTrue(runCatching { s.use {
            id = JSONObject(s.fetch("https://example.com/doc", web("<p>Quota 25.</p>"))).getString("document_id")
            throw AgentFailure("PERMISSION_REVOKED", "revoked")
        } }.isFailure)
        assertTrue(runCatching { s.outline(id, 0) }.isFailure)
    }
}
