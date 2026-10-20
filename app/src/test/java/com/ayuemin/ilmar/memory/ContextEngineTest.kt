package com.ayuemin.ilmar.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.attachments.AttachmentStore
import com.ayuemin.ilmar.chat.*
import com.ayuemin.ilmar.model.*
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
class ContextEngineTest {
    private lateinit var app: Context
    private lateinit var db: ChatStore
    private lateinit var memory: ContextStore
    private val caps = ModelCapabilities(setOf("max_tokens"), contextLength = 8192, promptPrice = "0.000001", completionPrice = "0.000002", requestPrice = "0")
    private val target = ModelTarget(ProviderConnection("p", "Provider", "https://openrouter.ai/api/v1"), RegisteredModel("m", "p", "test/model"), "secret")
    private val summary = """{"goal":["Continue"],"facts":["version 42"],"decisions":[],"progress":[],"files":[],"tests":[],"errors":[],"next":["test"],"uncertain":[]}"""
    @Before fun setup() { app = ApplicationProvider.getApplicationContext(); app.deleteDatabase("ilmar_chats.db"); db = ChatStore(app); db.create(1, "Chat"); memory = ContextStore(db) }
    @After fun cleanup() { db.close() }
    private fun history(pairs: Int = 6, size: Int = 700): List<ChatMessage> {
        repeat(pairs) { index -> db.append(1, ChatMessage("version 42 question $index " + "x".repeat(size), true), null)
            db.append(1, ChatMessage("answer $index " + "y".repeat(size), false), null) }
        return db.load().single().messages
    }
    private fun engine(policy: ContextPolicy = ContextPolicy(recent = 2), request: suspend (List<ChatMessage>, GenerationOptions, String, Int, Int) -> GenerationResult) =
        ContextEngine(1, memory, AttachmentStore(app), policy, caps, target, request)
    @Test fun compactKeepsFullHistoryAndExactTailAndRulesAndReducesProjection() = runTest {
        val history = history(); val rules = "Never delete branch protected-42\nВерсия 42"; memory.setRules(1, rules)
        var calls = 0
        val result = engine { _, options, purpose, _, _ ->
            calls++; assertEquals("compact", purpose); assertTrue(options.systemPrefix.contains(rules)); GenerationResult(summary)
        }.prepare(history, emptyList(), emptyList(), force = true)
        assertTrue(calls > 0)
        assertEquals(history.takeLast(2), result.history)
        assertEquals(history, db.load().single().messages)
        assertTrue(result.options.systemPrefix.contains(rules))
        assertTrue(result.options.systemPrefix.contains("version 42"))
        assertTrue(result.saved > 0)
        assertTrue(result.estimatedInput + result.output <= result.limit)
        assertNull(memory.checkpoint(1))
        assertTrue(memory.snapshot(1).capsules.single().active)
        var repeatCalls = 0
        engine { _, _, _, _, _ -> repeatCalls++; GenerationResult(summary) }.prepare(history, emptyList(), emptyList())
        assertEquals(0, repeatCalls)
    }
    @Test fun interruptedCompactionResumesOnlyCompletedPacketsAndKeepsOldCapsuleInactiveUntilSuccess() = runTest {
        val history = history(8, 3000); var paid = 0
        val failed = engine { _, _, _, _, _ -> if (++paid == 2) throw CancellationException("stop"); GenerationResult(summary) }
        assertTrue(runCatching { failed.prepare(history, emptyList(), emptyList(), true) }.isFailure)
        assertNull(memory.active(1)); assertEquals(history, db.load().single().messages)
        val checkpoint = JSONObject(memory.checkpoint(1)!!); assertEquals(1, checkpoint.getInt("next"))
        memory.interrupt(1)
        var resumed = 0
        engine { _, _, _, _, _ -> resumed++; GenerationResult(summary) }.prepare(history, emptyList(), emptyList(), true)
        assertEquals(checkpoint.getInt("total") - 1, resumed)
        assertNotNull(memory.active(1)); assertNull(memory.checkpoint(1))
    }
    @Test fun durablePaidPacketIsRecoveredWithoutCallingModelAgain() = runTest {
        val history = history(8, 3000)
        assertTrue(runCatching { engine { _, _, _, _, _ -> throw CancellationException("process died after response") }
            .prepare(history, emptyList(), emptyList(), true) }.isFailure)
        val checkpoint = JSONObject(memory.checkpoint(1)!!)
        val completion = GenerationResult(summary, TokenUsage(50, 10, 60, costUsd = "0.001"))
        db.recordUsage(1, "paid", completion.usage, "completed", "compact", completion = completion)
        assertEquals(summary, JSONObject(memory.checkpoint(1)!!).getString("pending_result"))
        var calls = 0
        engine { _, _, _, _, _ -> calls++; GenerationResult(summary) }.prepare(history, emptyList(), emptyList(), true)
        assertEquals(checkpoint.getInt("total") - 1, calls)
        assertEquals(1, db.usage(1).compactCalls)
        assertEquals("0.001", db.usage(1).compactCost)
    }
    @Test fun malformedSummaryDoesNotReplacePreviousVersionOrHistory() = runTest {
        val history = history()
        engine { _, _, _, _, _ -> GenerationResult(summary) }.prepare(history, emptyList(), emptyList(), true)
        val original = memory.active(1)!!
        assertTrue(runCatching { engine { _, _, _, _, _ -> GenerationResult("bad JSON") }.prepare(history, emptyList(), emptyList(), true) }.isFailure)
        assertEquals(original, memory.active(1)); assertEquals(history, db.load().single().messages)
        memory.delete(1, original.id)
        assertNull(memory.active(1)); assertEquals(history, db.load().single().messages)
    }
    @Test fun localFtsFindsOriginalFactAfterCompactionWithoutModelCall() = runTest {
        val history = history()
        engine { _, _, _, _, _ -> GenerationResult(summary) }.prepare(history, emptyList(), emptyList(), true)
        assertTrue(memory.search(1, "version 42").contains("version"))
        db.clearMessages(1)
        assertEquals("", memory.search(1, "version 42")); assertNull(memory.active(1))
    }
    @Test fun lateCompactionCannotRestoreMemoryAfterHistoryWasCleared() {
        val history = history(2, 50)
        val through = history.last().id
        val checkpoint = JSONObject().put("through", through).put("state", "running")
        memory.checkpoint(1, checkpoint)
        db.clearMessages(1)
        memory.checkpoint(1, checkpoint)
        assertNull(memory.checkpoint(1))
        assertThrows(ContextFailure::class.java) { memory.save(1, through, summary, ContextEstimate.source(history, through), 200, 100) }
        assertNull(memory.active(1))
    }
    @Test fun toolsAndToolResultsAreBudgetedAsCompleteRoundInsteadOfTruncated() = runTest {
        val history = history(1, 10)
        val huge = listOf(JSONObject().put("role", "tool").put("content", "x".repeat(40000)))
        var calls = 0
        assertTrue(runCatching { engine(ContextPolicy(automatic = false)) { _, _, _, _, _ -> calls++; GenerationResult(summary) }
            .prepare(history, emptyList(), huge) }.isFailure)
        assertEquals(0, calls); assertEquals(40000, huge.single().getString("content").length)
    }
    @Test fun automaticCompactionOccursBeforeAnswerAndAllCallsShareBudget() = runTest {
        val history = history(8, 1000); val purposes = mutableListOf<String>()
        val execution = ContextExecution(1, memory, AttachmentStore(app), ContextPolicy(recent = 2), caps, target, {}) { projected, options, purpose, _, _ ->
            purposes += purpose
            if (purpose == "compact") GenerationResult(summary, TokenUsage(100, 20, 120, costUsd = "0.001"))
            else { assertEquals(history.takeLast(2), projected); assertNotEquals("", options.systemPrefix); GenerationResult("answer") }
        }
        assertEquals("answer", execution.run(history, false, null, emptyList())!!.text)
        assertTrue(purposes.first() == "compact" && purposes.last() == "answer")
    }
}
