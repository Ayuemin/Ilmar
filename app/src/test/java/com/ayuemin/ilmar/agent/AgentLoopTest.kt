package com.ayuemin.ilmar.agent

import com.ayuemin.ilmar.model.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AgentLoopTest {
    @Test fun guardDetectsEquivalentJsonRepeatsButAllowsProgress() {
        val guard = AgentToolLoopGuard()
        val decisions = (1..6).map { guard.observe("echo", if (it % 2 == 0) "{\"b\":2,\"a\":1}" else "{\"a\":1,\"b\":2}", "{\"ok\":true}") }
        assertFalse(decisions.take(5).any { it }); assertTrue(decisions.last())
        val progress = AgentToolLoopGuard()
        assertFalse((1..30).any { progress.observe("poll", "{}", "{\"revision\":$it}") })
    }
    @Test fun repeatedCallsStopBeforeAnotherPaidRound() = runTest {
        var executed = 0; var rounds = 0
        val tool = BoundTool(ToolSchema("echo", "test", JSONObject())) { executed++; "unchanged" }
        val failure = runCatching { AgentLoopRunner().run(listOf(tool)) { _, _ ->
            rounds++; GenerationResult("", toolCalls = listOf(ModelToolCall("call_$rounds", "echo", "{}")))
        } }.exceptionOrNull()!!
        assertTrue(failure.message!!.contains("без прогресса"))
        assertEquals(6, rounds); assertEquals(6, executed)
    }
    @Test fun unknownToolCannotExecuteAndNoProgressHasHardRoundBudget() = runTest {
        var executed = false
        val tool = BoundTool(ToolSchema("known", "test", JSONObject())) { executed = true; "x" }
        val stopped = AgentLoopRunner().run(listOf(tool)) { _, _ ->
            GenerationResult("", toolCalls = listOf(ModelToolCall("1", "unknown", "{}")))
        }
        assertTrue(stopped.text.contains("исчерпан"))
        assertFalse(executed)
        var rounds = 0
        assertTrue(runCatching { AgentLoopRunner(maxRounds = 3).run(listOf(tool)) { _, _ ->
            rounds++; GenerationResult("", toolCalls = listOf(ModelToolCall("$rounds", "known", "{\"n\":$rounds}")))
        } }.isFailure)
        assertEquals(3, rounds)
    }
    @Test fun fullToolRoundsReachFinalAnswerAndToolsStayOutOfRegularChat() = runTest {
        var round = 0
        val tool = BoundTool(ToolSchema("echo", "test", JSONObject())) { "result" }
        val result = AgentLoopRunner().run(listOf(tool)) { history, schemas ->
            assertEquals("echo", schemas.single().name)
            if (round++ == 0) GenerationResult("", toolCalls = listOf(ModelToolCall("1", "echo", "{}")))
            else {
                assertEquals(listOf("assistant", "tool"), history.map { it.getString("role") })
                GenerationResult("answer")
            }
        }
        assertEquals("answer", result.text)
        assertTrue(runCatching { AgentLoopRunner().run(emptyList()) { _, _ ->
            GenerationResult("", toolCalls = listOf(ModelToolCall("1", "echo", "{}")))
        } }.isFailure)
    }
}
