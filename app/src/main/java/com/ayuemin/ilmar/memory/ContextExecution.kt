package com.ayuemin.ilmar.memory

import com.ayuemin.ilmar.agent.*
import com.ayuemin.ilmar.attachments.AttachmentStore
import com.ayuemin.ilmar.chat.ChatMessage
import com.ayuemin.ilmar.model.*
import org.json.JSONObject

internal class ContextExecution(private val chat: Long, private val memory: ContextStore, files: AttachmentStore, private val policy: ContextPolicy,
    private val caps: ModelCapabilities, private val target: ModelTarget,
    private val status: (String) -> Unit,
    private val compactTarget: ModelTarget = target, private val compactCaps: ModelCapabilities = caps,
    private val event: (String, JSONObject) -> Unit = { _, _ -> },
    private val semanticSearch: suspend (String, Long) -> String = { _, _ -> "" },
    private val session: TaskSession? = null,
    private val request: suspend (List<ChatMessage>, GenerationOptions, String, Int, Int) -> GenerationResult) {
    private var budget = TaskBudget(policy, caps)
    private lateinit var activeSession: TaskSession
    private val engine = ContextEngine(chat, memory, files, policy, caps, target,
        { history, options, purpose, input, saved -> invoke(history, options, purpose, input, saved) }, compactCaps, compactTarget,
        notice = { status(it); event("COMPACT_NOTICE", JSONObject().put("message", it)) },
        failure = { event("COMPACT_ERROR", JSONObject().put("message", it.message).put("exception", it.javaClass.simpleName).put("stack", it.stackTraceToString())) },
        semanticSearch = semanticSearch, factualState = { memory.tasks.note(activeSession) })
    private suspend fun invoke(history: List<ChatMessage>, options: GenerationOptions, purpose: String, input: Int, saved: Int): GenerationResult {
        val modelCaps = if (purpose == "compact") compactCaps else caps
        budget.before(input, options.maxOutput ?: policy.reserve(modelCaps), history.any { m -> m.attachments.any { it.images.isNotEmpty() } }, options.outputParameter != null, modelCaps)
        val id = java.util.UUID.randomUUID().toString()
        val priced = modelCaps.promptPrice != null && modelCaps.completionPrice != null && modelCaps.requestPrice != null
        memory.tasks.reserve(activeSession, id, budget.reservedTokens, budget.reservedCost.takeIf { priced }, purpose)
        if (purpose == "compact") status("Compact: сжатие ранней истории…")
        try {
            return request(history, options.copy(requestId = id), purpose, input, saved).also {
                budget.after(it.usage); memory.tasks.completeModel(id, "COMPLETED", it.usage)
            }
        } catch (e: Exception) {
            val usage = (e as? ProviderFailure)?.reportedUsage
            memory.tasks.completeModel(id, if (e is kotlinx.coroutines.CancellationException) "INTERRUPTED" else "FAILED", usage)
            if (usage != null) budget.after(usage)
            throw e
        }
    }
    suspend fun run(history: List<ChatMessage>, manual: Boolean, reasoning: ReasoningConfig?, tools: List<BoundTool>): GenerationResult? {
        activeSession = session ?: memory.tasks.begin(chat, history)
        try {
            val spent = memory.tasks.spent(activeSession.taskId)
            if (policy.taskUsd != null && spent.unknownCost) throw ContextFailure("Стоимость предыдущего запроса задачи неизвестна; денежный бюджет нельзя подтвердить. Проверь расходы у провайдера.")
            budget = TaskBudget(policy, caps, spent.tokens, spent.cost)
            val result = if (manual) {
                val projection = engine.prepare(history, emptyList(), emptyList(), force = true)
                status(engine.lastNotice ?: "Compact сохранён · оценка контекста ${projection.estimatedInput} + ${projection.output} / ${projection.limit}")
                null
            } else AgentLoopRunner(
                onCheckpoint = { id, partial -> memory.tasks.checkpoint(activeSession, id, partial) },
                onRound = { id, round ->
                    memory.saveToolTrace(chat, id, round)
                    event(if (round.any { it.optString("role") == "tool" && runCatching { JSONObject(it.optString("content")).has("error") }.getOrDefault(false) }) "TOOL_ROUND_ERROR" else "TOOL_ROUND",
                        activeSession.json().put("trace_id", id).put("messages", org.json.JSONArray(round)))
                },
                onProgress = { status(it); event("AGENT_PROGRESS", activeSession.json().put("message", it)) },
                blockUnresolved = { id, name, args -> memory.unresolvedExecution(chat, id, name, args) },
                replayConfirmed = { name, args -> memory.tasks.confirmed(activeSession, name, args) }
            ).run(tools) { toolHistory, schemas ->
                val projection = engine.prepare(history, schemas, toolHistory)
                status("Контекст ≈ ${projection.estimatedInput} + ${projection.output} / ${projection.limit} · сокращён на ≈ ${projection.saved}")
                invoke(projection.history, projection.options.copy(reasoning = reasoning), "answer", projection.estimatedInput, projection.saved)
            }
            memory.tasks.finish(activeSession, "COMPLETED")
            return result
        } catch (e: com.ayuemin.ilmar.tools.ClarificationRequired) { memory.tasks.finish(activeSession, "AWAITING_USER"); throw e }
        catch (e: kotlinx.coroutines.CancellationException) { memory.tasks.finish(activeSession, "PAUSED"); throw e }
        catch (e: Exception) { memory.tasks.finish(activeSession, "FAILED"); throw e }
    }
}
