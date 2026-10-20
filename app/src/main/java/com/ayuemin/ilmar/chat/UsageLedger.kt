package com.ayuemin.ilmar.chat

import com.ayuemin.ilmar.model.TokenUsage
import java.math.BigDecimal

/** Adapted from Umnik RequestCostLedger: exact decimal addition, unknown never means zero. */
data class UsageRecord(val usage: TokenUsage, val status: String)
class UsageLedger(records: List<UsageRecord>) {
    val calls = records.size
    val incompleteCost = records.any { it.usage.costUsd == null }
    val failed = records.count { it.status != "completed" }
    private fun sum(field: (TokenUsage) -> Long?): Long? = records.mapNotNull { field(it.usage) }.takeIf { it.isNotEmpty() }?.sum()
    val input = sum { it.input }; val output = sum { it.output }; val total = sum { it.total }
    val cached = sum { it.cached }; val reasoning = sum { it.reasoning }
    val costUsd = records.mapNotNull { it.usage.costUsd?.let(::BigDecimal) }.takeIf { it.isNotEmpty() }
        ?.fold(BigDecimal.ZERO, BigDecimal::add)?.stripTrailingZeros()?.toPlainString()
    private val partialTokens = records.any { it.usage.input == null || it.usage.output == null }
    fun describe(): String = buildString {
        append("Учтено запросов: $calls; прерванных/ошибок: $failed\n")
        append("Вход: ${input ?: "нет данных"}; выход: ${output ?: "нет данных"}\n")
        append("Всего токенов: ${total ?: "нет данных"}\nКэшированные входные: ${cached ?: "нет данных"}\n")
        append("Токены reasoning: ${reasoning ?: "нет данных"}\n")
        append("Стоимость: ${costUsd?.let { "$$it USD" } ?: "нет данных"}")
        if (incompleteCost && costUsd != null) append(" (известная часть)")
        if (partialTokens && calls > 0) append("\nСчётчики неполные: не все ответы содержат usage.")
        append("\nДанные сообщает провайдер; старые сообщения без usage не пересчитываются.")
    }
}
