package com.ayuemin.ilmar.model

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SseUsageFailureTest {
    @Test fun knownUsageSurvivesMalformedErrorAndIncompleteTool() {
        val usage = "data: {\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"cost\":\"0.123456789012345678901\"}}\n\n"
        for (tail in listOf("data: {broken}\n\n", "data: {\"error\":{}}\n\n", "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{}\"}}]}}]}\n\ndata: [DONE]\n\n")) {
            val error = assertThrows(ProviderFailure::class.java) { SseReader.readResult((usage+tail).reader().buffered()) {} }
            assertEquals(10L, error.reportedUsage!!.input); assertEquals("0.123456789012345678901", error.reportedUsage!!.costUsd)
        }
    }
    @Test fun openRouterOffIsExplicitAndUnknownProtocolIsNotInvented() {
        val caps = ModelCapabilities(parameters=setOf("reasoning"))
        val off = caps.reasoningConfig("https://openrouter.ai/api/v1", false, null)!!
        assertFalse(off.enabled)
        assertNull(caps.reasoningConfig("https://fixture.invalid/v1", false, null))
    }
    @Test fun oversizedUnterminatedEventStopsWithKnownUsage() {
        val limits=com.ayuemin.ilmar.settings.RuntimeLimits
        limits.install(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        limits.save(limits.fields.associate { it.key to limits.get(it.key) } + ("stream_mib" to 1))
        try {
            val prefix="data: {\"usage\":{\"prompt_tokens\":7}}\n\n"
            val error=assertThrows(ProviderFailure::class.java) { SseReader.readResult((prefix+"data: "+"x".repeat(1024*1024+1)).reader().buffered()) {} }
            assertEquals(7L,error.reportedUsage!!.input)
        } finally { limits.reset() }
    }
}
