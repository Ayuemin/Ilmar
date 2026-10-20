package com.ayuemin.ilmar.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.attachments.AttachmentStore
import com.ayuemin.ilmar.chat.ChatMessage
import com.ayuemin.ilmar.chat.ChatStore
import com.ayuemin.ilmar.memory.*
import com.ayuemin.ilmar.model.*
import com.ayuemin.ilmar.settings.RuntimeLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DurableTaskJournalTest {
    private lateinit var app: Context
    private lateinit var db: ChatStore
    private val target = ModelTarget(ProviderConnection("p", "test", "https://example.com/v1"), RegisteredModel("m", "p", "model"), "key")
    private val caps = ModelCapabilities(setOf("max_tokens", "tools"), contextLength = 8192)
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase("ilmar_chats.db")
        RuntimeLimits.install(app); RuntimeLimits.reset()
        db = ChatStore(app); db.create(1, "Task"); db.append(1, ChatMessage("Continue", true), null)
    }
    @After fun cleanup() { db.close(); RuntimeLimits.reset() }
    private fun restart() { db.close(); db = ChatStore(app) }
    private fun group(name: String, args: String, result: String? = null, id: String = "call"): List<JSONObject> {
        val assistant = JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject()
            .put("id", id).put("function", JSONObject().put("name", name).put("arguments", args))))
        return listOf(assistant) + if (result == null) emptyList() else listOf(JSONObject().put("role", "tool").put("tool_call_id", id).put("content", result))
    }
    private fun runner() = AgentLoopRunner(onCheckpoint = { id, messages -> db.checkpointExecutions(1, id, messages) },
        blockUnresolved = { id, name, args -> db.unresolvedExecution(1, id, name, args) })

    @Test fun productionContextExecutionBlocksDuplicateEffectAfterDatabaseReopen() = runTest {
        var effects = 0
        val tool = BoundTool(ToolSchema("external_write", "test", JSONObject())) {
            effects++
            throw CancellationException("process stopped after external commit before result persistence")
        }
        fun execution(args: String) = ContextExecution(1, ContextStore(db), AttachmentStore(app), ContextPolicy(automatic = false),
            caps, target, status = {}, request = { _, _, _, _, _ -> GenerationResult("", toolCalls = listOf(ModelToolCall("model-id", "external_write", args))) })
        val history = db.load().single().messages
        assertTrue(runCatching { execution("{\"name\":\"a\",\"content\":\"b\"}").run(history, false, null, listOf(tool)) }.exceptionOrNull() is CancellationException)
        assertEquals(1, effects)
        val row = db.readableDatabase.rawQuery("SELECT execution_id,trace_id,status FROM task_executions", null).use {
            assertTrue(it.moveToFirst()); Triple(it.getString(0), it.getString(1), it.getString(2))
        }
        assertEquals("UNKNOWN", row.third)
        restart()
        assertEquals("UNKNOWN", db.executionStatus(1, row.first))
        assertTrue(db.readToolTrace(1, row.second)!!.contains("_started_calls"))
        val error = runCatching { execution("{ \"content\": \"b\", \"name\": \"a\" }").run(db.load().single().messages, false, null, listOf(tool)) }.exceptionOrNull() as AgentFailure
        assertEquals("UNRESOLVED_TOOL", error.code); assertEquals(1, effects)
        assertEquals(history, db.load().single().messages)
        assertTrue(db.recoveryNote(1).contains("UNKNOWN"))
    }

    @Test fun onlyEnteredCallsBecomeUnknownAndIntentIsDurableBeforeExternalAction() = runTest {
        var trace = ""
        val first = BoundTool(ToolSchema("write", "test", JSONObject())) {
            restart()
            assertEquals("UNKNOWN", db.executionStatus(1, "$trace:first"))
            assertNull(db.executionStatus(1, "$trace:second"))
            assertEquals(1, JSONArray(db.readToolTrace(1, trace)).getJSONObject(0).getJSONArray("_started_calls").length())
            throw CancellationException("stop")
        }
        val second = BoundTool(ToolSchema("write_second", "test", JSONObject())) { fail("not entered"); "unexpected" }
        val runner = AgentLoopRunner(onCheckpoint = { id, messages -> trace = id; db.checkpointExecutions(1, id, messages) },
            blockUnresolved = { id, name, args -> db.unresolvedExecution(1, id, name, args) })
        assertTrue(runCatching { runner.run(listOf(first, second)) { _, _ -> GenerationResult("", toolCalls = listOf(
            ModelToolCall("first", "write", "{}"), ModelToolCall("second", "write_second", "{}"))) } }.isFailure)
        restart()
        assertNull(db.executionStatus(1, "$trace:second"))
        assertFalse(db.unresolvedExecution(1, "next:second", "write_second", "{}"))
    }

    @Test fun completedAndFailedResultsAndTraceSurviveRestartWithoutModelIdCollisions() = runTest {
        var round = 0
        val tool = BoundTool(ToolSchema("write", "test", JSONObject())) { if (round == 1) "saved" else "{\"error\":\"denied\"}" }
        runner().run(listOf(tool)) { _, _ -> if (round++ < 2) GenerationResult("", toolCalls = listOf(ModelToolCall("same", "write", "{}"))) else GenerationResult("done") }
        restart()
        val rows = db.readableDatabase.rawQuery("SELECT execution_id,trace_id,status,result FROM task_executions ORDER BY rowid", null).use { c ->
            buildList { while (c.moveToNext()) add((0..3).map { c.getString(it) }) }
        }
        assertEquals(2, rows.size); assertNotEquals(rows[0][0], rows[1][0])
        assertEquals(listOf("SUCCEEDED", "FAILED"), rows.map { it[2] })
        rows.forEach { assertEquals(it[3], JSONArray(db.readToolTrace(1, it[1])).getJSONObject(1).getString("content")) }
        assertFalse(db.unresolvedExecution(1, "next:same", "write", "{}"))
    }

    @Test fun safeReadOperationsAreAvailableDespiteLegacyUnknownButWritesAreBlocked() = runTest {
        val safe = listOf("calculator", "read_file", "fetch_url", "web_search", "search_history", "recall_memory", "ask_user", "todo")
        for (name in safe) {
            db.checkpointExecutions(1, "old-$name", group(name, "{}"))
            restart()
            var calls = 0; var round = 0
            runner().run(listOf(BoundTool(ToolSchema(name, "test", JSONObject())) { calls++; "ok" })) { _, _ ->
                if (round++ == 0) GenerationResult("", toolCalls = listOf(ModelToolCall("new", name, "{}"))) else GenerationResult("done")
            }
            assertEquals(name, 1, calls)
        }
        db.checkpointExecutions(1, "old-plan", group("todo", "{\"steps\":[]}"))
        assertTrue(db.unresolvedExecution(1, "new:call", "todo", "{ \"steps\" : [] }"))
        assertFalse(db.unresolvedExecution(2, "new:call", "todo", "{\"steps\":[]}"))
    }

    @Test fun matchingIsCanonicalForNestedObjectsButPreservesContentAndArrayOrder() {
        db.checkpointExecutions(1, "old", group("write", "{\"object\":{\"a\":1,\"b\":2},\"items\":[1,2],\"content\":\"a b\"}"))
        assertTrue(db.unresolvedExecution(1, "new:call", "write", "{\"content\":\"a b\",\"items\":[1,2],\"object\":{\"b\":2,\"a\":1}}"))
        assertFalse(db.unresolvedExecution(1, "new:call", "write", "{\"content\":\"ab\",\"items\":[1,2],\"object\":{\"b\":2,\"a\":1}}"))
        assertFalse(db.unresolvedExecution(1, "new:call", "write", "{\"content\":\"a b\",\"items\":[2,1],\"object\":{\"b\":2,\"a\":1}}"))
    }

    @Test fun ambiguousTimeoutRemainsUnknownAndBlocksDuplicateWithinSameTrace() = runTest {
        var calls = 0
        val tool = BoundTool(ToolSchema("write", "test", JSONObject())) { calls++; "{\"error\":\"TOOL_TIMEOUT\"}" }
        val error = runCatching { runner().run(listOf(tool)) { _, _ -> GenerationResult("", toolCalls = listOf(
            ModelToolCall("first", "write", "{}"), ModelToolCall("second", "write", "{}"))) } }.exceptionOrNull() as AgentFailure
        assertEquals("UNRESOLVED_TOOL", error.code); assertEquals(1, calls)
        restart()
        assertTrue(db.unresolvedExecution(1, "new:call", "write", "{}"))
    }

    @Test fun traceAndExecutionCheckpointRollBackTogetherOnDatabaseFailure() {
        db.writableDatabase.execSQL("CREATE TRIGGER reject_execution BEFORE INSERT ON task_executions BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        assertTrue(runCatching { db.checkpointExecutions(1, "rollback", group("write", "{}")) }.isFailure)
        restart()
        assertNull(db.readToolTrace(1, "rollback")); assertNull(db.executionStatus(1, "rollback:call"))
    }

    @Test fun staleCheckpointsCannotEraseOrContradictConfirmedResults() {
        db.checkpointExecutions(1, "trace", group("write", "{}", "saved"))
        val trace = db.readToolTrace(1, "trace")
        db.checkpointExecutions(1, "trace", group("write", "{}"))
        db.checkpointExecutions(1, "trace", group("write", "{}", "{\"error\":\"stale\"}"))
        restart()
        assertEquals("SUCCEEDED", db.executionStatus(1, "trace:call"))
        assertEquals(trace, db.readToolTrace(1, "trace"))
    }

    @Test fun executionMarkersNeverEnterProviderToolHistory() {
        val messages = group("read_file", "{}", "ok")
        messages.first().put("_trace_id", "trace").put("_started_calls", JSONArray().put("call"))
        val projected = ToolHistoryProjection.project(messages, 1000)
        assertFalse(projected.first().has("_trace_id")); assertFalse(projected.first().has("_started_calls"))
    }
}
