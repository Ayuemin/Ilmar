package com.ayuemin.ilmar.browser

import com.ayuemin.ilmar.agent.AgentFailure
import com.ayuemin.ilmar.model.ModelCapabilities
import com.ayuemin.ilmar.model.TokenUsage
import com.ayuemin.ilmar.model.usageJson
import com.ayuemin.ilmar.settings.RuntimeLimits as Limits
import org.json.JSONObject
import java.math.BigDecimal

/** Limits and consent are captured for a run; changing modes cannot approve a pending command. */
internal class BrowserRunControl(
    private val chat: Long,
    private val autoConsent: Boolean,
    private val ask: suspend (Long, String, String) -> Boolean = BrowserPrompts::ask,
    private val event: (String, JSONObject) -> Unit = { _, _ -> },
    private val notice: (String) -> Unit = {},
    private val actionLimit: Int = Limits.get("browser_actions"),
    private val roundLimit: Int = Limits.get("browser_rounds"),
    private val cents: Int = Limits.get("browser_budget_cents")
) {
    private var consent = !autoConsent
    private var denied = false
    private var actions = 0
    private var rounds = 0
    private var allowedActions = actionLimit.toLong()
    private var allowedRounds = roundLimit.toLong()
    private var allowedCost = cents.toBigDecimal().movePointLeft(2)
    private var spent = BigDecimal.ZERO
    private var unknownCost = false
    private var estimatedCost = false
    private val warned = mutableSetOf<String>()
    private fun active() { if (denied) throw AgentFailure("BROWSER_CANCELLED", "Браузерная задача отменена. Скрытого повтора нет.") }
    private suspend fun permission(title: String, message: String) {
        active()
        var accepted = false
        try { accepted = ask(chat, title, message) } finally { if (!accepted) denied = true }
        active()
    }
    suspend fun beforeAction() {
        active()
        if (!consent) {
            permission("Использовать локальный браузер?", "В режиме «Авто» выбран локальный браузер телефона. Чтение страниц и действия могут потребовать дополнительных платных вызовов модели. Разрешить браузер для текущего запуска задачи?")
            consent = true
            event("BROWSER_CONSENT", JSONObject().put("allowed", true))
        }
        checkCount("действий", actions, actionLimit, allowedActions) { allowedActions += actionLimit }
        checkCost(BigDecimal.ZERO)
    }
    fun actionStarted(name: String) {
        active(); actions++
        event("BROWSER_ACTION", summary().put("tool", name))
    }
    fun actionResult(name: String, output: String, modelVisible: Boolean = true) {
        event("BROWSER_RESULT", summary().put("tool", name).put("model_visible", modelVisible).put("result_chars", output.length)
            .put("result_utf8_bytes", output.toByteArray(Charsets.UTF_8).size)
            .put("result_tokens_estimate", com.ayuemin.ilmar.memory.ContextEstimate.text(output)))
    }
    suspend fun beforeModel(input: Int, output: Int, caps: ModelCapabilities, images: Boolean) {
        active()
        checkCount("раундов", rounds, roundLimit, allowedRounds) { allowedRounds += roundLimit }
        val estimate = if (!images && caps.promptPrice != null && caps.completionPrice != null && caps.requestPrice != null)
            maxOf(caps.promptPrice.toBigDecimal(), caps.cacheWritePrice?.toBigDecimal() ?: BigDecimal.ZERO) * input.toBigDecimal() + caps.completionPrice.toBigDecimal() * output.toBigDecimal() + caps.requestPrice.toBigDecimal()
            else null
        if (cents > 0 && (unknownCost || estimate == null)) {
            permission("Стоимость браузера неизвестна", "Провайдер не сообщил часть usage/стоимости или нет полного тарифа для следующего запроса. Проверить денежный бюджет невозможно. Разрешить один следующий запрос? Лимиты действий и раундов сохраняются.")
        } else checkCost(estimate ?: BigDecimal.ZERO)
        rounds++
    }
    fun afterModel(usage: TokenUsage, caps: ModelCapabilities) {
        val reported = usage.costUsd?.toBigDecimalOrNull()
        val estimate = if (usage.input != null && usage.output != null && caps.promptPrice != null && caps.completionPrice != null && caps.requestPrice != null)
            usage.input.toBigDecimal() * caps.promptPrice.toBigDecimal() + usage.output.toBigDecimal() * caps.completionPrice.toBigDecimal() + caps.requestPrice.toBigDecimal() else null
        if (reported != null) spent += reported
        else if (estimate != null) { spent += estimate; estimatedCost = true } else unknownCost = true
        event("BROWSER_MODEL_ROUND", summary().put("usage", usageJson(usage)).put("round_cost_usd", (reported ?: estimate)?.toPlainString() ?: JSONObject.NULL)
            .put("round_cost_source", if (reported != null) "provider_reported" else if (estimate != null) "tariff_estimate" else "unknown"))
    }
    private suspend fun checkCount(kind: String, current: Int, limit: Int, allowed: Long, extend: () -> Unit) {
        if (limit == 0) return
        if (current >= allowed) {
            permission("Достигнут лимит браузера", "Выполнено $current $kind. Следующее действие остановлено. Разрешить ещё $limit $kind в этом запуске?")
            extend(); warned.remove(kind)
        } else if (current.toLong() * 100 >= allowed * 80 && warned.add(kind)) notice("Браузер: достигнуто 80% лимита $kind ($current / $allowed)")
    }
    private suspend fun checkCost(next: BigDecimal) {
        if (cents == 0) return
        if (spent + next > allowedCost || spent >= allowedCost) {
            permission("Достигнут бюджет браузера", "Учтено USD ${spent.toPlainString()}; следующий запрос по тарифу ≈ USD ${next.toPlainString()}. Лимит USD $allowedCost. ${if (estimatedCost) "Часть суммы — оценка по тарифу." else "Сумма получена из usage провайдера."} Это локальный контроль, не точное списание. Разрешить дополнительный бюджет?")
            allowedCost = maxOf(allowedCost + cents.toBigDecimal().movePointLeft(2), spent + next)
            warned.remove("cost")
        } else if (spent >= allowedCost * BigDecimal("0.8") && warned.add("cost")) notice("Браузер: достигнуто 80% бюджета USD $allowedCost; учтено $spent")
    }
    fun summary() = JSONObject().put("browser_actions", actions).put("model_rounds", rounds).put("accounted_cost_usd", spent.toPlainString())
        .put("cost_incomplete", unknownCost).put("includes_tariff_estimate", estimatedCost).put("scope", "one_browser_run")
}
