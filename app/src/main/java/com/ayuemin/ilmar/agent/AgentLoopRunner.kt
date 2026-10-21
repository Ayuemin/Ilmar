package com.ayuemin.ilmar.agent

import com.ayuemin.ilmar.model.*
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import com.ayuemin.ilmar.settings.RuntimeLimits as Limits

internal data class ConfirmedToolResult(val executionId: String, val result: String)

internal data class BoundTool(val schema: ToolSchema, val localPrimitive: Boolean = false, val beforeExecute: suspend (JSONObject) -> Unit = {}, val execute: suspend (JSONObject) -> String)

/** Every execution crosses a bound permission check. No arbitrary downloaded code or implicit retries. */
internal class AgentFailure(val code: String, message: String) : IllegalStateException(message)

internal suspend fun <T> optionalTimeout(seconds: Int, action: suspend () -> T): T =
    if (seconds == 0) action() else withTimeout(seconds.toLong() * 1000) { action() }

internal class AgentLoopRunner(private val maxRounds: Int = Limits.get("rounds"), private val maxCalls: Int = Limits.get("calls"),
    private val onRound: (String, List<JSONObject>) -> Unit = { _, _ -> },
    private val onCheckpoint: (String, List<JSONObject>) -> Unit = { _, _ -> }, private val onProgress: (String) -> Unit = {},
    private val blockUnresolved: (String, String, String) -> Boolean = { _, _, _ -> false },
    private val onHistoryReduced: () -> Unit = {},
    private val replayConfirmed: (String, String) -> ConfirmedToolResult? = { _, _ -> null }) {
    suspend fun run(tools: List<BoundTool>, generate: suspend (List<JSONObject>, List<ToolSchema>) -> GenerationResult): GenerationResult = optionalTimeout(Limits.get("task_seconds")) {
        val browserPolicy = com.ayuemin.ilmar.browser.BrowserPolicy.load()
        val history = mutableListOf<JSONObject>()
        val repeatThreshold = Limits.get("repeat_threshold")
        val guard = AgentToolLoopGuard(threshold = repeatThreshold.coerceAtLeast(2))
        var calls = 0
        fun reserveCall() {
            calls++
            if (maxCalls > 0 && calls > maxCalls) throw AgentFailure("CALL_LIMIT", "Достигнут лимит $maxCalls вызовов tools, включая локальные шаги.")
        }
        val local = LocalToolExecutor(tools, ::reserveCall, onCheckpoint, blockUnresolved, replayConfirmed, onProgress) { name, args, output ->
            if (repeatThreshold > 0 && guard.observe(name, args, output)) throw AgentFailure("NO_PROGRESS", "$name: локальные шаги повторяются без прогресса")
        }
        var round = 0
        while (maxRounds == 0 || round < maxRounds) {
            round++
            currentCoroutineContext().ensureActive()
            onProgress("Раунд $round · вызовов tools: $calls")
            val result = generate(history.toList(), tools.map { it.schema })
            if (result.toolCalls.isEmpty()) return@optionalTimeout result
            if (tools.isEmpty()) throw AgentFailure("TOOLS_DISABLED", "Модель запросила инструмент, но режим агента или инструменты не включены.")
            require(result.toolCalls.map { it.id }.distinct().size == result.toolCalls.size) { "Повторяющиеся ID вызовов" }
            val traceId = java.util.UUID.randomUUID().toString()
            val groupStart = history.size
            history += JSONObject().put("role", "assistant").put("content", result.text)
                .put("_trace_id", traceId).put("_started_calls", JSONArray())
                .put("tool_calls", JSONArray().apply { result.toolCalls.forEach { call ->
                    put(JSONObject().put("id", call.id).put("type", "function").put("function", JSONObject().put("name", call.name).put("arguments", call.arguments)))
                } })
            // Persist the call group before any side-effectful tool can begin.
            onCheckpoint(traceId, history.drop(groupStart))
            for (call in result.toolCalls) {
                reserveCall()
                val tool = tools.find { it.schema.name == call.name }
                if (call.arguments.toByteArray(Charsets.UTF_8).size > Limits.bytes("argument_kib")) throw AgentFailure("ARGUMENT_LIMIT", "${call.name}: аргументы превышают глобальный лимит. Увеличь его в расширенных настройках.")
                if (blockUnresolved("$traceId:${call.id}", call.name, call.arguments)) throw AgentFailure("UNRESOLVED_TOOL", "${call.name}: повтор неопределённой операции заблокирован. Сначала проверь результат.")
                val replay = if (tool != null) replayConfirmed(call.name, call.arguments) else null
                val output = if (replay != null) {
                    onProgress("${call.name}: используется подтверждённый результат без повторного действия")
                    val confirmed = runCatching { JSONObject(replay.result) }.getOrElse { JSONObject().put("result", replay.result) }
                    confirmed.put("_reused_confirmed", true).put("_original_execution_id", replay.executionId).toString()
                } else {
                    val preflight = try { JSONObject(call.arguments) } catch (_: org.json.JSONException) { null }
                    if (preflight != null) tool?.beforeExecute?.invoke(preflight)
                    currentCoroutineContext().ensureActive()
                    // Planned calls are not execution intents. Persist only this call immediately before entering its executor.
                    history[groupStart].getJSONArray("_started_calls").put(call.id)
                    onCheckpoint(traceId, history.drop(groupStart))
                    onProgress("Раунд $round · выполняется ${call.name}")
                    try {
                        if (tool == null) JSONObject().put("error", "UNKNOWN_TOOL").put("message", "Инструмент ${call.name} не доступен; используй только предоставленные schemas.").toString()
                        else optionalTimeout(Limits.get(if (call.name == "browser_download") "download_seconds" else "tool_seconds")) { kotlinx.coroutines.withContext(local) { tool.execute(JSONObject(call.arguments)) } }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        JSONObject().put("error", "TOOL_TIMEOUT").put("tool", call.name).put("message", "Истекло время вызова. Проверь аргументы или расширенные настройки.").toString()
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: com.ayuemin.ilmar.tools.ClarificationRequired) { throw e }
                    catch (e: org.json.JSONException) { JSONObject().put("error", "INVALID_ARGUMENTS").put("message", "Исправь JSON-аргументы ${call.name}: ${e.message}").toString() }
                    catch (e: IllegalArgumentException) { JSONObject().put("error", "INVALID_ARGUMENTS").put("message", e.message).toString() }
                }
                if (output.toByteArray(Charsets.UTF_8).size > Limits.bytes("result_kib")) throw AgentFailure("RESULT_LIMIT", "${call.name}: результат превышает глобальный лимит. Увеличь его или читай данные частями.")
                history += JSONObject().put("role", "tool").put("tool_call_id", call.id).put("content", output).apply { replay?.let { put("_reused_from", it.executionId) } }
                // The result must survive a crash before the next tool starts.
                onCheckpoint(traceId, history.drop(groupStart))
                val control = runCatching { JSONObject(output) }.getOrNull()
                if (control?.optBoolean("_awaiting_user") == true) throw com.ayuemin.ilmar.tools.ClarificationRequired(control.optString("message", "Нужно действие пользователя в браузере."))
                if (repeatThreshold > 0 && guard.observe(call.name, call.arguments, replay?.result ?: output)) throw AgentFailure("NO_PROGRESS", "${call.name}: повторяющиеся вызовы без прогресса. Защита настраивается в расширенных лимитах.")
            }
            onRound(traceId, history.drop(groupStart))
            val working = com.ayuemin.ilmar.memory.ToolHistoryProjection.project(com.ayuemin.ilmar.browser.BrowserHistoryProjection.project(history, browserPolicy), Limits.get("trace_tokens"), preserveTrace = true, preserveLatestBrowser = tools.any { it.schema.name.startsWith("browser_") })
            if (working.size < history.size) onHistoryReduced()
            history.clear(); history.addAll(working)
        }
        throw AgentFailure("ROUND_LIMIT", "Достигнут лимит $maxRounds раундов агента. История сохранена; лимит можно увеличить или отключить в расширенных настройках.")
    }
}
