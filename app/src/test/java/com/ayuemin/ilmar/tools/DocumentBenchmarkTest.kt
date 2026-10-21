package com.ayuemin.ilmar.tools

import com.ayuemin.ilmar.documents.*
import com.ayuemin.ilmar.memory.ContextEstimate
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DocumentBenchmarkTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource("documents/$name")!!.readText()
    @Test fun corpusReportsMeasuredRetrievalCoveragePayloadAndHostLatencyWithoutModels() = runTest {
        val cases = JSONArray(resource("cases.json")); val report = JSONArray(); var calls = 0
        val variants = linkedMapOf("default" to RetrievalOptions(), "feedback" to RetrievalOptions(feedback = true),
            "no_structure" to RetrievalOptions(structure = false), "no_heat" to RetrievalOptions(heat = false),
            "no_morphology" to RetrievalOptions(morphology = false), "no_context" to RetrievalOptions(context = false))
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i); val file = case.getString("file"); val body = resource(file)
            val query = case.getString("query"); val url = "https://example.com/$file"
            val mime = if (file.endsWith(".html")) "text/html; charset=utf-8" else "text/plain; charset=utf-8"
            val web = WebTools(OkHttpClient.Builder().addInterceptor { chain ->
                calls++; Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody(mime.toMediaType())).build()
            }.build())
            val started = System.nanoTime(); val fetched = web.fetchDocument(url)
            val legacy = fetched.legacyResult(64000); val legacyMs = (System.nanoTime() - started) / 1e6
            val extractionStarted = System.nanoTime(); val doc = DocumentExtraction.extract(fetched)
            val extractionMs = (System.nanoTime() - extractionStarted) / 1e6
            val indexStarted = System.nanoTime(); val heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            DocumentIndex(doc).use { index ->
                val indexMs = (System.nanoTime() - indexStarted) / 1e6
                val heapAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                val gold = case.getJSONArray("gold").let { a -> (0 until a.length()).map { a.getString(it) } }
                val measurements = JSONArray()
                for ((variant, options) in variants) for (k in listOf(3, 5, 10)) {
                    val searchStarted = System.nanoTime(); val result = index.search(query, k, options)
                    val texts = result.hits.map { doc.text.substring(doc.blocks[it.first].start, doc.blocks[it.until - 1].end) }
                    val found = gold.count { answer -> texts.any { it.contains(answer) } }
                    measurements.put(JSONObject().put("variant", variant).put("k", k).put("gold_passages", gold.size).put("found", found)
                        .put("recall", found.toDouble() / gold.size).put("returned_windows", texts.size).put("search_ms", (System.nanoTime() - searchStarted) / 1e6))
                }
                var compare: JSONObject? = null
                val beforeFetch = calls
                DocumentSession(1, "benchmark-$i", DocumentExperiment(DocumentMode.COMPARE), query, { true }, { compare = it }).use { session ->
                    val localStarted = System.nanoTime()
                    val output = session.fetch(url, web)
                    val localMs = (System.nanoTime() - localStarted) / 1e6
                    assertEquals(1, calls - beforeFetch)
                    val root = JSONObject(output); val frags = root.getJSONArray("fragments")
                    val text = (0 until frags.length()).joinToString("\n") { frags.getJSONObject(it).getString("text") }
                    val schemas = JSONArray().apply { DocumentTools.forSession(session).forEach { put(JSONObject().put("type", "function").put("function", JSONObject().put("name", it.schema.name).put("description", it.schema.description).put("parameters", it.schema.parameters))) } }.toString()
                    report.put(JSONObject().put("id", case.getString("id")).put("query", query).put("file", file)
                        .put("source_sha256", ContextEstimate.hash(body)).put("source_bytes", body.toByteArray().size).put("backend", index.backend)
                        .put("legacy_result", legacy).put("local_result", output).put("document_tool_schemas", schemas)
                        .put("legacy_gold_found", gold.count { JSONObject(legacy).getString("text").contains(it) }).put("local_gold_found", gold.count { text.contains(it) })
                        .put("gold_count", gold.size).put("ranking", measurements).put("index_ms", indexMs)
                        .put("legacy_fixture_fetch_ms", legacyMs).put("local_fixture_fetch_ms", localMs).put("structure_ms", extractionMs)
                        .put("fixture_pipeline_ms", (System.nanoTime() - started) / 1e6).put("index_payload_bytes", index.retainedBytes)
                        .put("host_heap_before_bytes", heapBefore).put("host_heap_after_bytes", heapAfter)
                        .put("compare", compare).put("fetch_calls_per_projection_comparison", 1).put("browser_calls", 0).put("model_calls", 0).put("document_store_disk_bytes", 0))
                }
            }
        }
        val result = JSONObject().put("method", "12 synthetic RU/EN questions, 10 documents. Recall is exact gold-passage containment, not final-answer accuracy. Host JVM timings/heap are not phone measurements; no socket, model, provider usage or billed-cost measurement. Each Compare uses one interceptor Fetch. Ranking index construction is separate from that comparison; total fixture Fetch calls include both.")
            .put("fixture_fetch_calls_total", calls).put("cases", report)
        java.io.File("build/reports/retrieval-benchmark.json").apply { parentFile?.mkdirs(); writeText(result.toString(2)) }
        assertEquals(12, report.length()); assertEquals(24, calls)
    }
}
