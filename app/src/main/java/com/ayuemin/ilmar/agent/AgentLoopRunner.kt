package com.ayuemin.ilmar.agent

import com.ayuemin.ilmar.model.*
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

internal data class BoundTool(val schema: ToolSchema, val execute: suspend (JSONObject) -> String)

/** Every execution crosses a bound permission check. No arbitrary downloaded code or implicit retries. */
internal class AgentLoopRunner(private val maxRounds: Int = 8, private val maxCalls: Int = 24) {
    suspend fun run(tools: List<BoundTool>, generate: suspend (List<JSONObject>, List<ToolSchema>) -> GenerationResult): GenerationResult = withTimeout(180_000) {
        val history = mutableListOf<JSONObject>()
        val guard = AgentToolLoopGuard()
        var calls = 0
        repeat(maxRounds) {
            currentCoroutineContext().ensureActive()
            val result = generate(history.toList(), tools.map { it.schema })
            if (result.toolCalls.isEmpty()) return@withTimeout result
            require(tools.isNotEmpty()) { "Модель запросила инструмент, но агент и инструменты не включены" }
            require(result.toolCalls.map { it.id }.distinct().size == result.toolCalls.size) { "Повторяющиеся ID вызовов" }
            history += JSONObject().put("role", "assistant").put("content", result.text)
                .put("tool_calls", JSONArray().apply { result.toolCalls.forEach { call ->
                    put(JSONObject().put("id", call.id).put("type", "function").put("function", JSONObject().put("name", call.name).put("arguments", call.arguments)))
                } })
            for (call in result.toolCalls) {
                check(++calls <= maxCalls) { "Достигнут лимит вызовов инструментов" }
                val tool = tools.find { it.schema.name == call.name } ?: error("Инструмент не разрешён для этого чата")
                require(call.arguments.length <= 64 * 1024) { "Слишком большие аргументы инструмента" }
                val arguments = JSONObject(call.arguments)
                val output = withTimeout(20_000) { tool.execute(arguments) }
                require(output.length <= 64 * 1024) { "Результат инструмента слишком большой" }
                check(!guard.observe(call.name, call.arguments, output)) { "Агент остановлен: повторяющиеся вызовы без прогресса" }
                history += JSONObject().put("role", "tool").put("tool_call_id", call.id).put("content", output)
            }
        }
        error("Агент остановлен: достигнут лимит шагов")
    }
}
