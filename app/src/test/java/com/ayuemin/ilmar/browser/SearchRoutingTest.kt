package com.ayuemin.ilmar.browser

import com.ayuemin.ilmar.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SearchRoutingTest {
    @Test fun routesDifferentProvidersAndNeverInventsUnsupportedProtocol() {
        val generic = "https://models.example/v1"
        assertNull(ProviderSearch.resolve(ProviderSearchProtocol.AUTO, generic, ModelCapabilities()))
        assertEquals(ProviderSearchProtocol.CHAT_COMPLETIONS, ProviderSearch.resolve(ProviderSearchProtocol.AUTO, generic, ModelCapabilities(setOf("web_search_options"))))
        assertEquals(ProviderSearchProtocol.NATIVE, ProviderSearch.resolve(ProviderSearchProtocol.AUTO, "https://api.perplexity.ai", ModelCapabilities()))
        assertEquals(ProviderSearchProtocol.OPENROUTER, ProviderSearch.resolve(ProviderSearchProtocol.AUTO, "https://openrouter.ai/api/v1", ModelCapabilities()))
        assertEquals(ProviderSearchProtocol.CHAT_COMPLETIONS, ProviderSearch.resolve(ProviderSearchProtocol.CHAT_COMPLETIONS, generic, ModelCapabilities()))
        assertEquals(SearchRoute.BROWSER, SearchRouting.resolve(true, SearchSource.AUTO, false, true))
        assertEquals(SearchRoute.PROVIDER, SearchRouting.resolve(true, SearchSource.AUTO, true, false))
        assertEquals(SearchRoute.BROWSER, SearchRouting.resolve(true, SearchSource.BROWSER, true, true))
        assertEquals(SearchRoute.OFF, SearchRouting.resolve(false, SearchSource.PROVIDER, false, false))
        assertTrue(runCatching { SearchRouting.resolve(true, SearchSource.PROVIDER, false, true) }.isFailure)
        assertTrue(runCatching { SearchRouting.resolve(true, SearchSource.BROWSER, true, false) }.isFailure)
    }
    @Test fun payloadAdaptersAreSeparateAndAbsentByDefaultAndForCompact() {
        fun payload(mode: String?) = GenerationPayload.build("model", emptyList(), GenerationOptions(webSearch = mode))
        assertFalse(payload(null).has("plugins")); assertFalse(payload(null).has("web_search_options"))
        assertEquals("web", payload("OPENROUTER").getJSONArray("plugins").getJSONObject(0).getString("id"))
        assertFalse(payload("CHAT_COMPLETIONS").has("plugins"))
        assertEquals("low", payload("CHAT_COMPLETIONS").getJSONObject("web_search_options").getString("search_context_size"))
        assertFalse(payload("NATIVE").has("plugins")); assertFalse(payload("NATIVE").has("web_search_options"))
    }
    @Test fun providerProtocolPersistsPerConnectionAndSearchDoesNotEnableAgent() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = ProviderSearchPreferences(app)
        config.save("a", ProviderSearchProtocol.CHAT_COMPLETIONS); config.save("b", ProviderSearchProtocol.NATIVE)
        assertEquals(ProviderSearchProtocol.CHAT_COMPLETIONS, ProviderSearchPreferences(app).selected("a"))
        assertEquals(ProviderSearchProtocol.NATIVE, ProviderSearchPreferences(app).selected("b"))
        val modes = com.ayuemin.ilmar.chat.ChatModePreferences(app)
        modes.setEnabled(91, "Агент", false); modes.setEnabled(91, "Поиск", true); modes.setChoice(91, "search_source", "browser")
        assertFalse(modes.enabled(91, "Агент"))
        assertEquals(4, BrowserTools.forChat(91, modes, false, true).size)
    }
    @Test fun snapshotsRejectInvalidDataAndBoundUntrustedContent() {
        val page = JSONObject().put("url", "https://example.com/").put("title", "Example").put("text", "x".repeat(15000))
            .put("links", org.json.JSONArray("""[{"ref":1,"title":"bad","url":"file:///etc/passwd"},{"ref":2,"title":"ok","url":"https://example.com/page"}]"""))
        val decoded = BrowserSnapshot.decode(JSONObject.quote(page.toString()))
        assertEquals(12000, decoded.getString("text").length); assertTrue(decoded.getBoolean("untrusted"))
        assertEquals(1, decoded.getJSONArray("links").length())
        assertTrue(runCatching { BrowserSnapshot.decode("null") }.isFailure)
        listOf("file:///etc/passwd", "content://local", "javascript:alert(1)", "https://user:secret@example.com/").forEach { assertFalse(BrowserAddress.valid(it)) }
        assertTrue(BrowserAddress.search("a & b").contains("a+%26+b"))
    }
}
