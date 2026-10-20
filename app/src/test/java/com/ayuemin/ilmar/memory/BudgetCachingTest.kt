package com.ayuemin.ilmar.memory

import com.ayuemin.ilmar.model.*
import com.ayuemin.ilmar.chat.ChatMessage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BudgetCachingTest {
    @Test fun budgetsRefuseNextCallBeforeItCanSpendAndReconcileActualUsage() {
        val caps = ModelCapabilities(promptPrice = "0.001", completionPrice = "0.002", requestPrice = "0")
        val budget = TaskBudget(ContextPolicy(taskTokens = 3000, taskUsd = "2"), caps)
        budget.before(500, 500, false, true)
        budget.after(TokenUsage(100, 10, 110, costUsd = "0.12"))
        budget.before(500, 500, false, true)
        assertThrows(ContextFailure::class.java) { budget.before(500, 500, false, true) }
        val tokens = TaskBudget(ContextPolicy(taskTokens = 2048), caps)
        assertThrows(ContextFailure::class.java) { tokens.before(1800, 500, false, true) }
        assertThrows(ContextFailure::class.java) { TaskBudget(ContextPolicy(taskUsd = "1"), ModelCapabilities()).before(10, 500, false, false) }
        assertThrows(ContextFailure::class.java) { TaskBudget(ContextPolicy(taskUsd = "1"), caps).before(10, 500, true, true) }
    }
    @Test fun cacheControlRequiresProfitableKnownAdapterAndStableLongPrefix() {
        val caps = ModelCapabilities(promptPrice = "0.000003", completionPrice = "0.000015", cacheReadPrice = "0.0000003", cacheWritePrice = "0.00000375")
        val prefix = "stable ".repeat(1000)
        assertTrue(CachingPolicy.ephemeral("https://openrouter.ai/api/v1", "anthropic/claude", caps, prefix))
        assertFalse(CachingPolicy.ephemeral("https://other.example/v1", "anthropic/claude", caps, prefix))
        assertFalse(CachingPolicy.ephemeral("https://openrouter.ai/api/v1", "other/model", caps, prefix))
        assertFalse(CachingPolicy.ephemeral("https://openrouter.ai/api/v1", "anthropic/claude", caps.copy(cacheWritePrice = null), prefix))
        assertFalse(CachingPolicy.ephemeral("https://openrouter.ai/api/v1", "anthropic/claude", caps, "short"))
        val options = GenerationOptions(systemPrefix = prefix, maxOutput = 500, outputParameter = "max_tokens", ephemeralCache = true)
        val payload = GenerationPayload.build("anthropic/claude", listOf(ChatMessage("question", true)), options)
        assertEquals("ephemeral", payload.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getJSONObject("cache_control").getString("type"))
        assertEquals(500, payload.getInt("max_tokens"))
        val plain = GenerationPayload.build("other", emptyList(), options.copy(ephemeralCache = false, outputParameter = null))
        assertFalse(plain.has("max_tokens")); assertTrue(plain.getJSONArray("messages").getJSONObject(0).get("content") is String)
    }
    @Test fun priceMetadataRoundTripsAndSelectsSupportedOutputLimit() {
        val caps = ModelCapabilities.parse(JSONObject("""{"supported_parameters":["max_completion_tokens"],"pricing":{"prompt":"0.000001","completion":"0.000002","request":"0","input_cache_read":"0.0000001","input_cache_write":"0.00000125"}}"""))
        assertEquals(caps, ModelCapabilities.fromJson(caps.toJson()))
        assertEquals("max_completion_tokens", caps.outputParameter("https://custom.example/v1"))
        assertNull(ModelCapabilities().outputParameter("https://custom.example/v1"))
    }
}
