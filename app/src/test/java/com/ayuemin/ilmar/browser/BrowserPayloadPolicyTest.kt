package com.ayuemin.ilmar.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BrowserPayloadPolicyTest {
    private fun page(text: String, id: String) = JSONObject().put("url", "https://example.com/").put("page_id", "page").put("snapshot_id", id)
        .put("text", text).put("controls", JSONArray("[{\"ref\":1,\"label\":\"Search\"}]")).put("links", JSONArray())
    private fun group(id: String, content: JSONObject, name: String = "browser_read") = listOf(
        JSONObject().put("role", "assistant").put("_trace_id", "trace-$id").put("tool_calls", JSONArray().put(JSONObject().put("id", id).put("function", JSONObject().put("name", name).put("arguments", "{}")))),
        JSONObject().put("role", "tool").put("tool_call_id", id).put("content", content.toString()))
    @Test fun customSnapshotLimitsBoundPayloadAndKeepFreshRefs() {
        val policy = BrowserPolicy(textChars = 80, fullChars = 300, controls = 1, links = 1)
        val input = page("x".repeat(2000), "one").put("controls", JSONArray("[{\"ref\":1},{\"ref\":2}]"))
        val compact = BrowserSnapshot.decode(JSONObject.quote(input.toString()), policy = policy)
        val full = BrowserSnapshot.decode(JSONObject.quote(input.toString()), full = true, policy = policy)
        assertEquals(80, compact.getString("text").length); assertEquals(300, full.getString("text").length)
        assertEquals(1, compact.getJSONArray("controls").length()); assertEquals(1, compact.getJSONArray("controls").getJSONObject(0).getInt("ref"))
    }
    @Test fun oldPayloadsAreSummarizedLatestDeltaBecomesSelfContainedAndOtherToolsStayIntact() {
        val baseline = page("x".repeat(4000), "base")
        val delta = page("", "next").put("representation", "DOM_DELTA").put("base_snapshot_id", "base").put("text_unchanged", true).put("controls_unchanged", true)
        delta.remove("controls")
        val original = group("a", baseline) + group("b", delta) + group("c", JSONObject().put("result", "precise other tool data"), "calculator")
        val projected = BrowserHistoryProjection.project(original, BrowserPolicy(historyItems = 1))
        val historical = JSONObject(projected[1].getString("content"))
        assertTrue(historical.getBoolean("historical")); assertFalse(historical.has("controls"))
        val current = JSONObject(projected[3].getString("content"))
        assertEquals(baseline.getString("text"), current.getString("text")); assertTrue(current.has("controls")); assertFalse(current.has("base_snapshot_id"))
        assertEquals(original[5].toString(), projected[5].toString())
        assertEquals(4000, JSONObject(original[1].getString("content")).getString("text").length)
    }
    @Test fun smallBudgetTrimsTextButNeverDropsCurrentControlMap() {
        val original = group("a", page("x".repeat(10000), "base"))
        val projected = BrowserHistoryProjection.project(original, BrowserPolicy(historyTokens = 180))
        val result = JSONObject(projected.last().getString("content"))
        assertTrue(result.getString("text").length < 10000); assertEquals(1, result.getJSONArray("controls").length())
        assertTrue(result.getBoolean("context_excerpt"))
        assertTrue(runCatching { BrowserHistoryProjection.project(original, BrowserPolicy(historyTokens = 1)) }.isFailure)
    }
    @Test fun genericHistoryReductionCannotReplaceLatestDomWithTraceExcerpt() {
        val current = group("a", page("x".repeat(1500), "current"))
        val size = com.ayuemin.ilmar.memory.ContextEstimate.text(current.joinToString())
        // This group fits the budget but exceeds the former 70% retention threshold.
        val kept = com.ayuemin.ilmar.memory.ToolHistoryProjection.project(current, size + 100, preserveLatestBrowser = true)
        assertEquals("current", JSONObject(kept.last().getString("content")).getString("snapshot_id"))
        assertEquals(1, JSONObject(kept.last().getString("content")).getJSONArray("controls").length())
        val error = runCatching { com.ayuemin.ilmar.memory.ToolHistoryProjection.project(current, 128, preserveLatestBrowser = true) }.exceptionOrNull() as com.ayuemin.ilmar.agent.AgentFailure
        assertEquals("BROWSER_CONTEXT_LIMIT", error.code)
    }
}
