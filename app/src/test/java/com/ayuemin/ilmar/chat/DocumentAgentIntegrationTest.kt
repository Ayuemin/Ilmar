package com.ayuemin.ilmar.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.agent.ToolPermissionBroker
import com.ayuemin.ilmar.documents.*
import com.ayuemin.ilmar.model.*
import com.ayuemin.ilmar.settings.RuntimeLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DocumentAgentIntegrationTest {
    @Test fun experimentIsOptInAndRunCapabilityIsClosedAfterGeneration() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.deleteDatabase("ilmar_chats.db")
        listOf("ilmar_models", "ilmar_chat_modes", "ilmar_tool_permissions", "ilmar_requests", "ilmar_document_experiment").forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
        val prefs = DocumentExperimentPreferences(app)
        assertEquals(DocumentMode.LEGACY, prefs.load().mode)
        prefs.save(DocumentExperiment(DocumentMode.LOCAL))
        RuntimeLimits.install(app); RuntimeLimits.reset()
        RuntimeLimits.save(RuntimeLimits.fields.associate { it.key to if (it.key in setOf("tool_seconds", "task_seconds")) 0 else RuntimeLimits.get(it.key) })
        val registry = ProviderRegistry(app, MemorySecrets())
        registry.saveProvider(null, "Test", "https://example.com/v1", "key")
        val providerId = registry.snapshot().providers.single().id
        registry.applyCatalogDetails(providerId, registry.snapshot().providers.single().endpoint, listOf(CatalogModel("model", ModelCapabilities(setOf("tools"), known = true))))
        val modes = ChatModePreferences(app); modes.setEnabled(1, "Агент", true); modes.setEnabled(1, "tool_fetch_url", true)
        ToolPermissionBroker(app).setGranted("fetch_url", true)
        var calls = 0; var captured: DocumentSession? = null
        val provider = object : ChatProvider {
            override suspend fun stream(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, onDelta: (String) -> Unit) = error("typed generation")
            override suspend fun generate(endpoint: String, model: String, apiKey: String, history: List<ChatMessage>, options: GenerationOptions, onDelta: (String) -> Unit): GenerationResult {
                assertTrue(options.tools.any { it.name == "document_search" })
                assertTrue(options.tools.any { it.name == "document_outline" })
                assertTrue(options.tools.any { it.name == "document_read" })
                return if (calls++ == 0) GenerationResult("", toolCalls = listOf(ModelToolCall("fetch", "fetch_url", """{"url":"https://example.com"}"""))) else GenerationResult("Done")
            }
        }
        try {
            val vm = ChatViewModel(app, provider, registry, dispatcher)
            ToolRegistry.registerForChat("fetch_url", "Fetch fixture", ToolSchema("fetch_url", "fixture", JSONObject())) { _, _ ->
                captured = currentCoroutineContext()[DocumentSession]
                JSONObject().put("source_complete", true).toString()
            }
            vm.send("quota"); advanceUntilIdle()
            assertEquals(2, calls); assertNotNull(captured)
            assertTrue(runCatching { captured!!.search("quota", 3) }.isFailure)
            assertNull(vm.state.value.activeChatId)
        } finally {
            prefs.save(DocumentExperiment()); RuntimeLimits.reset(); ToolRegistry.unregister("fetch_url"); Dispatchers.resetMain()
        }
    }
}
