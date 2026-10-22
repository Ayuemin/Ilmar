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
class ToolFailureRegressionTest {
    @Test fun permanentFailureExecutesOnceAndFinalizesWithoutTools() = runTest {
        var executions = 0; var rounds = 0
        val tool = BoundTool(ToolSchema("document_read", "test", JSONObject())) {
            executions++; error("DOCUMENT_UNAVAILABLE: changing message $executions")
        }
        val result = AgentLoopRunner().run(listOf(tool)) { _, schemas ->
            rounds++
            if (schemas.isEmpty()) GenerationResult("Документ недоступен; полный ответ подтвердить нельзя.")
            else GenerationResult("", toolCalls = listOf(ModelToolCall("$rounds", "document_read", "{\"document_id\":\"lost\"}")))
        }
        assertEquals(1, executions); assertEquals(3, rounds)
        assertTrue(result.text.contains("недоступен"))
    }
    @Test fun transientErrorsHaveOnlyOneRetryAndDoNotDependOnErrorMessage() = runTest {
        var executions = 0; var rounds = 0
        val tool = BoundTool(ToolSchema("fetch_url", "test", JSONObject())) {
            executions++; throw java.io.IOException("changing $executions")
        }
        AgentLoopRunner().run(listOf(tool)) { _, schemas ->
            rounds++
            if (schemas.isEmpty()) GenerationResult("Network unavailable")
            else GenerationResult("", toolCalls = listOf(ModelToolCall("$rounds", "fetch_url", "{}")))
        }
        assertEquals(2, executions); assertEquals(4, rounds)
    }
    @Test fun canonicalArgumentsAndInterveningSuccessCannotEvadeFailureBudget() {
        val guard = ToolFailureGuard()
        guard.observe("read", "{\"a\":1,\"b\":2}", "{\"error\":\"INVALID_ARGUMENTS\"}")
        guard.observe("read", "{\"section\":\"s2\"}", "{\"text\":\"new evidence\"}")
        assertNotNull(guard.blocked("read", "{\"b\":2,\"a\":1}"))
        assertNull(guard.blocked("read", "{\"section\":\"s3\"}"))
    }
    @Test fun changingInvalidArgumentsCannotSpendDozensOfPaidRounds() = runTest {
        var calls = 0; var rounds = 0
        val tool = BoundTool(ToolSchema("document_read", "test", JSONObject())) {
            calls++; throw IllegalArgumentException("Invalid selector $calls")
        }
        AgentLoopRunner().run(listOf(tool)) { _, schemas ->
            rounds++
            if (schemas.isEmpty()) GenerationResult("Cannot confirm missing evidence; invalid tool arguments.")
            else GenerationResult("", toolCalls = listOf(ModelToolCall("$rounds", "document_read", "{\"section_id\":\"changed$rounds\"}")))
        }
        assertEquals(4, calls); assertEquals(5, rounds)
    }
    @Test fun validationReasonsSurviveWithoutExportingArgumentValues() = runTest {
        var rounds = 0
        val tool = BoundTool(ToolSchema("document_read", "test", JSONObject())) {
            throw IllegalArgumentException("DOCUMENT_SELECTOR: private input must not enter metadata")
        }
        AgentLoopRunner().run(listOf(tool)) { history, _ ->
            if (rounds++ == 0) GenerationResult("", toolCalls = listOf(ModelToolCall("1", "document_read", "{}")))
            else {
                val output = JSONObject(history.last().getString("content"))
                assertEquals("DOCUMENT_SELECTOR", output.getString("validation_code"))
                assertNull(ToolValidation.safe("private input"))
                GenerationResult("Correct selector required")
            }
        }
        assertEquals(2, rounds)
    }
    @Test fun permissionRevocationIsTerminalAndExecutorNeverStarts() = runTest {
        var executions = 0; var rounds = 0
        val tool = BoundTool(ToolSchema("document_read", "test", JSONObject()), beforeExecute = {
            throw AgentFailure("PERMISSION_REVOKED", "Fetch revoked")
        }) { executions++; "{}" }
        val failure = runCatching { AgentLoopRunner().run(listOf(tool)) { _, _ ->
            rounds++; GenerationResult("", toolCalls = listOf(ModelToolCall("$rounds", "document_read", "{}")))
        } }.exceptionOrNull() as AgentFailure
        assertEquals("PERMISSION_REVOKED", failure.code)
        assertEquals(1, rounds); assertEquals(0, executions)
    }
}
