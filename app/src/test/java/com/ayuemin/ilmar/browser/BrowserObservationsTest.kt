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
class BrowserObservationsTest {
    private fun page(id: String = "page", text: String = "facts") = JSONObject().put("page_id", id).put("url", "https://example.com/")
        .put("text", text).put("controls", JSONArray("[{\"ref\":1,\"label\":\"Search\"}]")).put("links", JSONArray())
    @Test fun deltaKeepsChangedControlsAndCanRecoverWithExplicitRead() {
        val cache = BrowserObservations()
        val first = cache.encode(page())
        val next = cache.encode(page().put("controls", JSONArray("[{\"ref\":2,\"label\":\"Result\"}]")))
        assertEquals("DOM_DELTA", next.getString("representation"))
        assertEquals(first.getString("snapshot_id"), next.getString("base_snapshot_id"))
        assertEquals(2, next.getJSONArray("controls").getJSONObject(0).getInt("ref"))
        assertTrue(next.getBoolean("text_unchanged")); assertFalse(next.has("links"))
        assertEquals("facts", cache.encode(page(), force = true).getString("text"))
    }
    @Test fun newPageErrorsFullReadAndChatResetCannotReuseBaseline() {
        val cache = BrowserObservations(); cache.encode(page())
        assertEquals("DOM_COMPACT", cache.encode(page("new")).getString("representation"))
        cache.encode(JSONObject().put("error", "STALE_PAGE"))
        assertFalse(cache.encode(page()).has("base_snapshot_id"))
        cache.encode(page(), full = true)
        assertFalse(cache.encode(page()).has("base_snapshot_id"))
        cache.clear(); assertFalse(cache.encode(page()).has("base_snapshot_id"))
    }
    @Test fun fullReadHasIndependentBound() {
        val raw = JSONObject.quote(page(text = "x".repeat(20000)).toString())
        assertEquals(4000, BrowserSnapshot.decode(raw).getString("text").length)
        assertEquals(12000, BrowserSnapshot.decode(raw, full = true).getString("text").length)
    }
}
