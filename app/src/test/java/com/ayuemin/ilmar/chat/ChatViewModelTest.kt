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
        assertEquals("local_heuristic_not_provider_usage", request.getJSONObject("input_breakdown_estimate").getString("method"))
        val response = rows.single { it.optString("event") == "MODEL_RESPONSE" }.getJSONObject("details")
        assertEquals(4, response.getInt("cached_tokens")); assertEquals(2, response.getInt("reasoning_tokens")); assertEquals("0.02", response.getJSONObject("usage").getString("cost_usd"))
        val reservation = rows.single { it.optString("event") == "TASK_MODEL_RESERVATION" }
        assertEquals(request.getString("request_id"), reservation.getString("request_id")); assertEquals(request.getString("run_id"), reservation.getString("run_id"))
    }
    @Test fun genericProviderSearchRunsWithoutAgentAndDoesNotRetryPaidFailure() = runTest(dispatcher) {
        val connection = registry.snapshot().providers.single()
        com.ayuemin.ilmar.browser.ProviderSearchPreferences(app).save(connection.id, com.ayuemin.ilmar.browser.ProviderSearchProtocol.CHAT_COMPLETIONS)
        val modes = ChatModePreferences(app)
        val vm = ChatViewModel(app, object : ChatProvider {
            var calls = 0
            override suspend fun stream(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, onDelta: (String) -> Unit): String = error("typed")
            override suspend fun generate(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, options: GenerationOptions, onDelta: (String) -> Unit): GenerationResult {
                calls++; assertEquals(1, calls)
                assertEquals("CHAT_COMPLETIONS", options.webSearch)
                assertTrue(options.tools.isEmpty())
                throw ProviderFailure("Ошибка API: HTTP 429")
            }
        }, registry, dispatcher)
        val chat = vm.state.value.selectedId
        modes.setEnabled(chat, "Поиск", true); modes.setEnabled(chat, "Агент", false)
        modes.setChoice(chat, "search_source", "provider")
        vm.send("Find a current fact"); advanceUntilIdle()
        assertTrue(vm.state.value.errors[chat]!!.contains("429"))
        assertEquals(1, vm.state.value.chats.single().messages.size)
        assertFalse(modes.enabled(chat, "Агент"))
    }

    private suspend fun TestScope.browserSearchRoundFlags(source: String): List<String?> {
        val connection = registry.snapshot().providers.single()
        com.ayuemin.ilmar.browser.ProviderSearchPreferences(app).save(connection.id, com.ayuemin.ilmar.browser.ProviderSearchProtocol.CHAT_COMPLETIONS)
        registry.applyCatalogDetails(connection.id, connection.endpoint, listOf(CatalogModel("m", ModelCapabilities(setOf("tools"), contextLength = 32768, known = true))))
        val flags = mutableListOf<String?>()
        val vm = ChatViewModel(app, object : ChatProvider {
            override suspend fun stream(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, onDelta: (String) -> Unit): String = error("typed")
            override suspend fun generate(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, options: GenerationOptions, onDelta: (String) -> Unit): GenerationResult {
                flags += options.webSearch
                assertTrue(options.tools.any { it.name == "browser_batch" })
                return if (flags.size == 1) GenerationResult("", toolCalls = listOf(ModelToolCall("bad", "browser_batch", "{\"steps\":[]}"))) else GenerationResult("done")
            }
        }, registry, dispatcher)
        val chat = vm.state.value.selectedId
        ChatModePreferences(app).apply {
            setEnabled(chat, "Поиск", true); setEnabled(chat, "Агент", true); setEnabled(chat, "tool_Браузер", true); setChoice(chat, "search_source", source)
        }
        vm.send("Inspect browser test"); advanceUntilIdle()
        assertTrue(vm.state.value.errors.isEmpty()); assertEquals(2, flags.size)
        return flags
    }
    @Test fun autoUsesProviderSearchBeforeOfferingBrowserFallback() = runTest(dispatcher) {
        assertEquals(listOf("CHAT_COMPLETIONS", null), browserSearchRoundFlags("auto"))
    }
    @Test fun intentionalMixedBrowserSearchAddsProviderSearchOnlyWithinConfiguredQuota() = runTest(dispatcher) {
        assertEquals(listOf("CHAT_COMPLETIONS", null), browserSearchRoundFlags("provider"))
    }

    @Test fun browserBudgetCancelledBeforeSendingModelHasKnownZeroUsage() = runTest(dispatcher) {
        val limits = com.ayuemin.ilmar.settings.RuntimeLimits
        limits.install(app)
        limits.save(limits.fields.associate { it.key to if (it.key == "browser_budget_cents") 1 else it.default })
        try {
            val connection = registry.snapshot().providers.single()
            registry.applyCatalogDetails(connection.id, connection.endpoint, listOf(CatalogModel("m", ModelCapabilities(setOf("tools"), known = true))))
            val provider = FakeProvider()
            val vm = ChatViewModel(app, provider, registry, dispatcher)
            val chat = vm.state.value.selectedId
            ChatModePreferences(app).apply { setEnabled(chat, "Поиск", true); setChoice(chat, "search_source", "browser") }
            vm.send("Find a fact"); runCurrent()
            val pending = com.ayuemin.ilmar.browser.BrowserPrompts.pending.value
            assertNotNull(pending); assertEquals(0, provider.calls)
            com.ayuemin.ilmar.browser.BrowserPrompts.respond(pending!!.id, false)
            advanceUntilIdle()
            assertEquals(0, provider.calls); assertNull(vm.state.value.activeChatId)
            assertEquals("0", vm.usage(chat).costUsd); assertFalse(vm.usage(chat).incompleteCost)
            assertEquals(0L, vm.usage(chat).input)
            assertTrue(vm.usage(chat).describe().contains("Не отправлено провайдеру"))
            ChatStore(app).use { db ->
                val task = com.ayuemin.ilmar.memory.ContextStore(db).tasks.snapshot(chat)!!.getString("task_id")
                val spent = com.ayuemin.ilmar.memory.ContextStore(db).tasks.spent(task)
                assertFalse(spent.unknownCost); assertEquals(0, spent.cost.compareTo(java.math.BigDecimal.ZERO))
            }
        } finally { limits.reset() }
    }

    @Test fun localBrowserCompletesToolRoundInOrdinaryChat() = runTest(dispatcher) {
        val connection = registry.snapshot().providers.single()
        registry.applyCatalogDetails(connection.id, connection.endpoint, listOf(CatalogModel("m", ModelCapabilities(setOf("tools"), known = true))))
        var opens = 0
        val page = object : com.ayuemin.ilmar.browser.BrowserPage {
            override suspend fun open(url: String): org.json.JSONObject { opens++; assertTrue(url.startsWith("https://www.google.com/search?")); return read() }
            override suspend fun read() = org.json.JSONObject().put("url", "https://example.com/").put("title", "Source").put("text", "Verified test fact").put("untrusted", true)
            override suspend fun scroll(down: Boolean) = read()
            override fun stop() = Unit
        }
        com.ayuemin.ilmar.browser.BrowserRuntime.attach(page)
        var calls = 0
        val model = object : ChatProvider {
            override suspend fun stream(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, onDelta: (String) -> Unit): String = error("typed")
            override suspend fun generate(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, options: GenerationOptions, onDelta: (String) -> Unit): GenerationResult {
                calls++; assertNull(options.webSearch)
                assertEquals(setOf("browser_batch", "browser_find", "browser_wait", "browser_search", "browser_open", "browser_read", "browser_scroll", "browser_back", "browser_click", "browser_type", "browser_select", "browser_takeover"), options.tools.map { it.name }.toSet())
                return if (calls == 1) GenerationResult("", finishReason = "tool_calls", toolCalls = listOf(ModelToolCall("browse", "browser_search", "{\"query\":\"test fact\"}")))
                else { assertTrue(options.toolHistory.any { it.optString("content").contains("Verified test fact") }); GenerationResult("Source-backed answer", finishReason = "stop") }
            }
        }
        try {
            val vm = ChatViewModel(app, model, registry, dispatcher)
            val chat = vm.state.value.selectedId; val modes = ChatModePreferences(app)
            modes.setEnabled(chat, "Поиск", true); modes.setEnabled(chat, "Агент", false); modes.setChoice(chat, "search_source", "browser")
            vm.send("Find test fact"); advanceUntilIdle()
            assertTrue(vm.state.value.errors.toString(), vm.state.value.errors.isEmpty())
            assertEquals(2, calls); assertEquals(1, opens)
            assertEquals("Source-backed answer", vm.state.value.chats.single().messages.last().text)
            assertFalse(modes.enabled(chat, "Агент"))
        } finally { com.ayuemin.ilmar.browser.BrowserRuntime.detach(page) }
    }

    @Test fun browserHandoffPersistsTaskAndUserReturnContinuesWithoutRepeatingOpen() = runTest(dispatcher) {
        val connection = registry.snapshot().providers.single()
        registry.applyCatalogDetails(connection.id, connection.endpoint, listOf(CatalogModel("m", ModelCapabilities(setOf("tools"), known = true))))
        var login = true; var opens = 0; var calls = 0
        val page = object : com.ayuemin.ilmar.browser.BrowserPage {
            override suspend fun open(url: String): org.json.JSONObject { opens++; return read() }
            override suspend fun read() = org.json.JSONObject().put("url", "https://example.com/").put("text", "Page after user").put("requires_user", login)
            override suspend fun scroll(down: Boolean) = read()
            override fun stop() = Unit
        }
        com.ayuemin.ilmar.browser.BrowserRuntime.attach(page)
        val model = object : ChatProvider {
            override suspend fun stream(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, onDelta: (String) -> Unit): String = error("typed")
            override suspend fun generate(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, options: GenerationOptions, onDelta: (String) -> Unit): GenerationResult {
                calls++
                return when(calls) {
                    1 -> GenerationResult("", toolCalls = listOf(ModelToolCall("open", "browser_open", "{\"url\":\"https://example.com/\"}")))
                    2 -> GenerationResult("", toolCalls = listOf(ModelToolCall("read", "browser_read", "{}")))
                    else -> GenerationResult("Continued after manual login")
                }
            }
        }
        try {
            val vm = ChatViewModel(app, model, registry, dispatcher); val chat = vm.state.value.selectedId
            val modes = ChatModePreferences(app); modes.setEnabled(chat, "Поиск", true); modes.setChoice(chat, "search_source", "browser")
            vm.send("Open a page"); advanceUntilIdle()
            assertEquals(1, calls); assertTrue(com.ayuemin.ilmar.browser.BrowserRuntime.state.value.waitingUser)
            ChatStore(app).use { db -> db.readableDatabase.rawQuery("SELECT status FROM agent_tasks", null).use { assertTrue(it.moveToFirst()); assertEquals("AWAITING_USER", it.getString(0)) } }
            login = false; vm.resumeBrowserTask(chat); advanceUntilIdle()
            assertEquals(3, calls); assertEquals(1, opens); assertFalse(com.ayuemin.ilmar.browser.BrowserRuntime.state.value.waitingUser)
            assertTrue(vm.state.value.errors.toString(), vm.state.value.errors.isEmpty())
            assertEquals("Continued after manual login", vm.state.value.chats.single().messages.last().text)
            ChatStore(app).use { db ->
                db.readableDatabase.rawQuery("SELECT COUNT(*) FROM agent_tasks", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
                db.readableDatabase.rawQuery("SELECT COUNT(*) FROM agent_runs", null).use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
            }
        } finally { com.ayuemin.ilmar.browser.BrowserRuntime.detach(page) }
    }

}
