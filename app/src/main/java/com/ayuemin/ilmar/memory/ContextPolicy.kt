package com.ayuemin.ilmar.memory

import android.content.Context
import com.ayuemin.ilmar.chat.ChatMessage
import com.ayuemin.ilmar.model.*
import org.json.JSONObject
import java.math.BigDecimal
import java.security.MessageDigest

internal class ContextFailure(message: String) : IllegalStateException(message)
internal data class ContextPolicy(val window: Int = 0, val output: Int = 0, val recent: Int = 6,
    val taskTokens: Int = 0, val taskUsd: String? = null, val automatic: Boolean = true) {
    fun limit(caps: ModelCapabilities) = (if (window == 0) caps.contextLength ?: 32768L else minOf(window.toLong(), caps.contextLength ?: window.toLong())).coerceIn(128, Int.MAX_VALUE.toLong()).toInt()
    fun reserve(caps: ModelCapabilities) = (if (output == 0) minOf(caps.maxOutputTokens ?: 8192L, (limit(caps) / 4).toLong()) else minOf(output.toLong(), caps.maxOutputTokens ?: output.toLong())).coerceAtLeast(1).toInt()
    fun validate() {
        require((window == 0 || window in 2048..Int.MAX_VALUE) && (output == 0 || output in 256..1000000) && (window == 0 || output < window / 2)) { "Резерв ответа должен быть меньше половины контекста" }
        require(recent in 0..10000 && (taskTokens == 0 || taskTokens in 2048..Int.MAX_VALUE)) { "Проверь число последних сообщений и бюджет задачи" }
        require(taskUsd == null || (taskUsd.toBigDecimalOrNull()?.let { it > BigDecimal.ZERO } == true)) { "Бюджет USD должен быть положительным числом" }
    }
}
internal class ContextPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("ilmar_context", Context.MODE_PRIVATE)
    fun load() = ContextPolicy(prefs.getInt("window", 0), prefs.getInt("output", 0), prefs.getInt("recent", 6),
        prefs.getInt("task_tokens", 0), prefs.getString("task_usd", null), prefs.getBoolean("automatic", true))
    var compactModelId: String?
        get() = prefs.getString("compact_model_id", null)
        set(value) { check(prefs.edit().putString("compact_model_id", value).commit()) }
    fun save(policy: ContextPolicy) { policy.validate(); check(prefs.edit().putInt("window", policy.window).putInt("output", policy.output)
        .putInt("recent", policy.recent).putInt("task_tokens", policy.taskTokens).putString("task_usd", policy.taskUsd)
        .putBoolean("automatic", policy.automatic).commit()) }
}
internal object ContextEstimate {
    fun text(text: String): Int = ((text.toByteArray(Charsets.UTF_8).size.toLong() * 2 + 4) / 5).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    fun messages(messages: List<ChatMessage>, images: Boolean = true): Int = messages.sumOf { message ->
        12 + text(message.text) + message.attachments.sumOf { text(it.name) + text(it.text.orEmpty()) + if (images) it.images.size * 4096 else 0 }
    }
    fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    fun source(messages: List<ChatMessage>, through: Long) = hash(messages.takeWhile { it.id <= through }.joinToString("\n") {
        JSONObject().put("id", it.id).put("user", it.fromUser).put("text", it.text)
            .put("attachments", com.ayuemin.ilmar.attachments.ChatAttachment.encode(it.attachments)).toString()
    })
}
internal class TaskBudget(private val policy: ContextPolicy, private val caps: ModelCapabilities) {
    private var tokens = 0L
    private var cost = BigDecimal.ZERO
    private var reservedTokens = 0L
    private var reservedCost = BigDecimal.ZERO
    fun before(input: Int, output: Int, images: Boolean, boundedOutput: Boolean, modelCaps: ModelCapabilities = caps) {
        val caps = modelCaps
        reservedTokens = input.toLong() + output
        if (policy.taskTokens > 0 && tokens + reservedTokens > policy.taskTokens) throw ContextFailure("Достигнут токеновый бюджет задачи. Измени лимит в настройках памяти.")
        val limit = policy.taskUsd?.toBigDecimal()
        if (limit != null && (images || !boundedOutput || caps.promptPrice == null || caps.completionPrice == null || caps.requestPrice == null))
            throw ContextFailure("Для бюджета USD нужны тарифы текста и ограничение вывода в каталоге. Для изображений денежная оценка недоступна.")
        reservedCost = if (caps.promptPrice != null && caps.completionPrice != null)
            maxOf(caps.promptPrice.toBigDecimal(), caps.cacheWritePrice?.toBigDecimal() ?: BigDecimal.ZERO).multiply(input.toBigDecimal()) + caps.completionPrice.toBigDecimal().multiply(output.toBigDecimal()) + (caps.requestPrice?.toBigDecimal() ?: BigDecimal.ZERO)
            else BigDecimal.ZERO
        if (limit != null && cost + reservedCost > limit) throw ContextFailure("Следующий запрос превышает локальный бюджет USD по оценке тарифа. Лимит проверяется до отправки.")
        tokens += reservedTokens; cost += reservedCost
    }
    fun after(usage: TokenUsage) {
        val actualTokens = usage.total ?: if (usage.input != null && usage.output != null) usage.input + usage.output else null
        if (actualTokens != null) tokens += actualTokens - reservedTokens
        usage.costUsd?.let { cost += it.toBigDecimal() - reservedCost }
    }
}
