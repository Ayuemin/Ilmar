package com.ayuemin.ilmar.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.attachments.ChatAttachment
import com.ayuemin.ilmar.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessageBranchTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var app: Application
    @Before fun setup() { Dispatchers.setMain(dispatcher); app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase("ilmar_chats.db")
        listOf("ilmar_models", "ilmar_ui", "ilmar_chat_modes", "ilmar_requests").forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
    }
    @After fun cleanup() { Dispatchers.resetMain() }
    @Test fun branchPreservesPrefixAttachmentsAndInfoButDoesNotDuplicateBilling() {
        val store = ChatStore(app)
        try {
            store.create(1, "Original")
            val item = ChatAttachment("id", "notes.txt", "text/plain", 1, "x")
            store.append(1, ChatMessage("question", true, attachments = listOf(item)), null)
            val info = ReplyInfo("model", "provider", 123, 456, TokenUsage(costUsd = "0.1"), 1)
            val answer = store.append(1, ChatMessage("answer", false, info = info), null)
            store.append(1, ChatMessage("later", true), null)
            store.recordUsage(1, "request", info.usage, "completed")
            store.branch(1, 2, answer.id, "Branch")
            val original = store.load().first { it.id == 1L }
            val branch = store.load().first { it.id == 2L }
            assertEquals(3, original.messages.size); assertEquals(2, branch.messages.size)
            assertEquals(item, branch.messages.first().attachments.single())
            assertEquals(info, branch.messages.last().info)
            assertNotEquals(answer.id, branch.messages.last().id)
            assertEquals(0, store.usage(2).calls); assertEquals(1, store.usage(1).calls)
        } finally { store.close() }
    }
    @Test fun v2UpgradeKeepsUsageAndDraftAttachmentsSurviveReopenUntilSent() {
        app.openOrCreateDatabase("ilmar_chats.db", 0, null).use { db ->
            db.execSQL("CREATE TABLE chats (id INTEGER PRIMARY KEY, title TEXT NOT NULL)")
            db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER NOT NULL, text TEXT NOT NULL, from_user INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE request_usage (id TEXT PRIMARY KEY, chat_id INTEGER NOT NULL, status TEXT NOT NULL, input_tokens INTEGER, output_tokens INTEGER, total_tokens INTEGER, cached_tokens INTEGER, reasoning_tokens INTEGER, cost_usd TEXT)")
            db.execSQL("INSERT INTO chats VALUES(1, 'Old')")
            db.execSQL("INSERT INTO messages(chat_id,text,from_user) VALUES(1, 'history', 1)")
            db.execSQL("INSERT INTO request_usage(id,chat_id,status,cost_usd) VALUES('r',1,'completed','0.2')")
            db.version = 2
        }
        val item = ChatAttachment("local", "file.txt", "text/plain", 1, "text")
        val first = ChatStore(app)
        try { assertEquals("history", first.load().single().messages.single().text)
            assertEquals("0.2", first.usage(1).costUsd)
            first.saveDraft(1, listOf(item))
        } finally { first.close() }
        val second = ChatStore(app)
        try { assertEquals(listOf(item), second.drafts()[1])
            second.append(1, ChatMessage("", true, attachments = second.drafts()[1]!!), null)
            assertTrue(second.drafts().isEmpty())
            assertEquals(item, second.load().single().messages.last().attachments.single())
        } finally { second.close() }
    }
    @Test fun askingOldQuestionUsesOnlyItsPrefixAndKeepsOriginalConversation() = runTest(dispatcher) {
        val registry = ProviderRegistry(app, MemorySecrets())
        registry.saveConnection(null, "Test", "https://example.com/v1", listOf("m"), "key")
        var captured = emptyList<String>()
        val provider = object : ChatProvider {
            override suspend fun stream(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, onDelta: (String) -> Unit): String {
                captured = history.map { it.text }; return "reply"
            }
        }
        val vm = ChatViewModel(app, provider, registry, dispatcher)
        vm.send("first"); advanceUntilIdle()
        vm.send("second"); advanceUntilIdle()
        val source = vm.state.value.chats.single()
        vm.branch(source.id, source.messages.first().id, true); advanceUntilIdle()
        assertEquals(listOf("first"), captured)
        assertEquals(4, vm.state.value.chats.first { it.id == source.id }.messages.size)
        assertEquals(listOf("first", "reply"), vm.state.value.chats.first { it.id == vm.state.value.selectedId }.messages.map { it.text })
        assertEquals("m", vm.state.value.chats.first { it.id == vm.state.value.selectedId }.messages.last().info!!.model)
    }
}
