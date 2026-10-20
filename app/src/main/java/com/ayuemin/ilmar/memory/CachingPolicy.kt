package com.ayuemin.ilmar.memory

import com.ayuemin.ilmar.model.ModelCapabilities
import java.math.BigDecimal

internal object CachingPolicy {
    /** Explicit ephemeral caching only for the documented OpenRouter/Anthropic adapter and known profitable tariffs. */
    fun ephemeral(endpoint: String, model: String, caps: ModelCapabilities, stablePrefix: String): Boolean {
        if (ModelCapabilities.host(endpoint) != "openrouter.ai" || !model.startsWith("anthropic/") || ContextEstimate.text(stablePrefix) < 2048) return false
        val input = caps.promptPrice?.toBigDecimalOrNull() ?: return false
        val read = caps.cacheReadPrice?.toBigDecimalOrNull() ?: return false
        val write = caps.cacheWritePrice?.toBigDecimalOrNull() ?: return false
        return write + read.multiply(BigDecimal(2)) < input.multiply(BigDecimal(3))
    }
}
