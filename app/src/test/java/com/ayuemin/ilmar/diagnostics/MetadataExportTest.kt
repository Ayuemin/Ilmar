package com.ayuemin.ilmar.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.tools.ToolDataStore
import org.json.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MetadataExportTest {
    @Test fun nestedJsonArraysAndEscapedJsonAreStructurallyRedacted() {
        val secret = "synthetic-private-password"
        val nested = JSONObject().put("password", secret)
        for (value in listOf(nested, JSONArray().put(nested), nested.toString(), JSONObject.quote(nested.toString())))
            assertFalse(Redaction.json(value).toString().contains(secret))
    }
    @Test fun ordinaryLogDatabaseAndExportContainNoPromptOrToolBodyEvenFromLegacyRows() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val log = Diagnostics(app); log.clear()
        val sentinel = "synthetic-document-private-marker"
        log.record("MODEL_REQUEST", 42, JSONObject().put("request_id","req-42").put("prompt",sentinel).put("messages",JSONArray().put(sentinel)).put("estimated_input",123))
        ToolDataStore(app).use { data ->
            data.log(42, "read_file", JSONObject().put("text",sentinel), JSONObject().put("password",sentinel).toString(), 2, "completed")
            data.readableDatabase.rawQuery("SELECT args,result FROM calls", null).use { rows -> while(rows.moveToNext()) { assertFalse(rows.getString(0).contains(sentinel)); assertFalse(rows.getString(1).contains(sentinel)) } }
            data.writableDatabase.execSQL("INSERT INTO calls(chat,tool,args,result,latency,status) VALUES(42,'legacy',?,?,1,'completed')", arrayOf(sentinel,sentinel))
        }
        val output = java.io.ByteArrayOutputStream(); log.export(output)
        val text = output.toString("UTF-8")
        assertFalse(text.contains(sentinel)); assertTrue(text.contains("req-42")); assertTrue(text.contains("123"))
        log.deleteChat(42); assertFalse(log.read().contains("req-42"))
        log.clear()
    }
}
