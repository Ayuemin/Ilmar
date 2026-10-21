package com.ayuemin.ilmar.browser

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NativePromptTest {
    @Test fun concurrentRequestsAreSerializedAndStaleCallbacksCannotApproveNext() = runTest {
        val first = async { BrowserPrompts.ask(1, "GitHub", "exact body") }
        runCurrent()
        val request = BrowserPrompts.pending.value!!
        val second = async { BrowserPrompts.ask(2, "Alpine", "exact command") }
        runCurrent()
        assertEquals(request.id, BrowserPrompts.pending.value!!.id)
        BrowserPrompts.respond(request.id, true); runCurrent()
        assertTrue(first.await())
        val next = BrowserPrompts.pending.value!!
        assertEquals(2L, next.chat)
        BrowserPrompts.respond(request.id, true); runCurrent()
        assertFalse(second.isCompleted)
        BrowserPrompts.respond(next.id, false); assertFalse(second.await())
        assertNull(BrowserPrompts.pending.value)
    }
    @Test fun cancellingApprovalReleasesQueueWithoutImplicitConsent() = runTest {
        val first = async { BrowserPrompts.ask(1, "GitHub", "first") }
        runCurrent(); first.cancel(); runCurrent()
        assertNull(BrowserPrompts.pending.value)
        val next = async { BrowserPrompts.ask(1, "Alpine", "second") }
        runCurrent(); val pending = BrowserPrompts.pending.value!!
        BrowserPrompts.respond(pending.id, false); assertFalse(next.await())
    }
}
