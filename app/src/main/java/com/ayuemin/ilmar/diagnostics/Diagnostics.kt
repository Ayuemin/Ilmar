package com.ayuemin.ilmar.diagnostics

import android.content.Context
import com.ayuemin.ilmar.settings.RuntimeLimits as Limits
import org.json.JSONArray
import org.json.JSONObject

/** Structured local journal; request secrets and authorization headers are excluded/redacted. */
class Diagnostics(context: Context) {
    private val app = context.applicationContext
    private val helper = JournalDatabase(app)
    init { Limits.install(app) }
    fun record(code: String, chatId: Long, details: JSONObject = JSONObject()) = synchronized(lock) {
        val payload = JSONObject().put("time", System.currentTimeMillis()).put("event", code).put("chat", chatId).put("details", Redaction.metadata(details))
        val db = helper.writableDatabase
        db.insertOrThrow("events", null, android.content.ContentValues().apply { put("payload", payload.toString()) })
        db.execSQL("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT ${Limits.get("log_events")})")
    }
    fun failure(code: String, chat: Long, error: Throwable, details: JSONObject = JSONObject()) {
        details.put("exception", error.javaClass.simpleName).put("message", error.message).put("stack", error.stackTraceToString())
        if (error is com.ayuemin.ilmar.model.ProviderFailure) {
            details.put("http_status", error.httpStatus).put("provider_error", error.details)
            error.reportedUsage?.let { details.put("reported_usage", com.ayuemin.ilmar.model.usageJson(it)) }
        }
        record(code, chat, details)
    }
    fun read(errorsOnly: Boolean = false): String = synchronized(lock) {
        helper.readableDatabase.rawQuery("SELECT payload FROM events ORDER BY id DESC LIMIT 200", null).use { rows -> buildString {
            while (rows.moveToNext()) {
                val event = JSONObject(rows.getString(0)); val code = event.getString("event")
                if (errorsOnly && !code.contains("ERROR") && !code.contains("FAILED") && !code.contains("TIMEOUT") && code !in setOf("NO_PROGRESS", "ROUND_LIMIT", "CALL_LIMIT", "ARGUMENT_LIMIT", "RESULT_LIMIT", "TOOLS_DISABLED", "PERMISSION_REVOKED")) continue
                val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date(event.getLong("time")))
                append("$time  $code  chat=${event.optLong("chat")}\n")
                val data = event.optJSONObject("details")?.let { Redaction.metadata(it) }
                if (data != null && data.length() > 0) append(data.toString(2).take(5000)).append('\n')
                append('\n')
            }
        } }
    }
    fun export(output: java.io.OutputStream) = synchronized(lock) {
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine(JSONObject().put("type", "ilmar_diagnostics").put("version", com.ayuemin.ilmar.BuildConfig.VERSION_NAME)
                .put("sha", com.ayuemin.ilmar.BuildConfig.BUILD_SHA).put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("limits", JSONObject(Limits.fields.associate { it.key to Limits.get(it.key) })).put("limit_definitions", Limits.definitions()).put("limits_snapshot", "at_export")
                .put("context_preferences", com.ayuemin.ilmar.memory.ContextPreferences(app).load().diagnosticJson())
                .put("auto_working_window_ceiling_tokens", 32768).put("model_budget_scope", "persistent_task_across_retries").put("embedding_budget_scope", "separate_from_generation_budget").toString())
            val legacy = app.getSharedPreferences("ilmar_diagnostics", Context.MODE_PRIVATE).getString("events", "").orEmpty()
            if (legacy.isNotBlank()) writer.appendLine(JSONObject().put("event", "LEGACY_LOG").put("characters", legacy.length).put("content_omitted", true).toString())
            helper.readableDatabase.rawQuery("SELECT payload FROM events ORDER BY id", null).use { while (it.moveToNext()) {
                val item = JSONObject(it.getString(0))
                item.put("details", Redaction.metadata(item.optJSONObject("details") ?: JSONObject()))
                writer.appendLine(item.toString())
            } }
            com.ayuemin.ilmar.tools.ToolDataStore(app).use { it.exportCalls(writer) }
            com.ayuemin.ilmar.chat.ChatStore(app).use { StateJournalExport.write(it, writer) }
        }
    }
    fun clear() = synchronized(lock) {
        helper.writableDatabase.delete("events", null, null)
        app.getSharedPreferences("ilmar_diagnostics", Context.MODE_PRIVATE).edit().remove("events").apply()
        com.ayuemin.ilmar.tools.ToolDataStore(app).use { it.writableDatabase.delete("calls", null, null) }
    }
    fun deleteChat(chat: Long) = synchronized(lock) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val ids = mutableListOf<Long>()
            db.rawQuery("SELECT id,payload FROM events", null).use { rows -> while (rows.moveToNext()) {
                if (runCatching { JSONObject(rows.getString(1)).optLong("chat", -1) == chat }.getOrDefault(false)) ids += rows.getLong(0)
            } }
            ids.forEach { db.delete("events", "id=?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    private class JournalDatabase(context: Context) : android.database.sqlite.SQLiteOpenHelper(context, "ilmar_journal.db", null, 1) {
        override fun onCreate(db: android.database.sqlite.SQLiteDatabase) { db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY,payload TEXT NOT NULL)") }
        override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    companion object { private val lock = Any() }
}

internal object Redaction {
    // Diagnostic exports are metadata only, including records written by older builds.
    private val bodyFields = setOf("arguments", "args", "result", "results", "messages", "text", "content", "prompt", "goal", "steps", "stack", "message", "provider_error", "url", "headers", "query")
    private val metadataStrings = setOf("stop_reason", "error_code", "argument_fingerprint", "event", "status", "purpose", "model", "provider", "host", "tool", "tools", "tool_name", "tool_calls", "exception", "finish_reason", "route", "protocol", "method", "scope", "unit", "zero_meaning", "label", "key", "output_parameter", "compact_model", "search_route", "configured_search_route", "search_protocol", "search_engine", "status_source", "window_zero_meaning", "output_zero_meaning", "task_token_zero_meaning", "usd_null_meaning", "reserved_cost")
    fun metadata(value: JSONObject): JSONObject = JSONObject().apply {
        value.keys().forEach { key ->
            if (key !in bodyFields) when (val field = value.opt(key)) {
                null, JSONObject.NULL -> put(key, JSONObject.NULL)
                is Number, is Boolean -> put(key, field)
                is JSONObject -> put(key, metadata(field))
                is String -> if (key in metadataStrings || key.endsWith("_id") || key.contains("cost") || key.endsWith("_usd")) put(key, text(field).take(512))
            }
        }
    }
    private val sensitive = Regex("(?i)^(api.?key|authorization|access_token|refresh_token|token|password|secret|cookie|credentials?)$")
    fun text(value: String): String = value
        .replace(Regex("(?i)(bearer\\s+)[^\\s\"<>]+"), "$1[REDACTED]")
        .replace(Regex("(?i)((?:api[_-]?key|access_token|token|password|secret)[=:\\s]+)[^\\s&,\"}]+"), "$1[REDACTED]")
        .replace(Regex("sk-[A-Za-z0-9_-]{8,}"), "[REDACTED]")
    fun json(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().apply { value.keys().forEach { key -> put(key, if (sensitive.containsMatchIn(key)) "[REDACTED]" else json(value.opt(key))) } }
        is JSONArray -> JSONArray().apply { repeat(value.length()) { put(json(value.opt(it))) } }
        is String -> {
            val nested = when (value.trimStart().firstOrNull()) {
                '{' -> runCatching { JSONObject(value) }.getOrNull()
                '[' -> runCatching { JSONArray(value) }.getOrNull()
                '"' -> runCatching { org.json.JSONTokener(value).nextValue() as? String }.getOrNull()?.takeIf { it != value }
                else -> null
            }
            (if (nested != null) json(nested).toString() else text(value)).take(Limits.get("log_chars"))
        }
        else -> value
    }
}
