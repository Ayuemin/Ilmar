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
    @Test fun askingOldQuestionUsesOnlyItsPrefixAndKeepsOriginalConversation() = runTest(dispatcher) {
        val registry = ProviderRegistry(app, MemorySecrets())
        registry.saveConnection(null, "Test", "https://example.com/v1", listOf("m"), "key")
        var captured = emptyList<String>()
        val provider = object : ChatProvider {
            override suspend fun stream(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, onDelta: (String) -> Unit): String {
                captured = history.map { it.text }; return "reply"
            }
        }
        val vm = ChatViewModel(app, provider, registry)
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
