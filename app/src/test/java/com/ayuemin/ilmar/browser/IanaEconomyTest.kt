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
class IanaEconomyTest {
    @Test fun ianaSearchOpenReadClickScrollPreservesDiscoveryAndReducesResults() {
        val steps = JSONArray(javaClass.getResource("/browser/iana-dom.json")!!.readText())
        val observations = BrowserObservations()
        var previous: JSONObject? = null
        val report = JSONArray()
        var beforeBytes = 0; var afterBytes = 0
        repeat(steps.length()) { i ->
            val step = steps.getJSONObject(i)
            val op = step.getString("operation")
            fun decode(key: String) = BrowserSnapshot.decode(JSONObject.quote(step.getJSONObject(key).toString()))
            val oldPage = decode("before")
            // Baseline f554c6d response policy: text-only delta, arrays always repeated.
            // Strip fields introduced after that HEAD to reproduce its decoder output.
            oldPage.remove("text_truncated"); oldPage.remove("full_read_available")
            val old = JSONObject(oldPage.toString())
            if (op in setOf("scroll", "click", "type", "select", "back") && previous != null &&
                previous!!.optString("page_id") == oldPage.optString("page_id") && previous!!.optString("url") == oldPage.optString("url") && previous!!.optString("text") == oldPage.optString("text")) {
                old.put("text", "").put("text_unchanged", true).put("notice", "Текст страницы не изменился; используй предыдущий снимок. Элементы управления и ссылки актуальны.")
            }
            previous = oldPage
            val current = observations.encode(decode("after"), force = op in setOf("search", "open", "read"))
            if (op == "read") { assertTrue(current.getString("text").contains("Example Domains")); assertTrue(current.getJSONArray("links").length() > 0) }
            if (op == "scroll") { assertTrue(current.getBoolean("text_unchanged")); assertTrue(current.getBoolean("controls_unchanged")); assertFalse(current.has("controls")) }
            val oldRaw = old.toString(); val newRaw = current.toString()
            beforeBytes += oldRaw.toByteArray().size; afterBytes += newRaw.toByteArray().size
            report.put(JSONObject().put("operation", op).put("before", oldRaw).put("after", newRaw)
                .put("before_chars", oldRaw.length).put("after_chars", newRaw.length))
        }
        assertTrue("$afterBytes >= $beforeBytes", afterBytes < beforeBytes)
        java.io.File("build/reports/iana-economy.json").apply { parentFile.mkdirs(); writeText(report.toString(2)) }
    }
}
