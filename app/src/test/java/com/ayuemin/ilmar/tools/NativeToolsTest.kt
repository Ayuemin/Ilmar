package com.ayuemin.ilmar.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.agent.ToolPermissionBroker
import com.ayuemin.ilmar.chat.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeToolsTest {
    @Test fun arithmeticHasPrecedenceAndRejectsCodeAndUnboundedInputs() {
        assertEquals(14.0, Calculator.calculate("2 + 3 * 4"), 0.0)
        assertEquals(512.0, Calculator.calculate("2^3^2"), 0.0)
        assertEquals(-4.0, Calculator.calculate("-2^2"), 0.0)
        assertEquals(0.025, Calculator.calculate("2.5e-2"), 0.0)
        listOf("1/0", "1%0", "2^10001", "9^9999", "NaN", "Runtime.exec('x')", "1;2", "(".repeat(40) + "1" + ")".repeat(40), "-".repeat(40) + "1").forEach {
            assertTrue(it, runCatching { Calculator.calculate(it) }.isFailure)
        }
    }
    @Test fun publicAddressesExcludeLocalMappedAndTransitionRanges() {
        listOf("127.0.0.1", "0.0.0.0", "10.1.2.3", "172.16.0.1", "192.168.0.1", "169.254.169.254", "100.100.100.200", "198.18.0.1", "224.0.0.1", "::1", "fc00::1", "fe80::1", "::ffff:127.0.0.1", "64:ff9b::a00:1", "2002:7f00:1::", "2001:db8::1").forEach {
            assertFalse(it, PublicAddressPolicy.allowed(InetAddress.getByName(it)))
        }
        listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111").forEach { assertTrue(it, PublicAddressPolicy.allowed(InetAddress.getByName(it))) }
    }
    @Test fun generatedFileNamesAreBoundedAndCannotEscapeStorage() {
        assertEquals("text/html", NativeTools.generatedMime("Отчёт.html"))
        listOf("../file.txt", "a/b.txt", "a\\b.txt", "bad\n.txt", "test.exe", "a".repeat(121) + ".txt").forEach {
            assertTrue(it, runCatching { NativeTools.generatedMime(it) }.isFailure)
        }
    }
    @Test fun memoryIsIdempotentScopedAndPlanIsValidated() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ToolDataStore(context).use { data ->
            data.clear(9001); data.clear(9002)
            val id = data.saveMemory(9001, "Версия проекта 42")
            assertEquals(id, data.saveMemory(9001, "Версия проекта 42"))
            assertTrue(data.recall(9001, "Версия").contains("42"))
            assertEquals("[]", data.recall(9002, "Версия"))
            assertTrue(runCatching { data.plan(9001, org.json.JSONArray("[{\"text\":\"x\",\"status\":\"unknown\"}]")) }.isFailure)
            data.clear(9001); assertEquals("[]", data.recall(9001, "Версия"))
        }
    }
    @Test fun fileToolRequiresGrantPersistsAttachmentAndRejectsOtherChatRead() = runTest {
        val app = ApplicationProvider.getApplicationContext<Context>()
        NativeTools.install(app)
        val modes = ChatModePreferences(app); val broker = ToolPermissionBroker(app)
        modes.setEnabled(9003, "tool_create_file", true)
        broker.setGranted("create_file", false)
        assertFalse(ToolRegistry.authorized(9003, modes, broker).any { it.schema.name == "create_file" })
        broker.setGranted("create_file", true)
        ChatStore(app).use { db ->
            db.load().filter { it.id in setOf(9003L, 9004L) }.forEach { db.delete(it.id) }
            db.create(9003, "Files"); db.create(9004, "Other")
            val create = ToolRegistry.authorized(9003, modes, broker).first { it.schema.name == "create_file" }
            val args = JSONObject().put("name", "Отчёт.html").put("content", "<h1>Отчёт</h1>")
            val output = create.execute(args)
            assertTrue(output, JSONObject(output).has("file_id"))
            val id = JSONObject(output).getString("file_id")
            assertEquals(id, JSONObject(create.execute(args)).getString("file_id"))
            assertEquals(1, db.load().first { it.id == 9003L }.messages.size)
            assertEquals(id, db.load().first { it.id == 9003L }.messages.single().attachments.single().id)
            modes.setEnabled(9004, "tool_read_file", true); broker.setGranted("read_file", true)
            val read = ToolRegistry.authorized(9004, modes, broker).first { it.schema.name == "read_file" }
            assertTrue(JSONObject(read.execute(JSONObject().put("file_id", id))).has("error"))
            broker.setGranted("create_file", false)
            assertTrue(runCatching { create.execute(args) }.isFailure)
        }
    }
    @Test fun clarificationStopsExecutionWithQuestionInsteadOfInventingAnswer() = runTest {
        val app = ApplicationProvider.getApplicationContext<Context>(); NativeTools.install(app)
        val modes = ChatModePreferences(app); val broker = ToolPermissionBroker(app)
        modes.setEnabled(9005, "tool_ask_user", true); broker.setGranted("ask_user", true)
        val tool = ToolRegistry.authorized(9005, modes, broker).first { it.schema.name == "ask_user" }
        val error = runCatching { tool.execute(JSONObject().put("question", "Какая дата?")) }.exceptionOrNull()
        assertTrue(error is ClarificationRequired); assertEquals("Какая дата?", error?.message)
    }
    @Test fun successfulNetworkResultsAreCachedAndFailuresAreNot() = runTest {
        val app = ApplicationProvider.getApplicationContext<Context>()
        ToolDataStore(app).use { data ->
            val key = java.util.UUID.randomUUID().toString()
            var paid = 0
            assertEquals("result", data.cached(key) { paid++; "result" })
            assertEquals("result", data.cached(key) { paid++; "unexpected" })
            assertEquals(1, paid)
            val failed = java.util.UUID.randomUUID().toString()
            assertTrue(runCatching { data.cached(failed) { error("offline") } }.isFailure)
            assertEquals("retry", data.cached(failed) { "retry" })
        }
    }

    @Test fun largerGeneratedFilesAndMessageAndTraceReadsSupportPagingWithoutCrossChatAccess() = runTest {
        val app = ApplicationProvider.getApplicationContext<Context>(); NativeTools.install(app)
        val modes = ChatModePreferences(app); val broker = ToolPermissionBroker(app)
        for (name in listOf("create_file", "read_file")) { broker.setGranted(name, true); modes.setEnabled(9010, "tool_$name", true) }
        ChatStore(app).use { store ->
            store.load().filter { it.id == 9010L }.forEach { store.delete(it.id) }; store.create(9010, "Paging")
            val original = store.append(9010, ChatMessage("Full original message", true), null)
            val create = ToolRegistry.authorized(9010, modes, broker).first { it.schema.name == "create_file" }
            val read = ToolRegistry.authorized(9010, modes, broker).first { it.schema.name == "read_file" }
            val content = "Русский текст ".repeat(8000)
            val response = JSONObject(create.execute(JSONObject().put("name", "Большой.md").put("content", content)))
            val id = response.getString("file_id")
            val chunk = JSONObject(read.execute(JSONObject().put("file_id", id).put("offset", 100).put("limit", 1000)))
            assertEquals(content.substring(100, 1100), chunk.getString("text")); assertEquals(1100, chunk.getInt("next_offset"))
            assertEquals(content, store.load().first { it.id == 9010L }.messages.last().attachments.single().text)
            assertEquals("Full original message", JSONObject(read.execute(JSONObject().put("file_id", "message:${original.id}"))).getString("text"))
            store.saveToolTrace(9010, "trace-42", listOf(JSONObject().put("role", "tool").put("content", "original result")))
            assertTrue(JSONObject(read.execute(JSONObject().put("file_id", "trace:trace-42"))).getString("text").contains("original result"))
            assertTrue(JSONObject(read.execute(JSONObject().put("file_id", "trace:other-chat"))).has("error"))
        }
    }

    @Test fun historySearchUsesFullOriginalsAndCannotReadAnotherChat() = kotlinx.coroutines.test.runTest {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        NativeTools.install(app)
        val modes = com.ayuemin.ilmar.chat.ChatModePreferences(app)
        val broker = com.ayuemin.ilmar.agent.ToolPermissionBroker(app)
        broker.setGranted("search_history", true); modes.setEnabled(9011, "tool_search_history", true)
        com.ayuemin.ilmar.chat.ChatStore(app).use { store ->
            store.load().filter { it.id in listOf(9011L, 9012L) }.forEach { store.delete(it.id) }
            store.create(9011, "Current"); store.create(9012, "Private")
            store.append(9011, com.ayuemin.ilmar.chat.ChatMessage("deployment-version-42", true), null)
            store.append(9012, com.ayuemin.ilmar.chat.ChatMessage("private deployment-version-99", true), null)
            val tool = com.ayuemin.ilmar.chat.ToolRegistry.authorized(9011, modes, broker).first { it.schema.name == "search_history" }
            val result = tool.execute(org.json.JSONObject().put("query", "deployment").put("mode", "lexical"))
            assertTrue(result.contains("version-42")); assertFalse(result.contains("version-99"))
            store.delete(9011); store.delete(9012)
        }
    }

}
