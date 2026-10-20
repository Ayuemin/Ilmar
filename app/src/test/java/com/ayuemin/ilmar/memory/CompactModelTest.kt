package com.ayuemin.ilmar.memory

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.chat.*
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
class CompactModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var app: Application
    @Before fun setup() {
        Dispatchers.setMain(dispatcher); app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase("ilmar_chats.db")
        listOf("ilmar_models", "ilmar_ui", "ilmar_chat_modes", "ilmar_requests", "ilmar_context").forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
    }
    @After fun cleanup() { Dispatchers.resetMain() }
    @Test fun compactUsesSeparateEndpointModelKeyAndCapabilitiesWithoutChangingAnswerModel() = runTest(dispatcher) {
        val registry = ProviderRegistry(app, MemorySecrets())
        registry.saveConnection(null, "Answer", "https://answer.example/v1", listOf("answer-model"), "answer-key")
        registry.saveConnection(null, "Compact", "https://compact.example/v1", listOf("compact-model"), "compact-key")
        val models = registry.snapshot().models
        val answer = models.first { it.name == "answer-model" }; val compact = models.first { it.name == "compact-model" }
        val connection = registry.snapshot().providers.first { it.id == compact.providerId }
        registry.applyCatalogDetails(connection.id, connection.endpoint, listOf(CatalogModel(compact.name,
            ModelCapabilities(setOf("max_completion_tokens"), contextLength = 4096, maxOutputTokens = 512))))
        com.ayuemin.ilmar.browser.ProviderSearchPreferences(app).save(answer.providerId, com.ayuemin.ilmar.browser.ProviderSearchProtocol.CHAT_COMPLETIONS)
        val preferences = ContextPreferences(app)
        preferences.save(ContextPolicy(recent = 2, automatic = false)); preferences.compactModelId = compact.id
        val calls = mutableListOf<Triple<String, String, String>>()
        val provider = object : ChatProvider {
            override suspend fun stream(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, onDelta: (String) -> Unit) = "answer"
            override suspend fun generate(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, options: GenerationOptions, onDelta: (String) -> Unit): GenerationResult {
                calls += Triple(endpoint, model, apiKey)
                if (model == "compact-model") {
                    assertNull(options.webSearch)
                    assertEquals("max_completion_tokens", options.outputParameter); assertEquals(512, options.maxOutput)
                    return GenerationResult("""{"goal":["Continue"],"facts":[],"decisions":[],"progress":[],"files":[],"tests":[],"errors":[],"next":[],"uncertain":[]}""")
                }
                assertEquals("CHAT_COMPLETIONS", options.webSearch)
                return GenerationResult("answer")
            }
        }
        val vm = ChatViewModel(app, provider, registry, dispatcher)
        ChatModePreferences(app).setEnabled(vm.state.value.selectedId, "Поиск", true)
        repeat(3) { vm.send("Question $it " + "x".repeat(400)); advanceUntilIdle() }
        vm.compact(vm.state.value.selectedId); advanceUntilIdle()
        assertTrue(calls.any { it == Triple(connection.endpoint, compact.name, "compact-key") })
        assertTrue(vm.state.value.errors.toString(), vm.state.value.errors.isEmpty())
        assertNotNull(vm.memorySnapshot(vm.state.value.selectedId).capsules.firstOrNull())
        assertEquals(answer.id, registry.modelForChat(vm.state.value.selectedId)?.id)
        vm.send("Continue"); advanceUntilIdle()
        assertEquals("answer-model", calls.last().second)
        assertTrue(vm.usage(vm.state.value.selectedId).calls >= 5)
    }
}
