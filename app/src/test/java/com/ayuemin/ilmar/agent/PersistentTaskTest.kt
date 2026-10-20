package com.ayuemin.ilmar.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.attachments.AttachmentStore
import com.ayuemin.ilmar.chat.*
import com.ayuemin.ilmar.memory.*
import com.ayuemin.ilmar.model.*
import com.ayuemin.ilmar.settings.RuntimeLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PersistentTaskTest {
    private lateinit var app: Context
    private lateinit var db: ChatStore
    private val caps = ModelCapabilities(setOf("max_tokens", "tools"), contextLength = 4096, maxOutputTokens = 512)
    private val target = ModelTarget(ProviderConnection("p", "test", "https://example.com/v1"), RegisteredModel("m", "p", "model"), "key")
    @Before fun setup() { app = ApplicationProvider.getApplicationContext(); app.deleteDatabase("ilmar_chats.db"); RuntimeLimits.install(app); RuntimeLimits.reset(); db = ChatStore(app); db.create(1, "Task"); db.append(1, ChatMessage("Create report", true), null) }
    @After fun cleanup() { db.close(); RuntimeLimits.reset() }
    private fun restart() { db.close(); db = ChatStore(app); DurableTaskStore(db).interruptOpen() }
    private suspend fun run(session: TaskSession, tools: List<BoundTool>, policy: ContextPolicy = ContextPolicy(automatic = false),
        request: suspend (GenerationOptions) -> GenerationResult): GenerationResult? =
        ContextExecution(1, ContextStore(db), AttachmentStore(app), policy, caps, target, status = {}, session = session,
            request = { _, options, _, _, _ -> request(options) }).run(db.load().single().messages, false, null, tools)
    @Test fun successSurvivesNetworkFailureAndRestartAndIsReplayedWithStableStepAndFileReference() = runTest {
        var effects = 0; var requests = 0
        val tool = BoundTool(ToolSchema("create_file", "test", JSONObject())) { effects++; "{\"file_id\":\"file-42\",\"saved_in_chat\":true}" }
        val first = DurableTaskStore(db).begin(1, db.load().single().messages)
        assertTrue(runCatching { run(first, listOf(tool)) {
            if (requests++ == 0) GenerationResult("", toolCalls = listOf(ModelToolCall("call", "create_file", "{\"name\":\"a.txt\",\"content\":\"data\"}")))
            else throw ProviderFailure("simulated network failure")
        } }.isFailure)
        val before = DurableTaskStore(db).snapshot(1)!!.getJSONArray("steps").getJSONObject(0)
        assertEquals("SUCCEEDED", before.getString("status")); assertEquals("file-42", before.getJSONArray("file_refs").getString(0))
        restart()
        val second = DurableTaskStore(db).begin(1, db.load().single().messages)
        assertEquals(first.taskId, second.taskId); assertEquals(first.runId, second.parentRunId); assertNotEquals(first.runId, second.runId)
        var round = 0
        val answer = run(second, listOf(tool)) { options ->
            assertNotNull(options.requestId)
            assertTrue(options.systemPrefix.contains("Фактическое состояние")); assertTrue(options.systemPrefix.contains("file-42"))
            if (round++ == 0) GenerationResult("", toolCalls = listOf(ModelToolCall("other-model-id", "create_file", "{ \"content\": \"data\", \"name\": \"a.txt\" }")))
            else {
                assertEquals("file-42", JSONObject(options.toolHistory.last().getString("content")).getString("file_id"))
                assertFalse(options.toolHistory.last().has("_reused_from")); GenerationResult("done")
            }
        }
        assertEquals("done", answer!!.text); assertEquals(1, effects)
        val after = DurableTaskStore(db).snapshot(1)!!.getJSONArray("steps").getJSONObject(0)
        assertEquals(before.getString("step_id"), after.getString("step_id"))
        assertEquals(2, db.readableDatabase.rawQuery("SELECT count(*) FROM agent_execution_links", null).use { it.moveToFirst(); it.getInt(0) })
        assertEquals("ANSWERED", DurableTaskStore(db).snapshot(1)!!.getString("status"))
    }
    @Test fun continuationKeepsTaskButNewExplicitRequestAllowsIntentionalNewAction() = runTest {
        var effects = 0
        val tool = BoundTool(ToolSchema("write", "test", JSONObject())) { effects++; "saved" }
        suspend fun perform(): TaskSession {
            val session = DurableTaskStore(db).begin(1, db.load().single().messages); var round = 0
            run(session, listOf(tool)) { if (round++ == 0) GenerationResult("", toolCalls = listOf(ModelToolCall("call", "write", "{}"))) else GenerationResult("done") }
            return session
        }
        val first = perform()
        db.append(1, ChatMessage("далее", true), null); val continued = perform()
        assertEquals(first.taskId, continued.taskId); assertEquals(1, effects)
        db.append(1, ChatMessage("New task: write the same operation intentionally", true), null); val new = perform()
        assertNotEquals(first.taskId, new.taskId); assertEquals(2, effects)
    }
    @Test fun failedResultCanBeRepairedButUnknownExternalEffectCannotBeReplayed() = runTest {
        val session = DurableTaskStore(db).begin(1, db.load().single().messages)
        var effects = 0; var round = 0
        val failed = BoundTool(ToolSchema("write", "test", JSONObject())) { effects++; "{\"error\":\"DENIED\"}" }
        run(session, listOf(failed)) { if (round++ == 0) GenerationResult("", toolCalls = listOf(ModelToolCall("one", "write", "{}"))) else GenerationResult("denied") }
        val id = DurableTaskStore(db).snapshot(1)!!.getJSONArray("steps").getJSONObject(0).getString("step_id")
        restart(); val retry = DurableTaskStore(db).begin(1, db.load().single().messages); round = 0
        run(retry, listOf(BoundTool(failed.schema) { effects++; "saved" })) { if (round++ == 0) GenerationResult("", toolCalls = listOf(ModelToolCall("two", "write", "{}"))) else GenerationResult("done") }
        assertEquals(2, effects); assertEquals(id, DurableTaskStore(db).snapshot(1)!!.getJSONArray("steps").getJSONObject(0).getString("step_id"))
        val unknown = BoundTool(ToolSchema("external", "test", JSONObject())) { effects++; throw CancellationException("after external commit") }
        round = 0
        assertTrue(runCatching { run(DurableTaskStore(db).begin(1, db.load().single().messages), listOf(unknown)) { GenerationResult("", toolCalls = listOf(ModelToolCall("c", "external", "{}"))) } }.isFailure)
        restart()
        val error = runCatching { run(DurableTaskStore(db).begin(1, db.load().single().messages), listOf(unknown)) { GenerationResult("", toolCalls = listOf(ModelToolCall("d", "external", "{}"))) } }.exceptionOrNull() as AgentFailure
        assertEquals("UNRESOLVED_TOOL", error.code); assertEquals(3, effects)
    }
    @Test fun retryCannotResetPersistedTokenBudget() = runTest {
        var requests = 0
        val policy = ContextPolicy(automatic = false, taskTokens = 2048)
        val first = DurableTaskStore(db).begin(1, db.load().single().messages)
        run(first, emptyList(), policy) { requests++; GenerationResult("done", TokenUsage(total = 1800)) }
        restart()
        val second = DurableTaskStore(db).begin(1, db.load().single().messages)
        assertEquals(1800L, DurableTaskStore(db).spent(second.taskId).tokens)
        assertTrue(runCatching { run(second, emptyList(), policy) { requests++; GenerationResult("must not call") } }.exceptionOrNull() is ContextFailure)
        assertEquals(1, requests)
    }
    @Test fun unknownPreviousCostCannotSilentlyBecomeZeroWhenUsdBudgetIsEnabled() = runTest {
        val first = DurableTaskStore(db).begin(1, db.load().single().messages)
        run(first, emptyList()) { GenerationResult("done", TokenUsage(total = 10)) }
        restart(); var requests = 0
        val error = runCatching { run(DurableTaskStore(db).begin(1, db.load().single().messages), emptyList(), ContextPolicy(automatic = false, taskUsd = "1")) { requests++; GenerationResult("no") } }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("неизвестна")); assertEquals(0, requests)
    }
    @Test fun clarificationAnswerKeepsTaskAndInterruptedRunHasParentLink() {
        val first = DurableTaskStore(db).begin(1, db.load().single().messages)
        DurableTaskStore(db).finish(first, "AWAITING_USER")
        db.append(1, ChatMessage("Use the blue theme", true), null)
        val answer = DurableTaskStore(db).begin(1, db.load().single().messages)
        assertEquals(first.taskId, answer.taskId)
        restart()
        val resumed = DurableTaskStore(db).begin(1, db.load().single().messages)
        // Same answer belongs to the same persisted task after its RUNNING run was interrupted.
        assertEquals(answer.taskId, resumed.taskId); assertEquals(answer.runId, resumed.parentRunId)
    }
    @Test fun clearingOrDeletingChatRemovesTasksAndDoesNotResurrectCheckpoint() {
        val tasks = DurableTaskStore(db); val session = tasks.begin(1, db.load().single().messages)
        tasks.reserve(session, "reserved", 100, null, "answer")
        db.clearMessages(1)
        assertNull(tasks.snapshot(1)); assertEquals(0L, tasks.spent(session.taskId).tokens)
        db.delete(1)
        assertTrue(runCatching { tasks.begin(1, emptyList()) }.isFailure)
    }
}
