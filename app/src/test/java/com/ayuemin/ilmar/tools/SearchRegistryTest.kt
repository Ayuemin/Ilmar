package com.ayuemin.ilmar.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.model.MemorySecrets
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SearchRegistryTest {
    private lateinit var app: Context
    @Before fun setup() { app = ApplicationProvider.getApplicationContext(); app.getSharedPreferences("ilmar_search", 0).edit().clear().commit() }
    @Test fun legacyBraveKeyWorksAndCustomProfileSecretsDoNotEnterPreferences() {
        val secrets = MemorySecrets(); secrets.saveApiKey("legacy-value", NativeTools.SEARCH_KEY)
        val registry = SearchRegistry(app, secrets)
        assertTrue(registry.ready()); assertEquals("legacy-value", registry.target().key)
        val profile = SearchProfile.preset("Custom").copy(endpoint = "https://search.example/api", resultsPath = "/data/items")
        registry.save(profile, "private-key", """{"Authorization":"Bearer {api_key}","X-Secret":"private-header"}""")
        assertEquals(profile, registry.target().profile)
        val raw = app.getSharedPreferences("ilmar_search", 0).all.toString()
        assertFalse(raw.contains("private-key")); assertFalse(raw.contains("private-header"))
        assertEquals("private-header", registry.target().headers.getString("X-Secret"))
        registry.save(profile.copy(name = "Renamed"), "", null)
        assertEquals("private-key", registry.target().key)
        registry.select("brave-legacy"); assertEquals("legacy-value", registry.target().key)
        registry.delete(profile.id); assertFalse(secrets.hasApiKey("search-key:${profile.id}"))
    }
    @Test fun customPostJsonAndNestedMappingHandleQuotesWithoutInjection() = runTest {
        val query = "quoted \"query\" & unicode: Привет"
        val profile = SearchProfile.preset("Custom").copy(endpoint = "https://search.example:8443/api", method = "POST",
            body = """{"search":{"q":"{query}"},"limit":"{count}"}""", resultsPath = "data.items", titlePath = "document.title", urlPath = "link", snippetPath = "summary")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
            val json = JSONObject(buffer.readUtf8())
            assertEquals(query, json.getJSONObject("search").getString("q")); assertTrue(json.get("limit") is Int)
            assertEquals("Bearer private-value", request.header("Authorization"))
            Response.Builder().request(request).code(200).message("OK").protocol(Protocol.HTTP_1_1).body(
                """{"data":{"items":[{"document":{"title":"Title"},"link":"https://result.example/item","summary":"<b>Snippet</b>"}]}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = JSONObject(WebTools(client).search(query, SearchTarget(profile, "private-value", JSONObject("""{"Authorization":"Bearer {api_key}"}"""))))
        assertEquals("Snippet", result.getJSONArray("results").getJSONObject(0).getString("snippet"))
    }
    @Test fun querySecretsAreEncodedAndWrongMappingFailsClearly() = runTest {
        val profile = SearchProfile.preset("SerpAPI")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("a&b?c", chain.request().url.queryParameter("api_key"))
            assertEquals("x&y", chain.request().url.queryParameter("q"))
            Response.Builder().request(chain.request()).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val failure = runCatching { WebTools(client).search("x&y", SearchTarget(profile, "a&b?c", JSONObject())) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("organic_results"))
    }
}
