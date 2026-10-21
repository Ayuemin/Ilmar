package com.ayuemin.ilmar.browser

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BrowserRuntimeTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private class Page : BrowserPage {
        var stopped = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun open(url: String): JSONObject { gate?.await(); return read() }
        override suspend fun read() = JSONObject().put("url", "https://example.com/").put("title", "Example").put("page_id", "page").put("text", "facts")
        override suspend fun scroll(down: Boolean) = read()
        override fun stop() { stopped++ }
    }
    @Test fun cannotReadAnotherChatsPageAndDetachClearsSession() = runTest(dispatcher) {
        val page = Page(); BrowserRuntime.attach(page)
        BrowserRuntime.command(5, "open", "https://example.com/")
        assertEquals("facts", JSONObject(BrowserRuntime.command(5, "read")).getString("text"))
        assertTrue(runCatching { BrowserRuntime.command(6, "read") }.isFailure)
        BrowserRuntime.show(6); assertFalse(BrowserRuntime.state.value.visible)
        BrowserRuntime.show(5); assertTrue(BrowserRuntime.state.value.visible)
        BrowserRuntime.detach(page)
        assertNull(BrowserRuntime.state.value.chat)
        assertTrue(runCatching { BrowserRuntime.command(5, "read") }.isFailure)
        assertEquals(1, page.stopped)
    }
    @Test fun unchangedScrollUsesDeltaButExplicitReadReturnsFullText() = runTest(dispatcher) {
        val page = Page()
        BrowserRuntime.attach(page)
        try {
            BrowserRuntime.command(42, "open", "https://example.com/")
            val scroll = JSONObject(BrowserRuntime.command(42, "scroll", "down"))
            assertEquals("", scroll.getString("text"))
            assertTrue(scroll.getBoolean("text_unchanged"))
            val read = JSONObject(BrowserRuntime.command(42, "read"))
            assertEquals("facts", read.getString("text"))
        } finally { BrowserRuntime.detach(page) }
    }
    @Test fun cancellationStopsPendingNavigationAndNextCommandWorks() = runTest(dispatcher) {
        val page = Page().apply { gate = CompletableDeferred() }; BrowserRuntime.attach(page)
        try {
            val job = launch { BrowserRuntime.command(10, "open", "https://example.com/") }
            runCurrent(); job.cancelAndJoin()
            assertTrue(page.stopped > 0); assertEquals("Остановлено", BrowserRuntime.state.value.status)
            page.gate = null
            assertTrue(BrowserRuntime.command(11, "open", "https://example.com/").contains("facts"))
            assertTrue(runCatching { BrowserRuntime.command(10, "read") }.isFailure)
        } finally { BrowserRuntime.detach(page) }
    }
    @Test fun independentSearchGrantCanBeRevokedBeforeToolExecution() = runTest(dispatcher) {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val modes = com.ayuemin.ilmar.chat.ChatModePreferences(app)
        modes.setChoice(100, "search_source", "browser"); modes.setEnabled(100, "Поиск", true)
        val tools = BrowserTools.forChat(100, modes, false, true)
        modes.setEnabled(100, "Поиск", false)
        val error = runCatching { tools.first().execute(JSONObject().put("query", "test")) }.exceptionOrNull()
        assertTrue(error is com.ayuemin.ilmar.agent.AgentFailure)
        assertEquals("PERMISSION_REVOKED", (error as com.ayuemin.ilmar.agent.AgentFailure).code)
    }
}
