package com.ayuemin.ilmar.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.agent.*
import com.ayuemin.ilmar.attachments.*
import com.ayuemin.ilmar.chat.*
import com.ayuemin.ilmar.memory.*
import com.ayuemin.ilmar.model.*
import com.ayuemin.ilmar.settings.RuntimeLimits
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
class PlanFileStateTest {
    private lateinit var app: Context
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext(); app.deleteDatabase("ilmar_chats.db"); app.deleteDatabase("ilmar_tools.db")
        RuntimeLimits.install(app); RuntimeLimits.reset()
    }
    @Test fun reorderingPlanKeepsStableIdsAndStatusesAcrossReopenAndAllowsExplicitStatusChanges() {
        lateinit var first: JSONArray
        ToolDataStore(app).use { data ->
            first = JSONArray(data.plan(1, JSONArray("""[{"text":"Build","status":"completed"},{"text":"Test","status":"in_progress"}]""")))
            val reordered = JSONArray(data.plan(1, JSONArray().put(JSONObject().put("id", first.getJSONObject(1).getString("id")).put("text", "Test"))
                .put(JSONObject().put("id", first.getJSONObject(0).getString("id")).put("text", "Build"))))
            assertEquals("in_progress", reordered.getJSONObject(0).getString("status")); assertEquals("completed", reordered.getJSONObject(1).getString("status"))
        }
        ToolDataStore(app).use { data ->
            val reopened = JSONArray(data.plan(1, null))
            assertEquals(first.getJSONObject(1).getString("id"), reopened.getJSONObject(0).getString("id"))
            reopened.getJSONObject(0).put("status", "completed")
            val changed = JSONArray(data.plan(1, reopened))
            assertEquals(first.getJSONObject(1).getString("id"), changed.getJSONObject(0).getString("id"))
            assertEquals("completed", changed.getJSONObject(0).getString("status"))
            val invalid = JSONArray().put(changed.getJSONObject(0)).put(changed.getJSONObject(0))
            assertTrue(runCatching { data.plan(1, invalid) }.isFailure)
            assertEquals(changed.toString(), data.plan(1, null))
        }
    }
    @Test fun legacyPlanGetsIdsOnceWithoutLosingShortFactsOrStatuses() {
        ToolDataStore(app).use { data -> data.writableDatabase.execSQL("INSERT INTO plans VALUES(?,?)", arrayOf(1, """[{"text":"42","status":"completed"}]""")) }
        val before = ToolDataStore(app).use { it.plan(1, null) }
        val after = ToolDataStore(app).use { it.plan(1, null) }
        assertEquals(before, after); assertEquals("completed", JSONArray(after).getJSONObject(0).getString("status"))
        assertTrue(JSONArray(after).getJSONObject(0).getString("id").isNotBlank())
    }
    @Test fun sameFileIsReusedAndExplicitOrChangedContentVersionsNeverOverwriteOriginals() = runTest {
        ChatStore(app).use { it.create(1, "Files") }
        NativeTools.install(app)
        val modes = ChatModePreferences(app); modes.setEnabled(1, "tool_create_file", true)
        val permissions = ToolPermissionBroker(app); permissions.setGranted("create_file", true)
        val create = ToolRegistry.authorized(1, modes, permissions).first { it.schema.name == "create_file" }
        fun args(content: String, mode: String? = null) = JSONObject().put("name", "Report.md").put("content", content).apply { mode?.let { put("mode", it) } }
        val first = JSONObject(create.execute(args("original")))
        val same = JSONObject(create.execute(args("original")))
        assertEquals(first.getString("file_id"), same.getString("file_id")); assertTrue(same.getBoolean("reused"))
        val second = JSONObject(create.execute(args("original", "new_version")))
        assertNotEquals(first.getString("file_id"), second.getString("file_id")); assertEquals(2, second.getInt("version"))
        assertEquals(first.getString("file_id"), second.getString("previous_file_id"))
        val third = JSONObject(create.execute(args("changed")))
        assertEquals(3, third.getInt("version")); assertEquals(second.getString("file_id"), third.getString("previous_file_id"))
        val files = AttachmentStore(app)
        assertEquals("original", files.file(first.getString("file_id")).readText())
        assertEquals("original", files.file(second.getString("file_id")).readText())
        ChatStore(app).use { db ->
            val attachments = db.load().single().messages.flatMap { it.attachments }
            assertEquals(3, attachments.size); assertEquals(listOf(1, 2, 3), attachments.map { it.version })
            assertEquals(second.getString("file_id"), attachments.last().previousId)
        }
    }
    @Test fun attachmentMetadataRoundTripsWithoutChangingLegacySourceHashRepresentation() {
        val legacy = ChatAttachment("old", "Old.txt", "text/plain", 4, "text")
        assertFalse(legacy.json().has("version")); assertFalse(legacy.json().has("previous_file_id"))
        assertEquals(legacy, ChatAttachment.decode(ChatAttachment.encode(listOf(legacy))).single())
        val version = legacy.copy(id = "new", version = 2, previousId = "old")
        assertEquals(version, ChatAttachment.decode(ChatAttachment.encode(listOf(version))).single())
    }
    @Test fun livePlanAndConfirmedResultsAreAvailableBeforeCompactAndFitSmallWorkingWindow() = runTest {
        ChatStore(app).use { db ->
            db.create(1, "State"); db.append(1, ChatMessage("Complete the build", true), null)
            val plan = ToolDataStore(app).use { JSONArray(it.plan(1, JSONArray("""[{"text":"Actual build step","status":"in_progress"}]"""))) }
            val memory = ContextStore(db)
            val target = ModelTarget(ProviderConnection("p", "test", "https://example.com/v1"), RegisteredModel("m", "p", "model"), "key")
            var round = 0
            val result = ContextExecution(1, memory, AttachmentStore(app), ContextPolicy(automatic = true), ModelCapabilities(setOf("max_tokens", "tools"), contextLength = 4096, maxOutputTokens = 512), target, status = {},
                request = { _, options, purpose, _, _ ->
                    assertEquals("answer", purpose); assertTrue(options.systemPrefix.contains("Actual build step")); assertTrue(options.systemPrefix.contains(plan.getJSONObject(0).getString("id")))
                    if (round++ == 0) GenerationResult("", toolCalls = (1..20).map { ModelToolCall("call$it", "write", "{\"number\":$it}") })
                    else GenerationResult("done")
                }).run(db.load().single().messages, false, null, listOf(BoundTool(ToolSchema("write", "test", JSONObject())) { "saved " + it.getInt("number") }))
            assertEquals("done", result!!.text)
            val state = memory.factualSnapshot(1)
            assertEquals(plan.toString(), state.plan.toString()); assertEquals("SUCCEEDED", state.task!!.getJSONArray("steps").getJSONObject(0).getString("status"))
            assertTrue(state.capsules.isEmpty())
        }
    }
    @Test fun versionNineUpgradeKeepsTaskAndPlanFacts() {
        var taskId = ""
        ChatStore(app).use { db ->
            db.create(1, "Migration"); db.append(1, ChatMessage("Keep this task", true), null)
            taskId = DurableTaskStore(db).begin(1, db.load().single().messages).taskId
            db.writableDatabase.version = 9
        }
        val plan = ToolDataStore(app).use { it.plan(1, JSONArray("""[{"text":"Keep plan","status":"completed"}]""")) }
        ChatStore(app).use { db ->
            assertEquals(taskId, DurableTaskStore(db).snapshot(1)!!.getString("task_id"))
            assertEquals(plan, ContextStore(db).factualSnapshot(1).plan.toString())
            assertEquals("Keep this task", db.load().single().messages.single().text)
        }
    }
}
