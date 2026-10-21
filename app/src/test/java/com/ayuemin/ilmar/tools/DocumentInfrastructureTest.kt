package com.ayuemin.ilmar.tools

import com.ayuemin.ilmar.documents.TemporaryDocuments
import com.ayuemin.ilmar.memory.ContextEstimate
import com.ayuemin.ilmar.settings.RuntimeLimits
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DocumentInfrastructureTest {
    @Test fun completeSourceAndLegacyProjectionUseOneBoundedFetch() = runTest {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        RuntimeLimits.install(app); RuntimeLimits.reset()
        val text = "Вводный текст. ".repeat(6000) + "Late answer: version 42 is not supported."
        val html = "<main><h1>Specification</h1><p>$text</p></main>"
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(html.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }.build()
        val full = WebTools(client).fetchDocument("https://example.com/spec")
        val legacy = JSONObject(full.legacyResult(RuntimeLimits.get("excerpt_chars")))
        assertEquals(html, full.source)
        assertTrue(full.legacyText.endsWith("version 42 is not supported."))
        assertTrue(legacy.getBoolean("truncated")); assertFalse(legacy.getString("text").contains("Late answer"))
        TemporaryDocuments(1, "run").use { store -> assertEquals(full, store.get(store.put(full).id).fetched) }
        assertEquals(1, requests)
        val report = JSONObject().put("baseline_sha", "a44e94c8a82b5b10c7a0a6107e04766957afcac0")
            .put("fixture", "synthetic RU long page with late EN answer; no provider API")
            .put("source_utf8_bytes", html.toByteArray().size).put("full_text_chars", full.legacyText.length)
            .put("legacy_result_chars", legacy.toString().length).put("legacy_result_estimated_tokens", ContextEstimate.text(legacy.toString()))
            .put("token_method", "existing ContextEstimate; not tokenizer or actual usage")
            .put("fetch_calls", requests).put("browser_calls", 0).put("model_calls", 0)
            .put("late_answer_present_in_legacy", false)
        java.io.File("build/reports/fetch-baseline.json").apply { parentFile?.mkdirs(); writeText(report.toString(2)) }
        RuntimeLimits.reset()
    }
    @Test fun scopesExpiryQuotasAndCloseFailClosedWithoutDisk() {
        var now = 10L
        val doc = FetchedDocument("https://example.com", "text/plain", "original", "original")
        val a = TemporaryDocuments(1, "a", maxDocuments = 1, ttlMillis = 20, clock = { now })
        val b = TemporaryDocuments(2, "b")
        val id = a.put(doc).id
        assertTrue(runCatching { b.get(id) }.isFailure)
        assertTrue(runCatching { a.get("../../file") }.isFailure)
        assertTrue(runCatching { a.put(doc) }.isFailure)
        now = 30
        assertTrue(runCatching { a.get(id) }.isFailure)
        assertEquals(0, a.all().size)
        a.put(doc); a.close(); a.close()
        assertTrue(runCatching { a.put(doc) }.isFailure)
        assertTrue(runCatching { a.all() }.isFailure)
        TemporaryDocuments(1, "tiny", maxBytes = doc.retainedBytes - 1).use { tiny ->
            assertTrue(runCatching { tiny.put(doc) }.isFailure); assertTrue(tiny.all().isEmpty())
        }
        b.close()
    }
    @Test fun unknownLengthOversizeCannotProduceACompleteDocument() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = object : ResponseBody() {
                override fun contentType() = "text/plain".toMediaType()
                override fun contentLength() = -1L
                override fun source(): okio.BufferedSource = okio.Buffer().write(ByteArray(RuntimeLimits.bytes("fetch_kib") + 1))
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        }.build()
        TemporaryDocuments(1, "bounded").use { store ->
            assertTrue(runCatching { store.put(WebTools(client).fetchDocument("https://example.com")) }.isFailure)
            assertTrue(store.all().isEmpty())
        }
    }
}
