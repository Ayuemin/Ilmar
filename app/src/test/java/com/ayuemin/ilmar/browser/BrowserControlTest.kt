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
class BrowserControlTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private class Page : BrowserPage {
        var secretPage = true; var actions = 0; var shots = 0; var stops = 0
        override suspend fun open(url: String) = read()
        override suspend fun read() = JSONObject().put("url", "https://example.com/").put("text", "Page").put("requires_user", secretPage)
        override suspend fun scroll(down: Boolean) = read()
        override suspend fun act(command: String, args: JSONObject): JSONObject { actions++; return read() }
        override suspend fun screenshot(): ByteArray { shots++; return byteArrayOf(1) }
        override fun stop() { stops++ }
    }
    @Test fun passwordOrCaptchaHandsOffAndBlocksAllModelActionsAndScreenshotsUntilUserReturns() = runTest(dispatcher) {
        val page = Page(); BrowserRuntime.attach(page)
        try {
            val opened = JSONObject(BrowserRuntime.command(1, "open", "https://example.com/"))
            assertTrue(opened.getBoolean("_awaiting_user")); assertTrue(BrowserRuntime.state.value.visible)
            assertTrue(BrowserRuntime.state.value.waitingUser)
            assertTrue(JSONObject(BrowserRuntime.command(1, "click", details = JSONObject())).getBoolean("_awaiting_user"))
            assertTrue(runCatching { BrowserRuntime.command(2, "open", "https://example.com/") }.isFailure)
            assertTrue(runCatching { BrowserRuntime.screenshot(1) }.isFailure)
            BrowserRuntime.stop(1); assertEquals(0, page.stops)
            assertEquals(0, page.actions); assertEquals(0, page.shots)
            assertFalse(BrowserRuntime.completeHandoff(2)); page.secretPage = false
            assertTrue(BrowserRuntime.completeHandoff(1))
            BrowserRuntime.command(1, "click", details = JSONObject())
            assertEquals(1, page.actions); assertFalse(BrowserRuntime.state.value.waitingUser)
            assertArrayEquals(byteArrayOf(1), BrowserRuntime.screenshot(1)); assertEquals(1, page.shots)
        } finally { BrowserRuntime.detach(page) }
    }
    @Test fun credentialPageCannotBeCapturedEvenBeforeHandoffWasRequested() = runTest(dispatcher) {
        val page = Page().apply { secretPage = false }; BrowserRuntime.attach(page)
        try {
            BrowserRuntime.command(1, "open", "https://example.com/")
            page.secretPage = true
            assertTrue(runCatching { BrowserRuntime.screenshot(1) }.isFailure)
            assertEquals(0, page.shots)
        } finally { BrowserRuntime.detach(page) }
    }
}
