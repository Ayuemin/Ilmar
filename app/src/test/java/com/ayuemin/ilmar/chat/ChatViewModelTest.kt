package com.ayuemin.ilmar.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatViewModelTest {
    private lateinit var app: Application
    private lateinit var registry: ProviderRegistry
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase("ilmar_chats.db")
        listOf("ilmar_ui", "ilmar_models", "ilmar_chat_modes", "ilmar_requests", "ilmar_diagnostics").forEach {
            app.getSharedPreferences(it, 0).edit().clear().commit()
        }
        registry = ProviderRegistry(app, MemorySecrets())
        registry.saveConnection(null, "Test", "https://example.com/v1/chat/completions", listOf("m"), "key")
    }
    @After fun teardown() { Dispatchers.resetMain() }
    private class FakeProvider : ChatProvider {
        var calls = 0
        var fail = false
        var gate = CompletableDeferred<String>()
        override suspend fun stream(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, onDelta: (String) -> Unit): String {
            calls++; onDelta("partial")
            if (fail) throw ProviderFailure("Ошибка API: HTTP 429")
            return gate.await()
        }
    }
    @Test fun replyRemainsInOriginalChatWhenSelectionChanges() = runTest(dispatcher) {
        val provider = FakeProvider()
        val vm = ChatViewModel(app, provider, registry, dispatcher)
        val original = vm.state.value.selectedId
        vm.send("hello"); runCurrent(); vm.create()
        provider.gate.complete("answer"); advanceUntilIdle()
        assertEquals(listOf("hello", "answer"), vm.state.value.chats.first { it.id == original }.messages.map { it.text })
        assertTrue(vm.state.value.chats.first { it.id == vm.state.value.selectedId }.messages.isEmpty())
    }
    @Test fun stopDiscardsPartialAndRetryDoesNotDuplicateUserMessage() = runTest(dispatcher) {
        val provider = FakeProvider()
        val vm = ChatViewModel(app, provider, registry, dispatcher)
        vm.send("hello"); runCurrent(); vm.stop(); advanceUntilIdle()
        assertEquals(1, vm.state.value.chats.single().messages.size)
        assertEquals("", vm.state.value.streamingText)
        vm.retry(); runCurrent(); provider.gate.complete("answer"); advanceUntilIdle()
        assertEquals(2, provider.calls)
        assertEquals(listOf("hello", "answer"), vm.state.value.chats.single().messages.map { it.text })
    }
    @Test fun failureIsNotRetriedAutomaticallyAndUserMessageSurvives() = runTest(dispatcher) {
        val provider = FakeProvider().apply { fail = true }
        val vm = ChatViewModel(app, provider, registry, dispatcher)
        vm.send("hello"); advanceUntilIdle()
        assertEquals(1, provider.calls)
        assertEquals(1, vm.state.value.chats.single().messages.size)
        assertTrue(vm.state.value.errors.values.single().contains("429"))
    }
    @Test fun restartMarksInterruptedRequestWithoutCallingProvider() {
        val store = ChatStore(app)
        store.create(42, "Existing"); store.append(42, ChatMessage("hello", true), null); store.close()
        app.getSharedPreferences("ilmar_requests", 0).edit().putLong("active_chat", 42).commit()
        val provider = FakeProvider()
        val vm = ChatViewModel(app, provider, registry, dispatcher)
        assertEquals(0, provider.calls)
        assertTrue(vm.state.value.errors.containsKey(42))
        assertEquals("hello", vm.state.value.chats.single().messages.single().text)
    }
    @Test fun clearingActiveChatCannotResurrectDeletedMessages() = runTest(dispatcher) {
        val provider = FakeProvider()
        val vm = ChatViewModel(app, provider, registry, dispatcher)
        vm.send("hello"); runCurrent(); vm.clear(vm.state.value.selectedId)
        provider.gate.complete("late answer"); advanceUntilIdle()
        assertTrue(vm.state.value.chats.single().messages.isEmpty())
    }
    @Test fun cancellationBeforeCoroutineStartsDoesNotLeaveAnUnretryableRunningTask() = runTest(dispatcher) {
        val provider = FakeProvider()
        val vm = ChatViewModel(app, provider, registry, dispatcher)
        vm.send("hello"); vm.stop(); advanceUntilIdle()
        assertNull(vm.state.value.activeChatId)
        assertEquals(0, provider.calls)
        vm.retry(); runCurrent(); provider.gate.complete("answer"); advanceUntilIdle()
        assertEquals(1, provider.calls)
        assertEquals(listOf("hello", "answer"), vm.state.value.chats.single().messages.map { it.text })
    }
    @Test fun diagnosticsLinkRealModelRequestsToTaskRetryAndResolvedWindowAndUsage() = runTest(dispatcher) {
        com.ayuemin.ilmar.memory.ContextPreferences(app).save(com.ayuemin.ilmar.memory.ContextPolicy(window = 8192, output = 512, recent = 2, taskTokens = 4096))
        com.ayuemin.ilmar.diagnostics.Diagnostics(app).clear()
        val provider = object : ChatProvider {
            override suspend fun stream(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, onDelta: (String) -> Unit) = error("typed")
            override suspend fun generate(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, options: GenerationOptions, onDelta: (String) -> Unit): GenerationResult {
                assertNotNull(options.requestId)
                return GenerationResult("done", TokenUsage(10, 3, 13, 4, 2, "0.02"), "stop")
            }
        }
        val vm = ChatViewModel(app, provider, registry, dispatcher); vm.send("hello"); advanceUntilIdle()
        val out = java.io.ByteArrayOutputStream(); com.ayuemin.ilmar.diagnostics.Diagnostics(app).export(out)
        val rows = out.toString("UTF-8").lineSequence().filter { it.isNotBlank() }.map { org.json.JSONObject(it) }.toList()
        val request = rows.single { it.optString("event") == "MODEL_REQUEST" }.getJSONObject("details")
        assertEquals(8192, request.getInt("working_window_tokens")); assertEquals(512, request.getInt("reserved_output_tokens"))
        assertEquals(8192, request.getInt("compact_window_tokens")); assertEquals(4096, request.getJSONObject("context_policy").getInt("task_token_budget"))
        assertTrue(request.getString("task_id").isNotBlank()); assertTrue(request.getString("run_id").isNotBlank())
        val response = rows.single { it.optString("event") == "MODEL_RESPONSE" }.getJSONObject("details")
        assertEquals(4, response.getInt("cached_tokens")); assertEquals(2, response.getInt("reasoning_tokens")); assertEquals("0.02", response.getJSONObject("usage").getString("cost_usd"))
        val reservation = rows.single { it.optString("event") == "TASK_MODEL_RESERVATION" }
        assertEquals(request.getString("request_id"), reservation.getString("request_id")); assertEquals(request.getString("run_id"), reservation.getString("run_id"))
    }
}
