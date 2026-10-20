package com.ayuemin.ilmar.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.agent.*
import com.ayuemin.ilmar.attachments.*
import com.ayuemin.ilmar.chat.*
import com.ayuemin.ilmar.model.*
import com.ayuemin.ilmar.settings.RuntimeLimits
import com.ayuemin.ilmar.tools.ToolDataStore
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ContinuousAcceptanceTest {
    private lateinit var app: Context
    private lateinit var db: ChatStore
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext(); app.deleteDatabase("ilmar_chats.db"); app.deleteDatabase("ilmar_tools.db")
        listOf("ilmar_models", "ilmar_embeddings", "ilmar_ui", "ilmar_context").forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
        RuntimeLimits.install(app); RuntimeLimits.reset(); db = ChatStore(app); db.create(1, "Acceptance")
    }
    @After fun cleanup() { db.close(); RuntimeLimits.reset() }
    @Test fun compactAndEmbeddingFailuresPreserveHistoryLivePlanAndVersionedFileAndAllowNextAnswer() = runTest {
        repeat(10) { i -> db.append(1, ChatMessage("Version 42 question $i " + "x".repeat(500), true), null); db.append(1, ChatMessage("Answer $i " + "y".repeat(500), false), null) }
        val files = AttachmentStore(app); files.file("file-42").writeText("Original protected file")
        db.append(1, ChatMessage("File: Report.md", false, attachments = listOf(ChatAttachment("file-42", "Report.md", "text/markdown", 23, "Original protected file", version = 2, previousId = "file-41"))), null)
        val history = db.load().single().messages
        val plan = ToolDataStore(app).use { it.plan(1, JSONArray("""[{"text":"Actual unfinished step","status":"in_progress"}]""")) }
        val registry = ProviderRegistry(app, MemorySecrets())
        registry.saveConnection(null, "Embeddings", "https://embed.example/v1", listOf("embed"), "key")
        val connection = registry.snapshot().providers.single(); val config = EmbeddingConfig(true, connection.id, "embed")
        EmbeddingPreferences(app).save(config)
        SemanticArchive(db).index(1, history, config.space(connection.endpoint), { texts -> texts.map { floatArrayOf(1f) } })
        val failedClient = OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request()).code(503).message("failed").protocol(Protocol.HTTP_1_1).body("{}".toResponseBody("application/json".toMediaType())).build() }.build()
        val retrieval = ArchiveRetrieval(app, registry, EmbeddingClient(failedClient))
        val target = ModelTarget(ProviderConnection("p", "test", "https://example.com/v1"), RegisteredModel("m", "p", "model"), "key")
        val memory = ContextStore(db); memory.setRules(1, "Never delete protected version 42")
        val caps = ModelCapabilities(setOf("max_tokens"), contextLength = 4096, maxOutputTokens = 512)
        var compact = 0; var answers = 0
        val result = ContextExecution(1, memory, files, ContextPolicy(recent = 2), caps, target, status = {},
            semanticSearch = { query, through -> retrieval.search(1, query, through) },
            request = { _, options, purpose, _, _ ->
                if (purpose == "compact") { compact++; throw ProviderFailure("simulated Compact failure") }
                answers++; assertTrue(options.systemPrefix.contains("Actual unfinished step")); assertTrue(options.systemPrefix.contains("Never delete")); GenerationResult("continued")
            }).run(history, false, null, emptyList())
        assertEquals("continued", result!!.text); assertTrue(compact > 0); assertEquals(1, answers)
        assertEquals(history, db.load().single().messages); assertEquals(plan, memory.factualSnapshot(1).plan.toString())
        assertTrue(memory.active(1)!!.text.contains("Локальные выдержки")); assertEquals("Original protected file", files.file("file-42").readText())
        assertEquals(2, db.load().single().messages.last().attachments.single().version)
        assertTrue(memory.search(1, "Version 42").contains("Version 42"))
        assertTrue(EmbeddingLedger(db).summary(1).getBoolean("incomplete_cost"))
        db.close(); db = ChatStore(app)
        assertEquals(history, db.load().single().messages); assertEquals(plan, ContextStore(db).factualSnapshot(1).plan.toString())
    }
    @Test fun legacyDuplicatePlanIdsAreRepairedWithoutLosingStatuses() {
        ToolDataStore(app).use { data ->
            data.writableDatabase.execSQL("INSERT INTO plans VALUES(?,?)", arrayOf(1, """[{"id":"same","text":"One","status":"completed"},{"id":"same","text":"Two","status":"pending"}]"""))
            val plan = JSONArray(data.plan(1, null))
            assertNotEquals(plan.getJSONObject(0).getString("id"), plan.getJSONObject(1).getString("id"))
            assertEquals("completed", plan.getJSONObject(0).getString("status")); assertEquals("pending", plan.getJSONObject(1).getString("status"))
        }
    }
}
