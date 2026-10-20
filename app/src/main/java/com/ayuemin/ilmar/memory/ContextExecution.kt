package com.ayuemin.ilmar.memory

import com.ayuemin.ilmar.agent.AgentLoopRunner
import com.ayuemin.ilmar.agent.BoundTool
import com.ayuemin.ilmar.attachments.AttachmentStore
import com.ayuemin.ilmar.chat.ChatMessage
import com.ayuemin.ilmar.model.*

internal class ContextExecution(private val chat: Long, private val memory: ContextStore, files: AttachmentStore, private val policy: ContextPolicy,
    private val caps: ModelCapabilities, private val target: ModelTarget,
    private val status: (String) -> Unit,
    private val compactTarget: ModelTarget = target, private val compactCaps: ModelCapabilities = caps,
    private val event: (String, org.json.JSONObject) -> Unit = { _, _ -> },
    private val semanticSearch: suspend (String, Long) -> String = { _, _ -> "" },
    private val request: suspend (List<ChatMessage>, GenerationOptions, String, Int, Int) -> GenerationResult) {
    private val budget = TaskBudget(policy, caps)
    private val engine = ContextEngine(chat, memory, files, policy, caps, target,
        { history, options, purpose, input, saved -> invoke(history, options, purpose, input, saved) }, compactCaps, compactTarget, notice = { status(it); event("COMPACT_NOTICE", org.json.JSONObject().put("message", it)) },
        failure = { event("COMPACT_ERROR", org.json.JSONObject().put("message", it.message).put("exception", it.javaClass.simpleName).put("stack", it.stackTraceToString())) }, semanticSearch = semanticSearch)
    private suspend fun invoke(history: List<ChatMessage>, options: GenerationOptions, purpose: String, input: Int, saved: Int): GenerationResult {
        budget.before(input, options.maxOutput ?: policy.reserve(caps), history.any { m -> m.attachments.any { it.images.isNotEmpty() } }, options.outputParameter != null, if (purpose == "compact") compactCaps else caps)
        if (purpose == "compact") status("Compact: сжатие ранней истории…")
        return request(history, options, purpose, input, saved).also { budget.after(it.usage) }
    }
    suspend fun run(history: List<ChatMessage>, manual: Boolean, reasoning: ReasoningConfig?, tools: List<BoundTool>): GenerationResult? {
        if (manual) {
            val projection = engine.prepare(history, emptyList(), emptyList(), force = true)
            status(engine.lastNotice ?: "Compact сохранён · оценка контекста ${projection.estimatedInput} + ${projection.output} / ${projection.limit}")
            return null
        }
        return AgentLoopRunner(onRound = { id, round -> memory.saveToolTrace(chat, id, round); event(if (round.any { it.optString("role") == "tool" && runCatching { org.json.JSONObject(it.optString("content")).has("error") }.getOrDefault(false) }) "TOOL_ROUND_ERROR" else "TOOL_ROUND", org.json.JSONObject().put("trace_id", id).put("messages", org.json.JSONArray(round))) }, onProgress = { status(it); event("AGENT_PROGRESS", org.json.JSONObject().put("message", it)) }).run(tools) { toolHistory, schemas ->
            val projection = engine.prepare(history, schemas, toolHistory)
            status("Контекст ≈ ${projection.estimatedInput} + ${projection.output} / ${projection.limit} · сокращён на ≈ ${projection.saved}")
            invoke(projection.history, projection.options.copy(reasoning = reasoning), "answer", projection.estimatedInput, projection.saved)
        }
    }
}
