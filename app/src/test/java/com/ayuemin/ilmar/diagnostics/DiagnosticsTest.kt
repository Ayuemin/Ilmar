package com.ayuemin.ilmar.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.attachments.FileExport
import com.ayuemin.ilmar.settings.RuntimeLimits
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DiagnosticsTest {
    @Test fun exportContainsStructuredFailuresAndUsageButRedactsKeysAndIncludesLegacyLog() {
        val app = ApplicationProvider.getApplicationContext<Context>(); val log = Diagnostics(app); log.clear()
        app.getSharedPreferences("ilmar_diagnostics", 0).edit().putString("events", "old event").commit()
        log.record("MODEL_RESPONSE", 42, JSONObject().put("input_tokens", 123).put("api_key", "private-key")
            .put("headers", JSONObject().put("Authorization", "Bearer private-value")))
        log.failure("TOOL_ERROR", 42, IllegalStateException("test failure"))
        val output = java.io.ByteArrayOutputStream(); log.export(output)
        val text = output.toString("UTF-8")
        assertFalse(text.contains("test failure")); assertFalse(text.contains("old event")); assertTrue(text.contains("123")); assertTrue(text.contains("IllegalStateException"))
        assertFalse(text.contains("private-key")); assertFalse(text.contains("private-value"))
        text.lineSequence().filter { it.isNotBlank() }.forEach { JSONObject(it) }
        assertTrue(log.read(errorsOnly = true).contains("TOOL_ERROR")); assertFalse(log.read(errorsOnly = true).contains("MODEL_RESPONSE"))
        log.clear(); assertEquals("", log.read())
    }
    @Test fun exportIntentPreservesMarkdownPythonAndCyrillicNamesWithoutPlainTextSuffix() {
        for (name in listOf("demo_tools.md", "Отчёт.py", "Схема.kt", "данные.jsonl")) {
            val intent = FileExport.intent(name, "text/plain")
            assertEquals(name, intent.getStringExtra(android.content.Intent.EXTRA_TITLE))
            assertEquals("application/octet-stream", intent.type)
        }
    }
    @Test fun globalLimitsPersistAndOptionalBudgetsCanBeDisabled() {
        val app = ApplicationProvider.getApplicationContext<Context>(); RuntimeLimits.install(app); RuntimeLimits.reset()
        val values = RuntimeLimits.fields.associate { it.key to RuntimeLimits.get(it.key) } + mapOf("rounds" to 0, "calls" to 0, "storage_mib" to 0, "create_kib" to 32000)
        RuntimeLimits.save(values); RuntimeLimits.install(app)
        assertEquals(0, RuntimeLimits.get("rounds")); assertEquals(32000 * 1024, RuntimeLimits.bytes("create_kib"))
        assertTrue(runCatching { RuntimeLimits.save(values + ("fetch_kib" to 0)) }.isFailure)
        RuntimeLimits.reset()
    }
}
