package com.ayuemin.ilmar.tools

import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
class WebToolsTest {
    @Test fun privateLiteralsHttpAndCredentialsAreRejectedBeforeNetwork() = runTest {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("ok".toResponseBody("text/plain".toMediaType())).build()
        }.build()
        val tools = WebTools(client)
        listOf("http://public.example", "https://127.0.0.1", "https://[::1]", "https://user:password@public.example").forEach {
            assertTrue(it, runCatching { tools.fetch(it) }.isFailure)
        }
        assertEquals(0, requests)
    }
    @Test fun dnsRejectsLocalhostBeforeOpeningSocket() {
        assertTrue(runCatching { PublicAddressPolicy.dns.lookup("localhost") }.isFailure)
    }
    @Test fun fetchExtractsHtmlAndSearchReturnsSnippetsAndUrls() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val search = chain.request().url.host == "api.search.brave.com"
            if (search) assertEquals("test-key", chain.request().header("X-Subscription-Token"))
            val body = if (search) """{"web":{"results":[{"title":"Title","url":"https://example.com/result","description":"<b>Snippet</b> text"}]}}"""
                else "<html><nav>Menu</nav><main>Hello<script>bad()</script> world</main><footer>Footer</footer></html>"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody((if (search) "application/json" else "text/html").toMediaType())).build()
        }.build()
        val tools = WebTools(client)
        val fetched = JSONObject(tools.fetch("https://example.com/page"))
        assertEquals("Hello world", fetched.getString("text")); assertTrue(fetched.getBoolean("untrusted"))
        val found = JSONObject(tools.search("query", "test-key")).getJSONArray("results").getJSONObject(0)
        assertEquals("Snippet text", found.getString("snippet")); assertEquals("https://example.com/result", found.getString("url"))
    }
    @Test fun redirectsAndOversizedBodiesAreRejected() = runTest {
        for (code in listOf(302, 200)) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Test")
                    .header("Location", "https://127.0.0.1/")
                    .body("x".repeat(com.ayuemin.ilmar.settings.RuntimeLimits.bytes("fetch_kib") + 1).toResponseBody("text/plain".toMediaType())).build()
            }.build()
            assertTrue(runCatching { WebTools(client).fetch("https://example.com") }.isFailure)
        }
    }

}
