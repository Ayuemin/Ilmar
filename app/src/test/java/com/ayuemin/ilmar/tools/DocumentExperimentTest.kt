package com.ayuemin.ilmar.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.agent.*
import com.ayuemin.ilmar.browser.SearchRoute
import com.ayuemin.ilmar.documents.*
import com.ayuemin.ilmar.model.ToolSchema
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DocumentExperimentTest {
    @Test fun settingsAreIsolatedOptInAndRoundTripAllSwitches() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        app.getSharedPreferences("ilmar_document_experiment", 0).edit().clear().commit()
        val permissions = ToolPermissionBroker(app); permissions.setGranted("fetch_url", false)
        val prefs = DocumentExperimentPreferences(app); assertEquals(DocumentMode.LEGACY, prefs.load().mode)
        val config = DocumentExperiment(DocumentMode.COMPARE, RetrievalOptions(false, false, false, true, false), 10, ExperimentalBrowser.DISABLED)
        prefs.save(config); assertEquals(config, DocumentExperimentPreferences(app).load()); assertFalse(permissions.granted("fetch_url"))
        prefs.save(DocumentExperiment())
    }
    @Test fun restrictedBrowserCannotBypassConsentOrExistingRevocationAndDisabledExposesNothing() = runTest {
        var allowed = true; var calls = 0; var approvals = 0
        fun fake(name: String): BoundTool = BoundTool(ToolSchema(name, "fixture", JSONObject().put("type", "object")
            .put("properties", JSONObject().put("url", JSONObject().put("type", "string"))).put("required", JSONArray(listOf("url")))),
            beforeExecute = { check(allowed) { "revoked" } }) { args -> assertFalse(args.has("reason")); calls++; "{}" }
        val originals = listOf(fake("browser_open"), fake("browser_read"), fake("browser_search"), fake("browser_batch"))
        assertTrue(ExperimentalBrowserGate.tools(originals, ExperimentalBrowser.DISABLED) { _, _ -> true }.isEmpty())
        assertEquals(SearchRoute.OFF, ExperimentalBrowserGate.route(SearchRoute.BROWSER, ExperimentalBrowser.LIMITED))
        assertEquals(SearchRoute.PROVIDER, ExperimentalBrowserGate.route(SearchRoute.PROVIDER, ExperimentalBrowser.DISABLED))
        val limited = ExperimentalBrowserGate.tools(originals, ExperimentalBrowser.LIMITED) { _, _ -> approvals++; true }
        assertEquals(2, limited.size)
        assertTrue(runCatching { limited.last().execute(JSONObject()) }.isFailure)
        val args = JSONObject().put("url", "https://example.com").put("reason", "javascript")
        val open = limited.first(); open.beforeExecute(args); open.execute(args)
        assertEquals(1, approvals); assertEquals(1, calls)
        allowed = false
        assertTrue(runCatching { limited.last().execute(JSONObject()) }.isFailure); assertEquals(1, calls)
        allowed = true
        val denied = ExperimentalBrowserGate.tools(originals, ExperimentalBrowser.LIMITED) { _, _ -> false }
        assertTrue(runCatching { denied.first().execute(args) }.isFailure); assertEquals(1, calls)
        val revoked = ExperimentalBrowserGate.tools(originals, ExperimentalBrowser.LIMITED) { _, _ -> allowed = false; true }
        assertTrue(runCatching { revoked.first().execute(args) }.isFailure); assertEquals(1, calls)
    }
}
