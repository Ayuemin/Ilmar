package com.ayuemin.ilmar.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.agent.*
import com.ayuemin.ilmar.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AgentIntegrationTest {
    private lateinit var app: Application
    private lateinit var registry: ProviderRegistry
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase("ilmar_chats.db")
        listOf("ilmar_ui", "ilmar_models", "ilmar_chat_modes", "ilmar_requests", "ilmar_tool_permissions").forEach {
            app.getSharedPreferences(it, 0).edit().clear().commit()
        }
        com.ayuemin.ilmar.settings.RuntimeLimits.install(app); com.ayuemin.ilmar.settings.RuntimeLimits.reset()
        registry = ProviderRegistry(app, MemorySecrets())
        registry.saveProvider(null, "Test", "https://example.com/v1", "key")
        val provider = registry.snapshot().providers.single()
        registry.applyCatalogDetails(provider.id, provider.endpoint, listOf(CatalogModel("m", ModelCapabilities(setOf("tools"), known = true))))
    }
    @After fun cleanup() { ToolRegistry.unregister("TestEcho"); Dispatchers.resetMain() }
    @Test fun dispatcherRechecksPermissionAfterToolWasSelected() = runTest(dispatcher) {
        var executions = 0
        ToolRegistry.register("TestEcho", "Echo", ToolSchema("echo", "test", JSONObject())) { executions++; "unchanged" }
        val modes = ChatModePreferences(app)
        modes.setEnabled(1, "tool_TestEcho", true)
        val broker = ToolPermissionBroker(app)
        assertTrue(ToolRegistry.authorized(1, modes, broker).isEmpty())
        broker.setGranted("TestEcho", true)
        val bound = ToolRegistry.authorized(1, modes, broker).single()
        broker.setGranted("TestEcho", false)
        assertTrue(runCatching { bound.execute(JSONObject()) }.isFailure)
        assertEquals(0, executions)
    }
    @Test fun chatPipelineStopsRepeatingToolsAndKeepsEveryCallUsage() = runTest(dispatcher) {
        var calls = 0; var executions = 0
        ToolRegistry.register("TestEcho", "Echo", ToolSchema("echo", "test", JSONObject())) { executions++; "unchanged" }
        val modes = ChatModePreferences(app)
        modes.setEnabled(1, "tool_TestEcho", true); modes.setEnabled(1, "Агент", true)
        ToolPermissionBroker(app).setGranted("TestEcho", true)
        val provider = object : ChatProvider {
            override suspend fun stream(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, onDelta: (String) -> Unit): String = error("Use typed generation")
            override suspend fun generate(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, options: GenerationOptions, onDelta: (String) -> Unit): GenerationResult {
                calls++
                assertEquals("echo", options.tools.single().name)
                return GenerationResult("", TokenUsage(10, 1, 11, costUsd = "0.1"), "tool_calls", listOf(ModelToolCall("call_$calls", "echo", "{}")))
            }
        }
        val vm = ChatViewModel(app, provider, registry, dispatcher)
        vm.send("test"); advanceUntilIdle()
        assertEquals(6, calls); assertEquals(1, executions)
        assertEquals(1, vm.state.value.chats.single().messages.size)
        assertTrue(vm.state.value.errors.values.single().contains("Агент остановлен"))
        assertEquals("0.6", vm.usage(1).costUsd)
        assertEquals(6, vm.usage(1).calls)
        assertNull(vm.state.value.activeChatId)
    }
    @Test fun intermediateFileIsVisibleAndFailedTaskCanBeRetriedWithoutCreatingAnotherChatOrFile() = runTest(dispatcher) {
        // Real filesystem IO must not race runTest's instant advancement of virtual tool deadlines.
        val limits = com.ayuemin.ilmar.settings.RuntimeLimits
        limits.save(limits.fields.associate { it.key to if (it.key == "tool_seconds") 0 else limits.get(it.key) })
        val modes = ChatModePreferences(app)
        modes.setEnabled(1, "tool_create_file", true); modes.setEnabled(1, "Агент", true)
        ToolPermissionBroker(app).setGranted("create_file", true)
        var calls = 0; var retry = false
        val provider = object : ChatProvider {
            override suspend fun stream(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, onDelta: (String) -> Unit) = error("typed")
            override suspend fun generate(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, options: GenerationOptions, onDelta: (String) -> Unit): GenerationResult {
                if (retry) return GenerationResult("Completed")
                if (calls++ == 0) return GenerationResult("", toolCalls = listOf(ModelToolCall("file", "create_file", """{"name":"Demo.md","content":"file data"}""")))
                throw java.io.IOException("offline after file")
            }
        }
        val vm = ChatViewModel(app, provider, registry, dispatcher)
        vm.send("Create file and explain it"); advanceUntilIdle()
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(10000) { vm.state.first { it.activeChatId == null } }
        }
        advanceUntilIdle()
        assertTrue(vm.state.value.errors.isNotEmpty())
        assertTrue(vm.state.value.errors.toString() + "\n" + com.ayuemin.ilmar.diagnostics.Diagnostics(app).read(), vm.state.value.chats.single().messages.last().attachments.isNotEmpty())
        assertEquals("Demo.md", vm.state.value.chats.single().messages.last().attachments.single().name)
        retry = true; vm.retry(); advanceUntilIdle()
        assertEquals("Completed", vm.state.value.chats.single().messages.last().text)
        assertEquals(1, vm.state.value.chats.size)
        assertEquals(1, vm.state.value.chats.single().messages.sumOf { it.attachments.size })
    }

}
