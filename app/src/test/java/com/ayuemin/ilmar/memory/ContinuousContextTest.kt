package com.ayuemin.ilmar.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.attachments.*
import com.ayuemin.ilmar.chat.*
import com.ayuemin.ilmar.model.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ContinuousContextTest {
    private lateinit var app: Context
    private lateinit var db: ChatStore
    private lateinit var memory: ContextStore
    private val caps = ModelCapabilities(setOf("max_tokens"), contextLength = 4096, maxOutputTokens = 512)
    private val target = ModelTarget(ProviderConnection("p", "test", "https://example.com/v1"), RegisteredModel("m", "p", "model"), "key")
    private val summary = """{"goal":["Continue the same chat"],"facts":["version 42"],"decisions":[],"progress":[],"files":[],"tests":[],"errors":[],"next":[],"uncertain":[]}"""
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext(); app.deleteDatabase("ilmar_chats.db")
        com.ayuemin.ilmar.settings.RuntimeLimits.install(app); com.ayuemin.ilmar.settings.RuntimeLimits.reset()
        db = ChatStore(app); db.create(1, "Same chat"); memory = ContextStore(db)
    }
    @After fun cleanup() { db.close() }
    private fun engine(policy: ContextPolicy = ContextPolicy(output = 512), request: suspend () -> GenerationResult = { GenerationResult(summary) }) =
        ContextEngine(1, memory, AttachmentStore(app), policy, caps, target, { _, _, _, _, _ -> request() })
    @Test fun repeatedCompactionContinuesSameChatPastManyPhysicalWindowsWithoutDeletingOriginals() = runTest {
        memory.setRules(1, "Invariant: never delete protected-42")
        var compactCalls = 0
        repeat(120) { index ->
            db.append(1, ChatMessage("Question $index " + "x".repeat(800), true), null)
            val history = db.load().single().messages
            val projected = engine { compactCalls++; GenerationResult(summary) }.prepare(history, emptyList(), emptyList())
            assertEquals(history.last(), projected.history.last())
            assertTrue("round $index", projected.estimatedInput + projected.output <= projected.limit)
            assertTrue(projected.options.systemPrefix.contains("protected-42"))
            db.append(1, ChatMessage("Answer $index " + "y".repeat(800), false), null)
        }
        assertTrue(compactCalls > 5); assertEquals(240, db.load().single().messages.size)
        assertEquals(1L, db.load().single().id); assertTrue(memory.snapshot(1).capsules.size > 1)
        assertTrue(memory.search(1, "Question 1").contains("Question"))
    }
    @Test fun nonShrinkingManualCompactIsANoticeAndDoesNotBlockChat() = runTest {
        db.append(1, ChatMessage("hi", true), null); db.append(1, ChatMessage("hello", false), null); db.append(1, ChatMessage("continue", true), null)
        val history = db.load().single().messages
        val engine = engine(ContextPolicy(output = 512, recent = 1))
        val projected = engine.prepare(history, emptyList(), emptyList(), force = true)
        assertNotNull(engine.lastNotice); assertNull(memory.active(1)); assertEquals(history, projected.history)
        assertEquals(history, db.load().single().messages)
    }
    @Test fun failedAutomaticSummaryUsesHonestLocalExcerptsAndKeepsRulesAndOriginals() = runTest {
        repeat(8) { db.append(1, ChatMessage("version 42 " + "x".repeat(1500), true), null); db.append(1, ChatMessage("answer " + "y".repeat(1500), false), null) }
        db.append(1, ChatMessage("What next?", true), null)
        val original = db.load().single().messages; memory.setRules(1, "Never remove invariant")
        val projected = engine { GenerationResult("not JSON") }.prepare(original, emptyList(), emptyList())
        assertTrue(projected.estimatedInput + projected.output <= projected.limit)
        assertTrue(projected.options.systemPrefix.contains("Never remove invariant"))
        assertTrue(memory.active(1)!!.text.contains("Локальные выдержки"))
        assertEquals(original, db.load().single().messages)
    }
    @Test fun largeAttachmentDoesNotCreatePermanentContextFailureAndOriginalIsUnchanged() = runTest {
        val attachment = ChatAttachment("large", "Большой.txt", "text/plain", 300000, "факт ".repeat(50000))
        db.append(1, ChatMessage("Find important facts in the attached file", true, attachments = listOf(attachment)), null)
        val original = db.load().single().messages
        val projected = engine().prepare(original, emptyList(), emptyList())
        assertTrue(projected.estimatedInput + projected.output <= projected.limit)
        assertEquals(original, db.load().single().messages)
        assertEquals(original.single().text, projected.history.single().text)
        assertTrue(projected.history.single().attachments.single().text!!.contains("file_id=large"))
    }
    @Test fun fullToolRoundIsArchivedAndProjectionNeverContainsOrphanResponses() {
        val assistant = JSONObject("""{"role":"assistant","tool_calls":[{"id":"c","type":"function","function":{"name":"create_file","arguments":"{}"}}]}""").put("_trace_id", "round-1")
        val result = JSONObject().put("role", "tool").put("tool_call_id", "c").put("content", JSONObject().put("file_id", "file-42").put("text", "data".repeat(50000)).toString())
        val round = listOf(assistant, result)
        db.saveToolTrace(1, "round-1", round)
        val projected = ToolHistoryProjection.project(round, 1000)
        assertTrue(projected.joinToString().contains("round-1")); assertTrue(projected.joinToString().contains("file-42"))
        assertFalse(projected.any { it.optString("role") == "tool" })
        assertEquals(org.json.JSONArray(round).toString(), db.readToolTrace(1, "round-1"))
        assertNull(db.readToolTrace(2, "round-1"))
    }
    @Test fun defaultWindowUsesCatalogueAndTokenBudgetCanBeUnlimited() {
        assertEquals(32768, ContextPolicy().limit(caps.copy(contextLength = 200000)))
        assertEquals(32768, ContextPolicy().limit(ModelCapabilities()))
        assertEquals(8192, ContextPolicy(window = 8192).limit(caps.copy(contextLength = 200000)))
        val budget = TaskBudget(ContextPolicy(taskTokens = 0), caps)
        repeat(1000) { budget.before(10000, 1000, false, true) }
    }

    @Test fun failedCompactTransportNeverReplacesCapsuleOrOriginalHistoryAndCanResume() = runTest {
        repeat(6) { db.append(1, ChatMessage("message $it " + "x".repeat(700), it % 2 == 0), null) }
        val original = db.load().single().messages
        val policy = ContextPolicy(output = 512, recent = 2, automatic = false)
        engine(policy).prepare(original, emptyList(), emptyList(), force = true)
        val active = memory.active(1)!!
        repeat(2) { db.append(1, ChatMessage("new $it " + "y".repeat(700), it % 2 == 0), null) }
        val history = db.load().single().messages
        val fixtures = listOf(
            "data: [DONE]\n\n",
            "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n",
            "data: {broken}\n\ndata: [DONE]\n\n",
            "data: {\"error\":{\"message\":\"provider failed\"}}\n\ndata: [DONE]\n\n")
        for (fixture in fixtures) {
            val error = runCatching {
                engine(policy) { com.ayuemin.ilmar.model.SseReader.readResult(fixture.reader().buffered()) {} }
                    .prepare(history, emptyList(), emptyList(), force = true)
            }.exceptionOrNull()
            assertNotNull(fixture, error)
            assertEquals(active, memory.active(1))
            assertEquals(history, db.load().single().messages)
            assertEquals("failed", JSONObject(memory.snapshot(1).checkpoint!!).optString("state"))
        }
        engine(policy) { GenerationResult(summary.replace("version 42", "version 43")) }
            .prepare(history, emptyList(), emptyList(), force = true)
        assertTrue(memory.active(1)!!.text.contains("version 43"))
        assertEquals(history, db.load().single().messages)
    }
}
