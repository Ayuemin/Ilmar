package com.ayuemin.ilmar.memory

import com.ayuemin.ilmar.agent.AgentLoopRunner
import com.ayuemin.ilmar.agent.BoundTool
import com.ayuemin.ilmar.attachments.AttachmentStore
import com.ayuemin.ilmar.chat.ChatMessage
import com.ayuemin.ilmar.model.*

internal class ContextExecution(chat: Long, memory: ContextStore, files: AttachmentStore, private val policy: ContextPolicy,
    private val caps: ModelCapabilities, private val target: ModelTarget,
    private val status: (String) -> Unit,
    private val request: suspend (List<ChatMessage>, GenerationOptions, String, Int, Int) -> GenerationResult) {
    private val budget = TaskBudget(policy, caps)
    private val engine = ContextEngine(chat, memory, files, policy, caps, target) { history, options, purpose, input, saved ->
        invoke(history, options, purpose, input, saved)
    }
    private suspend fun invoke(history: List<ChatMessage>, options: GenerationOptions, purpose: String, input: Int, saved: Int): GenerationResult {
        budget.before(input, options.maxOutput ?: policy.reserve(caps), history.any { m -> m.attachments.any { it.images.isNotEmpty() } }, options.outputParameter != null)
        if (purpose == "compact") status("Compact: сжатие ранней истории…")
        return request(history, options, purpose, input, saved).also { budget.after(it.usage) }
    }
    suspend fun run(history: List<ChatMessage>, manual: Boolean, reasoning: ReasoningConfig?, tools: List<BoundTool>): GenerationResult? {
        if (manual) {
            val projection = engine.prepare(history, emptyList(), emptyList(), force = true)
            status("Compact сохранён · оценка контекста ${projection.estimatedInput} + ${projection.output} / ${projection.limit}")
            return null
        }
        return AgentLoopRunner().run(tools) { toolHistory, schemas ->
            val projection = engine.prepare(history, schemas, toolHistory)
            status("Контекст ≈ ${projection.estimatedInput} + ${projection.output} / ${projection.limit} · сокращён на ≈ ${projection.saved}")
            invoke(projection.history, projection.options.copy(reasoning = reasoning), "answer", projection.estimatedInput, projection.saved)
        }
    }
}
