package com.ayuemin.ilmar.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.agent.DurableTaskStore
import com.ayuemin.ilmar.attachments.AttachmentStore
import com.ayuemin.ilmar.chat.ChatMessage
import com.ayuemin.ilmar.chat.ChatStore
import com.ayuemin.ilmar.memory.*
import com.ayuemin.ilmar.model.TokenUsage
import com.ayuemin.ilmar.settings.RuntimeLimits
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DiagnosticsAcceptanceTest {
    private lateinit var app: Context
    @Before fun setup() { app = ApplicationProvider.getApplicationContext(); app.deleteDatabase("ilmar_chats.db"); RuntimeLimits.install(app); RuntimeLimits.reset() }
    private fun export(): String = java.io.ByteArrayOutputStream().also { Diagnostics(app).export(it) }.toString("UTF-8")
    @Test fun ownJsonlExportImportsAsUtf8AttachmentEvenWithOctetStreamMime() {
        Diagnostics(app).record("ACCEPTANCE", 1, JSONObject().put("message", "Короткий факт 42"))
        val text = export()
        val item = AttachmentStore(app).ingest(text.byteInputStream(), "Ilmar-log.jsonl", "application/octet-stream")
        assertEquals(text, item.text)
        item.text!!.lineSequence().filter { it.isNotBlank() }.forEach { JSONObject(it) }
        assertTrue(item.text!!.contains("Короткий факт 42"))
        assertTrue(runCatching { AttachmentStore(app).ingest(byteArrayOf(0xC3.toByte(), 0x28).inputStream(), "bad.jsonl", "application/octet-stream") }.isFailure)
        assertTrue(runCatching { AttachmentStore(app).ingest(byteArrayOf(0, 1, 2).inputStream(), "binary.jsonl", "application/octet-stream") }.isFailure)
    }
    @Test fun exportExplainsUnitsScopeZerosAndContextPreferences() {
        RuntimeLimits.save(RuntimeLimits.fields.associate { it.key to RuntimeLimits.get(it.key) } + mapOf("cache_minutes" to 0, "model_seconds" to 0))
        val header = JSONObject(export().lineSequence().first())
        val defs = header.getJSONArray("limit_definitions")
        fun definition(key: String) = (0 until defs.length()).map { defs.getJSONObject(it) }.single { it.getString("key") == key }
        assertEquals("KiB", definition("argument_kib").getString("unit")); assertEquals("global_all_chats", definition("argument_kib").getString("scope"))
        assertEquals("not_allowed", definition("argument_kib").getString("zero_meaning"))
        assertEquals("cache_disabled", definition("cache_minutes").getString("zero_meaning"))
        assertEquals("limit_disabled", definition("model_seconds").getString("zero_meaning"))
        assertEquals("seconds", definition("model_seconds").getString("unit"))
        assertEquals("automatic_up_to_32768", header.getJSONObject("context_preferences").getString("window_zero_meaning"))
        assertEquals("persistent_task_across_retries", header.getString("model_budget_scope"))
    }
    @Test fun durableRunLinksAndEmbeddingUnknownCostAreExportedWithoutSecrets() {
        ChatStore(app).use { db ->
            db.create(1, "Export"); db.append(1, ChatMessage("Goal api_key=private-secret", true), null)
            val tasks = DurableTaskStore(db); val first = tasks.begin(1, db.load().single().messages)
            tasks.reserve(first, "model-request", 200, null, "answer"); tasks.completeModel("model-request", "FAILED", null); tasks.finish(first, "FAILED")
            val retry = tasks.begin(1, db.load().single().messages)
            assertEquals(first.runId, retry.parentRunId)
            val ledger = EmbeddingLedger(db); val embedding = ledger.start(1, "index", "embed", 2, 100)
            ledger.finish(embedding, "completed", TokenUsage(input = 10, cached = 2))
        }
        val text = export(); assertFalse(text.contains("private-secret"))
        val rows = text.lineSequence().filter { it.isNotBlank() }.map { JSONObject(it) }.toList()
        val runs = rows.filter { it.optString("event") == "TASK_RUN" }
        assertEquals(2, runs.size); assertEquals(runs[0].getString("run_id"), runs[1].getString("parent_run_id"))
        val usage = rows.single { it.optString("event") == "EMBEDDING_USAGE" }.getJSONObject("usage")
        assertEquals(10, usage.getInt("input_tokens")); assertEquals(2, usage.getInt("cached_tokens")); assertTrue(usage.isNull("cost_usd"))
        val reservation = rows.single { it.optString("event") == "TASK_MODEL_RESERVATION" }
        assertTrue(reservation.isNull("reserved_cost")); assertEquals("FAILED", reservation.getString("status"))
    }
}
