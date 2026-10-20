package com.ayuemin.ilmar.agent

import com.ayuemin.ilmar.model.*
import com.ayuemin.ilmar.settings.RuntimeLimits
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AgentRecoveryTest {
    @Test fun invalidJsonIsReturnedToModelForRepairAndSuccessfulRoundIsArchived() = runTest {
        var executions = 0; var round = 0; val archived = mutableListOf<List<JSONObject>>()
        val tool = BoundTool(ToolSchema("calculator", "test", JSONObject())) { executions++; "4" }
        val result = AgentLoopRunner(onRound = { _, messages -> archived += messages }).run(listOf(tool)) { history, _ ->
            when (round++) {
                0 -> GenerationResult("", toolCalls = listOf(ModelToolCall("bad", "calculator", "{broken")))
                1 -> {
                    assertTrue(history.last().getString("content").contains("INVALID_ARGUMENTS"))
                    GenerationResult("", toolCalls = listOf(ModelToolCall("good", "calculator", "{}")))
                }
                else -> GenerationResult("4")
            }
        }
        assertEquals("4", result.text); assertEquals(1, executions); assertEquals(2, archived.size)
        assertTrue(archived.all { it.first().getString("role") == "assistant" && it.last().getString("role") == "tool" })
    }
    @Test fun checkpointsPersistEachCompletedToolBeforeNextExecution() = runTest {
        val checkpoints = mutableListOf<List<JSONObject>>()
        val tool = BoundTool(ToolSchema("write", "test", JSONObject())) {
            if (checkpoints.last().size != 1) error("Tool group was not checkpointed before execution")
            "saved"
        }
        var round = 0
        val result = AgentLoopRunner(onCheckpoint = { _, messages ->
            checkpoints += messages.map { JSONObject(it.toString()) }
        }).run(listOf(tool)) { _, _ ->
            if (round++ == 0) GenerationResult("", toolCalls = listOf(ModelToolCall("one", "write", "{}")))
            else GenerationResult("done")
        }
        assertEquals("done", result.text)
        assertEquals(2, checkpoints.size)
        assertEquals("assistant", checkpoints[0].single().getString("role"))
        assertEquals("tool", checkpoints[1].last().getString("role"))
        assertEquals("saved", checkpoints[1].last().getString("content"))
    }

    @Test fun longTaskCanProceedPastOldEightRoundLimitAndOptionalBudgetsCanBeDisabled() = runTest {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        RuntimeLimits.install(context); RuntimeLimits.reset()
        RuntimeLimits.save(RuntimeLimits.fields.associate { it.key to RuntimeLimits.get(it.key) } + mapOf("rounds" to 0, "calls" to 0, "task_seconds" to 0))
        try {
            var round = 0; var executions = 0
            val tool = BoundTool(ToolSchema("progress", "test", JSONObject())) { executions++; "revision=$executions" }
            val result = AgentLoopRunner().run(listOf(tool)) { _, _ ->
                if (round++ < 30) GenerationResult("", toolCalls = listOf(ModelToolCall("$round", "progress", "{}"))) else GenerationResult("done")
            }
            assertEquals("done", result.text); assertEquals(30, executions)
        } finally { RuntimeLimits.reset() }
    }
}
