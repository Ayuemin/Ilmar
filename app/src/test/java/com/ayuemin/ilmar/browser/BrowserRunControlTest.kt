package com.ayuemin.ilmar.browser

import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.agent.AgentFailure
import com.ayuemin.ilmar.agent.AgentLoopRunner
import com.ayuemin.ilmar.chat.ChatModePreferences
import com.ayuemin.ilmar.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BrowserRunControlTest {
    private fun modes(chat: Long) = ChatModePreferences(ApplicationProvider.getApplicationContext()).apply {
        setEnabled(chat, "Поиск", true); setEnabled(chat, "Агент", false); setChoice(chat, "search_source", "auto")
    }
    @Test fun autoBlocksAllCommandsBeforeConsentAndCancellationHasNoRetryOrUnknownIntent() = runTest {
        val modes = modes(600)
        val gate = CompletableDeferred<Boolean>(); var prompts = 0; var started = false
        val control = BrowserRunControl(600, true, ask = { _, _, _ -> prompts++; gate.await() })
        val tools = BrowserTools.forChat(600, modes, false, true, control = control)
        val runner = AgentLoopRunner(onCheckpoint = { _, messages ->
            started = started || messages.any { it.optJSONArray("_started_calls")?.length()?.let { n -> n > 0 } == true }
        })
        val task = async {
            runCatching { runner.run(tools) { _, _ -> GenerationResult("", toolCalls = listOf(ModelToolCall("first", "browser_open", "{\"url\":\"https://example.com/\"}"))) } }
        }
        runCurrent(); assertEquals(1, prompts); assertFalse(started)
        gate.complete(false)
        val error = task.await().exceptionOrNull() as AgentFailure
        assertEquals("BROWSER_CANCELLED", error.code); assertFalse(started)
        for (tool in tools) {
            val denied = runCatching { tool.beforeExecute(JSONObject()) }.exceptionOrNull()
            assertTrue(denied is AgentFailure)
        }
        assertEquals(1, prompts)
    }
    @Test fun switchingToExplicitModeWhilePromptingCannotApproveOldRun() = runTest {
        val modes = modes(601); val gate = CompletableDeferred<Boolean>()
        val tools = BrowserTools.forChat(601, modes, false, true, control = BrowserRunControl(601, true, ask = { _, _, _ -> gate.await() }))
        val pending = async { runCatching { tools.first().beforeExecute(JSONObject()) } }
        runCurrent()
        modes.setEnabled(601, "Агент", true); modes.setEnabled(601, "tool_Браузер", true)
        gate.complete(true)
        assertEquals("PERMISSION_REVOKED", (pending.await().exceptionOrNull() as AgentFailure).code)
    }
    @Test fun explicitBrowserDoesNotPromptAndBudgetDenialStopsNextAction() = runTest {
        var prompts = 0
        val control = BrowserRunControl(1, false, ask = { _, _, _ -> prompts++; false }, actionLimit = 2, roundLimit = 10, cents = 0)
        repeat(2) { control.beforeAction(); control.actionStarted("browser_read") }
        assertEquals(0, prompts)
        assertEquals("BROWSER_CANCELLED", (runCatching { control.beforeAction() }.exceptionOrNull() as AgentFailure).code)
        assertEquals(2, control.summary().getInt("browser_actions")); assertEquals(1, prompts)
    }
    @Test fun roundAndMoneyBudgetsPauseBeforeNextModelAndContinueOnlyOnApproval() = runTest {
        var approvals = 0
        val caps = ModelCapabilities(promptPrice = "0.001", completionPrice = "0.001", requestPrice = "0")
        val control = BrowserRunControl(2, false, ask = { _, _, _ -> approvals++; true }, roundLimit = 1, cents = 10)
        control.beforeModel(10, 10, caps, false)
        control.afterModel(TokenUsage(10, 10, costUsd = "0.10"), caps)
        control.beforeModel(10, 10, caps, false)
        assertEquals(2, approvals) // round limit and monetary limit both require approval
        assertEquals(2, control.summary().getInt("model_rounds"))
        assertFalse(control.summary().getBoolean("includes_tariff_estimate"))
    }
    @Test fun cancelledDialogClearsPendingAndStaleUiResponseCannotApproveNewOne() = runTest {
        val job = launch { BrowserPrompts.ask(1, "Auto", "Confirm") }
        runCurrent(); val old = BrowserPrompts.pending.value!!.id
        job.cancelAndJoin(); assertNull(BrowserPrompts.pending.value)
        val next = async { BrowserPrompts.ask(2, "Budget", "Continue") }
        runCurrent(); BrowserPrompts.respond(old, true); runCurrent(); assertFalse(next.isCompleted)
        BrowserPrompts.respond(BrowserPrompts.pending.value!!.id, false)
        assertFalse(next.await()); assertNull(BrowserPrompts.pending.value)
    }
}
