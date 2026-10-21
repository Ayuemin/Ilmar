package com.ayuemin.ilmar.chat

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.model.*
import com.ayuemin.ilmar.memory.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AuditRuntimeTest {
    private lateinit var app: Application
    private lateinit var registry: ProviderRegistry
    private val dispatcher = StandardTestDispatcher()
    private val owner = ViewModelStore()
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        app = ApplicationProvider.getApplicationContext()
        listOf("ilmar_chats.db", "ilmar_tools.db", "ilmar_journal.db").forEach { app.deleteDatabase(it) }
        listOf("ilmar_ui", "ilmar_models", "ilmar_requests", "ilmar_chat_modes", "ilmar_embeddings", "ilmar_context", "ilmar_limits", "ilmar_tool_permissions").forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
        registry = ProviderRegistry(app, MemorySecrets())
        registry.saveConnection(null, "Fixture", "https://fixture.invalid/v1/chat/completions", listOf("m"), "synthetic")
    }
    @After fun cleanup() { owner.clear(); Dispatchers.resetMain() }
    private fun controller(provider: ChatProvider): ChatViewModel = ChatViewModel(app, provider, registry, dispatcher).also { owner.put("audit", it) }
    @Test fun oneRequestWithOneThousandDeltasAddsOnePhaseAndClearWaitsForCancellation() = runTest(dispatcher) {
        var calls = 0
        val provider = object : ChatProvider {
            override suspend fun stream(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, onDelta: (String) -> Unit): String {
                calls++; repeat(1000) { onDelta("x") }; awaitCancellation()
            }
        }
        val vm = controller(provider); val chat = vm.state.value.selectedId
        vm.send("synthetic"); runCurrent()
        assertEquals(1, calls)
        assertEquals(1, vm.state.value.activityEvents.count { it == "Модель формирует ответ…" })
        assertTrue(vm.state.value.activityEvents.size <= 3)
        vm.clear(chat); advanceUntilIdle()
        assertNull(vm.state.value.activeChatId); assertTrue(vm.state.value.mutating.isEmpty())
        assertTrue(vm.state.value.chats.single().messages.isEmpty())
        assertEquals(0, vm.usage(chat).sent)
    }
    @Test fun gaugeInvalidatesOnModelPolicyChangesAndClear() = runTest(dispatcher) {
        val vm = controller(object : ChatProvider {
            override suspend fun stream(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, onDelta: (String) -> Unit) = "answer"
        })
        val chat = vm.state.value.selectedId
        vm.send("question"); advanceUntilIdle(); assertNotNull(vm.compactGauge(chat))
        ContextPreferences(app).save(ContextPolicy(window = 8192, output = 512, automatic = false))
        assertNull(vm.compactGauge(chat))
        vm.send("another"); advanceUntilIdle(); assertFalse(vm.compactGauge(chat)!!.automatic)
        vm.clear(chat); advanceUntilIdle(); assertNull(vm.compactGauge(chat))
    }
    @Test fun ledgerDistinguishesUnsentFromPaidAndKeepsDecimalPrecision() {
        val ledger = UsageLedger(listOf(
            UsageRecord(TokenUsage(input=0, output=0, total=0, costUsd="0"), "not_sent"),
            UsageRecord(TokenUsage(input=10, output=2, cached=3, reasoning=1, costUsd="0.123456789012345678901"), "completed"),
            UsageRecord(TokenUsage(costUsd="0.002"), "completed", "compact"),
            UsageRecord(TokenUsage(), "failed")))
        assertEquals(4, ledger.calls); assertEquals(3, ledger.sent); assertEquals(1, ledger.notSent)
        assertEquals("0.125456789012345678901", ledger.costUsd); assertTrue(ledger.incompleteCost)
        assertEquals(3L, ledger.cached); assertEquals(1L, ledger.reasoning)
    }
    @Test fun modelLifecycleHasOneStartAndTerminalWithSameRequestAndRound() = runTest(dispatcher) {
        val vm = controller(object : ChatProvider {
            override suspend fun stream(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, onDelta: (String) -> Unit): String {
                repeat(1000) { onDelta("x") }; return "done"
            }
        })
        vm.send("synthetic question"); advanceUntilIdle()
        val output=java.io.ByteArrayOutputStream(); com.ayuemin.ilmar.diagnostics.Diagnostics(app).export(output)
        val rows=output.toString("UTF-8").lineSequence().filter { it.isNotBlank() }.map { org.json.JSONObject(it) }.toList()
        val start=rows.single { it.optString("event")=="MODEL_START" }.getJSONObject("details")
        val finish=rows.single { it.optString("event")=="MODEL_FINISH" }.getJSONObject("details")
        assertEquals(start.getString("request_id"),finish.getString("request_id"))
        assertEquals(start.getString("round_id"),finish.getString("round_id"))
        assertEquals("completed",finish.getString("status"))
        assertFalse(output.toString("UTF-8").contains("synthetic question"))
    }
    @Test fun deleteRemovesInterventionsAndFollowUpsAreTaskScopedAndOrdered() {
        ChatStore(app).use { db ->
            db.create(42, "Fixture")
            val first = db.stageIntervention(42, "old-now", "task-a")
            val second = db.stageIntervention(42, "new-queued", "task-a")
            db.updateIntervention(42, first, "NOW"); db.updateIntervention(42, second, "QUEUED")
            assertTrue(db.deliverInterventions(42, "FOLLOW_UP", "task-b").isEmpty())
            assertEquals(listOf("old-now", "new-queued"), db.deliverInterventions(42, "FOLLOW_UP", "task-a").map { it.text })
            assertTrue(db.deliverInterventions(42, "FOLLOW_UP", "task-a").isEmpty())
            db.stageIntervention(42, "legacy unbound")
            assertTrue(db.deliverInterventions(42, "NOW", "task-a").isEmpty())
            db.delete(42)
        }
        ChatStore(app).use { db ->
            assertTrue(db.interventions(42).isEmpty())
            db.readableDatabase.rawQuery("SELECT count(*) FROM interventions WHERE chat_id=42", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
    }
    @Test fun migrationFromElevenKeepsQueuedRowsUnbound() {
        val old = app.openOrCreateDatabase("ilmar_chats.db", 0, null)
        // Build a real v11 fixture from the current schema, removing only the newly introduced column.
        old.close()
        ChatStore(app).use { db -> db.create(7,"Old"); db.stageIntervention(7,"keep me") }
        app.openOrCreateDatabase("ilmar_chats.db", 0, null).use { db ->
            db.execSQL("ALTER TABLE interventions RENAME TO interventions_new")
            db.execSQL("CREATE TABLE interventions(id INTEGER PRIMARY KEY AUTOINCREMENT,chat_id INTEGER NOT NULL,text TEXT NOT NULL,status TEXT NOT NULL,created_at INTEGER NOT NULL)")
            db.execSQL("INSERT INTO interventions SELECT id,chat_id,text,status,created_at FROM interventions_new")
            db.execSQL("DROP TABLE interventions_new")
            db.version = 11
        }
        ChatStore(app).use { db -> assertEquals("keep me", db.interventions(7).single().text); assertNull(db.interventions(7).single().taskId) }
    }
}
