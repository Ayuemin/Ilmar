package com.ayuemin.ilmar.agent

import com.ayuemin.ilmar.settings.RuntimeLimits as Limits
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Only an AgentLoopRunner can supply this capability. Composite tools cannot run unjournaled writes. */
internal class LocalToolExecutor(
    private val tools: List<BoundTool>,
    private val reserveCall: () -> Unit,
    private val checkpoint: (String, List<JSONObject>) -> Unit,
    private val blocked: (String, String, String) -> Boolean,
    private val replay: (String, String) -> ConfirmedToolResult?,
    private val progress: (String) -> Unit,
    private val observe: (String, String, String) -> Unit
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LocalToolExecutor> {
        suspend fun current() = currentCoroutineContext()[Key] ?: throw AgentFailure("LOCAL_EXECUTOR_REQUIRED", "Локальной цепочке нужен активный журнал задачи")
        suspend fun insideStep() = currentCoroutineContext()[Step] != null
        const val STEP = "_browser_batch_step"
    }
    private object Step : CoroutineContext.Key<StepContext>
    private class StepContext : AbstractCoroutineContextElement(Step)
    fun confirmed(identity: String) = replay(STEP, JSONObject().put("identity", identity).toString())
    suspend fun execute(name: String, arguments: JSONObject, identity: String? = null): String {
        currentCoroutineContext().ensureActive()
        val tool = tools.singleOrNull { it.schema.name == name && it.localPrimitive }
            ?: throw AgentFailure("LOCAL_TOOL_FORBIDDEN", "Инструмент $name не разрешён внутри локальной цепочки")
        reserveCall()
        val raw = arguments.toString()
        if (raw.toByteArray().size > Limits.bytes("argument_kib")) throw AgentFailure("ARGUMENT_LIMIT", "Слишком большие аргументы локального шага")
        val mutation = !ToolOperationPolicy.safeToRepeat(name, raw)
        val journalName = if (mutation) STEP else name
        val journalArgs = if (mutation) JSONObject().put("identity", requireNotNull(identity)).put("tool", name).put("arguments", arguments).toString() else raw
        val trace = java.util.UUID.randomUUID().toString()
        val id = "local"
        val messages = mutableListOf(JSONObject().put("role", "assistant").put("_trace_id", trace).put("_started_calls", JSONArray())
            .put("tool_calls", JSONArray().put(JSONObject().put("id", id).put("type", "function").put("function",
                JSONObject().put("name", journalName).put("arguments", journalArgs)))))
        checkpoint(trace, messages)
        if (blocked("$trace:$id", journalName, journalArgs)) throw AgentFailure("UNRESOLVED_TOOL", "$name: предыдущая браузерная запись имеет неопределённый исход")
        val prior = if (mutation) replay(journalName, journalArgs) else null
        if (prior != null) return reused(prior)
        tool.beforeExecute(arguments)
        currentCoroutineContext().ensureActive()
        messages.first().getJSONArray("_started_calls").put(id)
        checkpoint(trace, messages)
        progress("Локально: $name")
        val output = try {
            optionalTimeout(Limits.get("tool_seconds")) { withContext(StepContext()) { tool.execute(arguments) } }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            JSONObject().put("error", "TOOL_TIMEOUT").put("tool", name).toString()
        } catch (e: CancellationException) { throw e }
        catch (e: org.json.JSONException) { JSONObject().put("error", "INVALID_ARGUMENTS").put("message", e.message).toString() }
        catch (e: IllegalArgumentException) { JSONObject().put("error", "INVALID_ARGUMENTS").put("message", e.message).toString() }
        if (output.toByteArray().size > Limits.bytes("result_kib")) throw AgentFailure("RESULT_LIMIT", "$name: слишком большой результат локального шага")
        messages += JSONObject().put("role", "tool").put("tool_call_id", id).put("content", output)
        checkpoint(trace, messages)
        observe(name, raw, output)
        return JSONObject(output).put("_local_trace_id", trace).toString()
    }
    fun reused(result: ConfirmedToolResult) = runCatching { JSONObject(result.result) }.getOrElse { JSONObject().put("result", result.result) }
        .put("_reused_confirmed", true).put("_original_execution_id", result.executionId).toString()
}
