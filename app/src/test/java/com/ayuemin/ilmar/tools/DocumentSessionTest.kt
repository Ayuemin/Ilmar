package com.ayuemin.ilmar.tools

import com.ayuemin.ilmar.documents.*
import com.ayuemin.ilmar.agent.ToolOperationPolicy
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
class DocumentSessionTest {
    private fun web(html: String, onRequest: () -> Unit = {}): WebTools = WebTools(OkHttpClient.Builder().addInterceptor { chain ->
        onRequest()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(html.toResponseBody("text/html".toMediaType())).build()
    }.build())
    private fun text(root: JSONObject) = root.getJSONArray("fragments").let { a -> (0 until a.length()).joinToString("\n") { a.getJSONObject(it).getString("text") } }
    @Test fun searchOutlineExpansionAndRepeatUseOneFetchAndStayInThisRun() = runTest {
        var calls = 0
        val html = "<h1>Manual</h1>" + "<p>Unrelated background.</p>".repeat(250) + "<h2>Quota</h2><p>Quota is 25 requests. Only after approval. Except trial accounts.</p><p>Cancellation takes 30 days.</p>"
        val config = DocumentExperiment(DocumentMode.LOCAL, RetrievalOptions(context = false), fragments = 3)
        DocumentSession(1, "run-a", config, "quota", { true }).use { session ->
            val initial = JSONObject(session.fetch("https://example.com/doc", web(html) { calls++ }))
            val id = initial.getString("document_id")
            assertTrue(initial.getBoolean("source_complete")); assertTrue(text(initial).contains("25 requests"))
            val outline = JSONObject(session.outline(id, 0)).getJSONArray("sections")
            val section = (0 until outline.length()).map { outline.getJSONObject(it) }.first { it.getString("title") == "Quota" }.getString("section_id")
            val wider = JSONObject(session.read(id, section, 0, 0, false))
            val combined = text(initial) + "\n" + text(wider)
            assertTrue(combined.contains("Only after approval")); assertTrue(combined.contains("Except trial accounts"))
            assertTrue(combined.contains("Cancellation takes 30 days"))
            fun blocks(result: JSONObject): Set<Int> = result.getJSONArray("fragments").let { fragments ->
                (0 until fragments.length()).flatMap { i -> fragments.getJSONObject(i).let { it.getInt("first_block") until it.getInt("until_block") } }.toSet()
            }
            assertTrue(blocks(initial).intersect(blocks(wider)).isEmpty())
            assertFalse(text(wider).contains("Quota is 25"))
            val seen = JSONObject(session.search("quota", 3))
            assertEquals(0, seen.getJSONArray("fragments").length())
            assertEquals(id, seen.getJSONArray("document_ids").getString(0))
            assertTrue(text(JSONObject(session.read(id, section, 0, 0, true))).contains("Quota is 25"))
            DocumentSession(1, "run-b", config, "quota", { true }).use { other -> assertTrue(runCatching { other.outline(id, 0) }.isFailure) }
            assertTrue(runCatching { session.read(id, "f0-9999999999999999", 0, 0, false) }.isFailure)
            assertTrue(runCatching { session.outline("../data", 0) }.isFailure)
            assertTrue(JSONObject(session.fetch("https://example.com/doc", web(html) { calls++ })).getBoolean("reused"))
            assertEquals(1, calls)
        }
    }
    @Test fun compareUsesOneLoadAndOnlyOneProjectionAndRevocationAfterNetworkCannotRetainData() = runTest {
        var calls = 0; var metrics: JSONObject? = null
        val config = DocumentExperiment(DocumentMode.COMPARE)
        val session = DocumentSession(2, "compare", config, "quota", { true }, { metrics = it })
        val output = JSONObject(session.fetch("https://example.com/doc", web("<h1>Quota</h1><p>Quota is 42 requests.</p>") { calls++ }))
        assertTrue(text(output).contains("42 requests")); assertFalse(output.has("legacy_text"))
        assertEquals(1, calls); assertEquals(1, metrics!!.getInt("fetch_calls"))
        session.close(); assertTrue(runCatching { session.outline(output.getString("document_id"), 0) }.isFailure)
        var allowed = true
        DocumentSession(2, "revoke", config, "quota", { allowed }).use { revoked ->
            assertTrue(runCatching { revoked.fetch("https://example.com/doc", web("<p>Private source</p>") { allowed = false }) }.isFailure)
            assertTrue(runCatching { revoked.search("Private", 3) }.isFailure)
        }
    }
    @Test fun boundToolsValidateArgumentsAndPermissionAndContextExpansionSwitch() = runTest {
        var allowed = true
        DocumentSession(3, "tools", DocumentExperiment(DocumentMode.LOCAL, RetrievalOptions(context = false)), "quota", { allowed }).use { session ->
            val output = JSONObject(session.fetch("https://example.com/doc", web("<h1>Quota</h1><p>Quota is 42.</p>")))
            val id = output.getString("document_id")
            val tools = DocumentTools.forSession(session)
            val read = tools.single { it.schema.name == "document_read" }
            assertTrue(runCatching { read.execute(JSONObject().put("document_id", id).put("section_id", "s0").put("before", 1)) }.isFailure)
            assertTrue(runCatching { read.execute(JSONObject().put("document_id", id).put("section_id", "s0").put("evil", "/tmp")) }.isFailure)
            allowed = false
            assertTrue(runCatching { tools.first().execute(JSONObject().put("query", "quota")) }.isFailure)
            assertTrue(runCatching { session.search("quota", 3) }.isFailure)
            tools.forEach { assertTrue(ToolOperationPolicy.safeToRepeat(it.schema.name, "{}")) }
        }
    }
    @Test fun longReadContinuesOnWholeUnitsAndARecreatedSessionHasNoSource() = runTest {
        val html = "<h1>Long</h1>" + (1..100).joinToString("") { "<p>Condition $it must be preserved. " + "text ".repeat(40) + "</p>" }
        DocumentSession(4, "first", DocumentExperiment(DocumentMode.LOCAL), "unknown", { true }).use { session ->
            val initial = JSONObject(session.fetch("https://example.com/doc", web(html)))
            val id = initial.getString("document_id")
            var section = "s0"; val texts = mutableListOf<String>(); var turns = 0
            do {
                val result = JSONObject(session.read(id, section, 0, 0, false))
                assertTrue(result.toString().toByteArray().size <= 24000)
                texts += text(result); section = result.optString("next_fragment_id")
                assertTrue(++turns <= 20)
            } while (section.isNotBlank())
            assertTrue(texts.joinToString("\n").contains("Condition 100 must be preserved."))
            assertTrue(turns > 1)
        }
        DocumentSession(4, "restart", DocumentExperiment(DocumentMode.LOCAL), "query", { true }).use { fresh ->
            assertEquals(0, JSONObject(fresh.search("condition", 3)).getJSONArray("fragments").length())
        }
    }
}
