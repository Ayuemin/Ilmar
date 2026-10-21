package com.ayuemin.ilmar.browser

import com.ayuemin.ilmar.settings.RuntimeLimits as Limits

/** Immutable for one run. Raising payload limits never raises safety or execution permissions. */
internal data class BrowserPolicy(
    val textChars: Int = 4000, val fullChars: Int = 12000,
    val controls: Int = 35, val links: Int = 20,
    val historyItems: Int = 2, val historyTokens: Int = 8000,
    val batchSteps: Int = 8, val waitMs: Int = 3000,
    val outputTokens: Int = 0
) {
    val expandedChars get() = maxOf(textChars, fullChars)
    val expandedControls get() = maxOf(60, controls).coerceAtMost(200)
    val expandedLinks get() = maxOf(40, links).coerceAtMost(100)
    companion object {
        fun load() = BrowserPolicy(Limits.get("browser_text_chars"), Limits.get("browser_full_chars"),
            Limits.get("browser_controls"), Limits.get("browser_links"), Limits.get("browser_history_items"),
            Limits.get("browser_history_tokens"), Limits.get("browser_batch_steps"), Limits.get("browser_wait_ms"), Limits.get("browser_output_tokens"))
    }
}
