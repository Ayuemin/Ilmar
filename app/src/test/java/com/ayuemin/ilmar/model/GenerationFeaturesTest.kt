package com.ayuemin.ilmar.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GenerationFeaturesTest {
    private fun parse(events: String) = SseReader.readResult(events.reader().buffered()) {}
    private fun event(json: String) = "data: $json\n\n"
    @Test fun readsContentArraysAndExactUsageFromFinalEvent() {
        val result = parse(event("""{"choices":[{"delta":{"content":[{"type":"text","text":"Привет"},"!"]}}]}""") +
            event("""{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":4,"total_tokens":16,"prompt_tokens_details":{"cached_tokens":8},"completion_tokens_details":{"reasoning_tokens":2},"cost":0.123456789012345678901}}""") + "data: [DONE]\n\n")
        assertEquals("Привет!", result.text)
        assertEquals(TokenUsage(12, 4, 16, 8, 2, "0.123456789012345678901"), result.usage)
    }
    @Test fun errorsInsideChoiceCannotBecomeSuccessfulPartialAnswer() {
        val partial = event("""{"choices":[{"delta":{"content":"partial"}}]}""")
        for (error in listOf("""{"choices":[{"finish_reason":"error"}]}""", """{"choices":[{"error":{"message":"private"}}]}""")) {
            assertThrows(java.io.IOException::class.java) { parse(partial + event(error) + "data: [DONE]\n\n") }
        }
    }
    @Test fun joinsFragmentedToolArgumentsOnlyAfterCompleteStream() {
        val first = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject().put("tool_calls", org.json.JSONArray().put(
            JSONObject().put("index", 0).put("id", "call_1").put("function", JSONObject().put("name", "echo").put("arguments", "{\"a\":")))))))
        val next = """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]},"finish_reason":"tool_calls"}]}"""
        val result = parse(event(first.toString()) + event(next) + "data: [DONE]\n\n")
        assertEquals(listOf(ModelToolCall("call_1", "echo", "{\"a\":1}")), result.toolCalls)
        assertThrows(java.io.IOException::class.java) { parse(event(first.toString())) }
    }
    @Test fun keepsRepeatedCharactersInToolNameAndIdFragments() {
        val first = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_","function":{"name":"echo","arguments":"{}"}}]}}]}"""
        val second = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"_","function":{"name":"o"}}]}}]}"""
        val result = parse(event(first) + event(second) + "data: [DONE]\n\n")
        assertEquals(ModelToolCall("call__", "echoo", "{}"), result.toolCalls.single())
    }
    @Test fun unknownUsageRemainsUnknownAndCumulativeUsageIsNotAddedTwice() {
        val result = parse(event("""{"choices":[{"delta":{"content":"x"}}],"usage":{"prompt_tokens":10}}""") +
            event("""{"choices":[],"usage":{"prompt_tokens":10,"cost":"0"}}""") + "data: [DONE]\n\n")
        assertEquals(10L, result.usage.input)
        assertNull(result.usage.output)
        assertEquals("0", result.usage.costUsd)
    }
    @Test fun capabilitiesGateParametersAndRoundTrip() {
        val caps = ModelCapabilities.parse(JSONObject("""{"supported_parameters":["reasoning","tools"],"reasoning":{"supported_efforts":["low","high"]},"context_length":128000,"top_provider":{"max_completion_tokens":4096}}"""))
        assertTrue(caps.tools)
        assertTrue(caps.supportsReasoning("https://openrouter.ai/api/v1"))
        assertFalse(caps.supportsReasoning("https://unknown.example/v1"))
        assertEquals(caps, ModelCapabilities.fromJson(caps.toJson()))
        val reason = caps.reasoningConfig("https://openrouter.ai/api/v1", true, "invented")!!
        assertNull(reason.effort)
        val payload = GenerationPayload.build("vendor/model", emptyList(), GenerationOptions(reasoning = reason))
        assertTrue(payload.getJSONObject("reasoning").getBoolean("enabled"))
        assertFalse(payload.has("reasoning_effort"))
        assertFalse(payload.has("tools"))
        assertNull(ModelCapabilities().reasoningConfig("https://openrouter.ai/api/v1", true, "high"))
    }
}
