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
        assertEquals(3, checkpoints.size)
        assertEquals("assistant", checkpoints[0].single().getString("role"))
        assertEquals("tool", checkpoints[2].last().getString("role"))
        assertEquals("saved", checkpoints[2].last().getString("content"))
    }

    @Test fun executionCheckpointKeepsUnknownAndSeparatesFailedCallsAcrossTraces() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = com.ayuemin.ilmar.chat.ChatStore(context)
        val chat = System.currentTimeMillis()
        db.create(chat, "Checkpoint test")
        fun group(result: String?): List<JSONObject> {
            val call = JSONObject().put("id", "same-model-id").put("function",
                JSONObject().put("name", "calculator").put("arguments", "{}"))
            val assistant = JSONObject().put("role", "assistant").put("tool_calls", org.json.JSONArray().put(call))
            return if (result == null) listOf(assistant) else listOf(assistant,
                JSONObject().put("role", "tool").put("tool_call_id", "same-model-id").put("content", result))
        }
        try {
            db.checkpointExecutions(chat, "first", group(null))
            assertEquals("UNKNOWN", db.executionStatus(chat, "first:same-model-id"))
            db.checkpointExecutions(chat, "first", group("ok"))
            assertEquals("SUCCEEDED", db.executionStatus(chat, "first:same-model-id"))
            db.checkpointExecutions(chat, "second", group(JSONObject().put("error", "TOOL_TIMEOUT").toString()))
            assertEquals("FAILED", db.executionStatus(chat, "second:same-model-id"))
            assertEquals("SUCCEEDED", db.executionStatus(chat, "first:same-model-id"))
        } finally { db.delete(chat); db.close() }
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
