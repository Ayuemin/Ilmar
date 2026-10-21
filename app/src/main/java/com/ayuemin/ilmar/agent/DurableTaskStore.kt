package com.ayuemin.ilmar.agent

import android.database.sqlite.SQLiteDatabase
import com.ayuemin.ilmar.chat.ChatMessage
import com.ayuemin.ilmar.chat.ChatStore
import com.ayuemin.ilmar.memory.ContextEstimate
import com.ayuemin.ilmar.memory.ContinuationPolicy
import com.ayuemin.ilmar.model.TokenUsage
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.UUID

internal data class TaskSession(val taskId: String, val runId: String, val parentRunId: String?, val chat: Long) {
    fun json() = JSONObject().put("task_id", taskId).put("run_id", runId).put("parent_run_id", parentRunId ?: JSONObject.NULL)
}
internal data class TaskSpend(val tokens: Long, val cost: BigDecimal, val unknownCost: Boolean)

/** Factual execution state, independent of model capsules. No external exactly-once claim. */
internal class DurableTaskStore(private val store: ChatStore) {
    private val db get() = store.writableDatabase
    private fun <T> transaction(action: () -> T): T {
        db.beginTransaction()
        try { return action().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
    fun begin(chat: Long, history: List<ChatMessage>): TaskSession = transaction {
        check(db.rawQuery("SELECT 1 FROM chats WHERE id=?", arrayOf(chat.toString())).use { it.moveToFirst() }) { "Чат удалён" }
        val user = history.lastOrNull { it.fromUser }
        val prior = db.rawQuery("SELECT id,status FROM agent_tasks WHERE chat_id=? ORDER BY updated_at DESC,rowid DESC LIMIT 1", arrayOf(chat.toString())).use {
            if (it.moveToFirst()) it.getString(0) to it.getString(1) else null
        }
        val anchor = user?.id ?: 0L
        val linked = db.rawQuery("SELECT task_id FROM agent_task_messages WHERE chat_id=? AND message_id=?", arrayOf(chat.toString(), anchor.toString())).use { if (it.moveToFirst()) it.getString(0) else null }
        val existing = linked ?: if (prior != null && (ContinuationPolicy.isControl(user?.text.orEmpty()) || ContinuationPolicy.isBriefReply(user?.text.orEmpty()) || prior.second == "AWAITING_USER")) prior.first
            else db.rawQuery("SELECT id FROM agent_tasks WHERE chat_id=? AND anchor_message_id=?", arrayOf(chat.toString(), anchor.toString())).use { if (it.moveToFirst()) it.getString(0) else null }
        val task = existing ?: UUID.randomUUID().toString().also {
            db.execSQL("INSERT INTO agent_tasks VALUES(?,?,?,?,?,?)", arrayOf(it, chat, anchor, user?.text?.take(2000).orEmpty(), "RUNNING", System.currentTimeMillis()))
        }
        db.execSQL("INSERT OR IGNORE INTO agent_task_messages VALUES(?,?,?)", arrayOf(chat, anchor, task))
        check(db.rawQuery("SELECT 1 FROM agent_runs WHERE task_id=? AND status='RUNNING'", arrayOf(task)).use { !it.moveToFirst() }) { "Задача уже выполняется" }
        val parent = db.rawQuery("SELECT id FROM agent_runs WHERE task_id=? ORDER BY started_at DESC,rowid DESC LIMIT 1", arrayOf(task)).use { if (it.moveToFirst()) it.getString(0) else null }
        val run = UUID.randomUUID().toString()
        db.execSQL("INSERT INTO agent_runs VALUES(?,?,?,?,?,?)", arrayOf(run, task, parent, "RUNNING", System.currentTimeMillis(), null))
        db.execSQL("UPDATE agent_tasks SET status='RUNNING',updated_at=? WHERE id=?", arrayOf(System.currentTimeMillis(), task))
        TaskSession(task, run, parent, chat)
    }
    fun finish(session: TaskSession, status: String) = transaction {
        val active = db.rawQuery("SELECT 1 FROM agent_runs WHERE id=? AND status='RUNNING'", arrayOf(session.runId)).use { it.moveToFirst() }
        if (active) {
            db.execSQL("UPDATE agent_runs SET status=?,finished_at=? WHERE id=?", arrayOf(status, System.currentTimeMillis(), session.runId))
            db.execSQL("UPDATE agent_tasks SET status=?,updated_at=? WHERE id=?", arrayOf(if (status == "COMPLETED") "ANSWERED" else status, System.currentTimeMillis(), session.taskId))
        }
    }
    fun interruptOpen() = transaction {
        db.execSQL("UPDATE agent_runs SET status='INTERRUPTED',finished_at=? WHERE status='RUNNING'", arrayOf(System.currentTimeMillis()))
        db.execSQL("UPDATE agent_tasks SET status='INTERRUPTED' WHERE status='RUNNING'")
        db.execSQL("UPDATE agent_model_calls SET status='INTERRUPTED' WHERE status='RUNNING'")
    }
    fun checkpoint(session: TaskSession, trace: String, messages: List<JSONObject>) = transaction {
        check(db.rawQuery("SELECT 1 FROM agent_tasks WHERE id=? AND chat_id=?", arrayOf(session.taskId, session.chat.toString())).use { it.moveToFirst() }) { "Задача удалена" }
        store.checkpointExecutions(session.chat, trace, messages)
        val calls = messages.first().optJSONArray("tool_calls") ?: return@transaction
        for (i in 0 until calls.length()) {
            val call = calls.getJSONObject(i); val execution = "$trace:${call.getString("id")}"; val fn = call.getJSONObject("function")
            val status = store.executionStatus(session.chat, execution) ?: continue
            val key = operation(fn.getString("name"), fn.getString("arguments"))
            val step = db.rawQuery("SELECT id FROM agent_steps WHERE task_id=? AND operation_key=?", arrayOf(session.taskId, key)).use { if (it.moveToFirst()) it.getString(0) else UUID.randomUUID().toString() }
            val result = db.rawQuery("SELECT result FROM task_executions WHERE execution_id=?", arrayOf(execution)).use { if (it.moveToFirst()) it.getString(0) else null }
            val refs = JSONArray()
            if (status == "SUCCEEDED" && fn.getString("name") == "create_file") runCatching { JSONObject(result!!).optString("file_id").takeIf { it.isNotBlank() }?.let { refs.put(it) } }
            if (status == "SUCCEEDED" && fn.getString("name") == "browser_download") runCatching { JSONObject(result!!).optString("download_id").takeIf { it.isNotBlank() }?.let { refs.put(it) } }
            db.execSQL("INSERT OR IGNORE INTO agent_steps VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf(step, session.taskId, key, fn.getString("name"), fn.getString("arguments"), status, result, refs.toString(), execution, System.currentTimeMillis()))
            db.execSQL("UPDATE agent_steps SET status=?,result=?,file_refs=?,execution_id=?,updated_at=? WHERE id=? AND status<>'SUCCEEDED'", arrayOf(status, result, refs.toString(), execution, System.currentTimeMillis(), step))
            db.execSQL("INSERT OR IGNORE INTO agent_execution_links VALUES(?,?,?,?)", arrayOf(execution, session.taskId, session.runId, step))
        }
    }
    fun confirmed(session: TaskSession, name: String, arguments: String): ConfirmedToolResult? {
        if (ToolOperationPolicy.safeToRepeat(name, arguments)) return null
        return db.rawQuery("SELECT execution_id,result FROM agent_steps WHERE task_id=? AND operation_key=? AND status='SUCCEEDED'", arrayOf(session.taskId, operation(name, arguments))).use {
            if (it.moveToFirst() && !it.isNull(1)) ConfirmedToolResult(it.getString(0), it.getString(1)) else null
        }
    }
    fun reserve(session: TaskSession, id: String, tokens: Long, cost: BigDecimal?, purpose: String) {
        check(db.rawQuery("SELECT 1 FROM agent_tasks WHERE id=?", arrayOf(session.taskId)).use { it.moveToFirst() }) { "Задача удалена" }
        db.execSQL("INSERT INTO agent_model_calls VALUES(?,?,?,?,?,?,?,NULL)", arrayOf(id, session.taskId, session.runId, purpose, tokens, cost?.toPlainString(), "RUNNING"))
    }
    fun completeModel(id: String, status: String, usage: TokenUsage?) {
        val json = usage?.let { JSONObject().put("input", it.input).put("output", it.output).put("total", it.total).put("cost", it.costUsd) }
        db.execSQL("UPDATE agent_model_calls SET status=?,usage=? WHERE id=?", arrayOf(status, json?.toString(), id))
    }
    fun spent(task: String): TaskSpend {
        var tokens = 0L; var cost = BigDecimal.ZERO; var unknown = false
        db.rawQuery("SELECT c.reserved_tokens,c.reserved_cost,c.usage,u.input_tokens,u.output_tokens,u.total_tokens,u.cost_usd FROM agent_model_calls c LEFT JOIN request_usage u ON c.id=u.id WHERE c.task_id=?", arrayOf(task)).use { rows ->
            while (rows.moveToNext()) {
                val usage = rows.getString(2)?.let { JSONObject(it) }
                val input = usage?.optString("input")?.toLongOrNull() ?: if (rows.isNull(3)) null else rows.getLong(3)
                val output = usage?.optString("output")?.toLongOrNull() ?: if (rows.isNull(4)) null else rows.getLong(4)
                val total = usage?.optString("total")?.toLongOrNull() ?: if (rows.isNull(5)) null else rows.getLong(5)
                tokens += total ?: if (input != null && output != null) input + output else rows.getLong(0)
                val amount = usage?.optString("cost")?.toBigDecimalOrNull() ?: rows.getString(6)?.toBigDecimalOrNull() ?: rows.getString(1)?.toBigDecimalOrNull()
                if (amount == null) unknown = true else cost += amount
            }
        }
        return TaskSpend(tokens, cost, unknown)
    }
    fun snapshot(chat: Long): JSONObject? {
        val task = db.rawQuery("SELECT id,goal,status FROM agent_tasks WHERE chat_id=? ORDER BY updated_at DESC,rowid DESC LIMIT 1", arrayOf(chat.toString())).use {
            if (it.moveToFirst()) JSONObject().put("task_id", it.getString(0)).put("goal", it.getString(1)).put("status", it.getString(2)) else null
        } ?: return null
        val steps = JSONArray()
        db.rawQuery("SELECT id,tool_name,status,result,file_refs,execution_id FROM agent_steps WHERE task_id=? ORDER BY updated_at DESC,rowid DESC LIMIT 16", arrayOf(task.getString("task_id"))).use { rows ->
            while (rows.moveToNext()) {
                val raw = rows.getString(3)
                val result = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
                val summary = result?.optString("name")?.takeIf { it.isNotBlank() }?.let { it + if (result.has("version") && !result.isNull("version")) " · v${result.optInt("version")}" else "" }
                    ?: result?.opt("result")?.toString() ?: raw?.takeUnless { it.startsWith("{") }?.take(160).orEmpty()
                steps.put(JSONObject().put("step_id", rows.getString(0)).put("tool", rows.getString(1)).put("status", rows.getString(2))
                .put("result", rows.getString(3)?.take(240) ?: JSONObject.NULL).put("file_refs", JSONArray(rows.getString(4))).put("summary", summary).put("trace_id", rows.getString(5).substringBefore(':')))
            }
        }
        return task.put("steps", steps)
    }
    fun note(session: TaskSession, maxTokens: Int = 1024): String {
        val state = snapshot(session.chat) ?: return ""
        if (state.getString("task_id") != session.taskId || state.getJSONArray("steps").length() == 0) return ""
        state.put("goal", state.optString("goal").take(160))
        val steps = state.getJSONArray("steps")
        repeat(steps.length()) { i ->
            val step = steps.getJSONObject(i); step.remove("step_id")
            if (step.optString("status") == "SUCCEEDED") step.remove("result")
        }
        state.put("shown_steps", steps.length())
        while (steps.length() > 1 && ContextEstimate.text(state.toString()) > maxTokens) steps.remove(steps.length() - 1)
        state.put("shown_steps", steps.length())
        return "\nФактическое состояние задачи из SQLite (отдельно от сводки модели):\n${state}\nSUCCEEDED подтверждён исполнителем; одинаковое побочное действие в этой задаче использует сохранённый результат. UNKNOWN не повторять без проверки. ANSWERED означает завершение ответа, а не доказанное достижение цели.\n"
    }
    companion object {
        private fun operation(name: String, args: String): String {
            val identity = if (name == LocalToolExecutor.STEP) JSONObject(args).getString("identity") else ToolOperationPolicy.canonical(args)
            return ContextEstimate.hash(name + "\n" + identity)
        }
        fun create(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS agent_tasks(id TEXT PRIMARY KEY,chat_id INTEGER NOT NULL,anchor_message_id INTEGER NOT NULL,goal TEXT NOT NULL,status TEXT NOT NULL,updated_at INTEGER NOT NULL,UNIQUE(chat_id,anchor_message_id))")
            db.execSQL("CREATE TABLE IF NOT EXISTS agent_task_messages(chat_id INTEGER NOT NULL,message_id INTEGER NOT NULL,task_id TEXT NOT NULL,PRIMARY KEY(chat_id,message_id))")
            db.execSQL("CREATE TABLE IF NOT EXISTS agent_runs(id TEXT PRIMARY KEY,task_id TEXT NOT NULL,parent_run_id TEXT,status TEXT NOT NULL,started_at INTEGER NOT NULL,finished_at INTEGER)")
            db.execSQL("CREATE TABLE IF NOT EXISTS agent_steps(id TEXT PRIMARY KEY,task_id TEXT NOT NULL,operation_key TEXT NOT NULL,tool_name TEXT NOT NULL,arguments TEXT NOT NULL,status TEXT NOT NULL,result TEXT,file_refs TEXT NOT NULL,execution_id TEXT NOT NULL,updated_at INTEGER NOT NULL,UNIQUE(task_id,operation_key))")
            db.execSQL("CREATE TABLE IF NOT EXISTS agent_execution_links(execution_id TEXT PRIMARY KEY,task_id TEXT NOT NULL,run_id TEXT NOT NULL,step_id TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS agent_model_calls(id TEXT PRIMARY KEY,task_id TEXT NOT NULL,run_id TEXT NOT NULL,purpose TEXT NOT NULL,reserved_tokens INTEGER NOT NULL,reserved_cost TEXT,status TEXT NOT NULL,usage TEXT)")
            createIndexes(db)
        }
        fun createIndexes(db: SQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS agent_runs_task ON agent_runs(task_id,started_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS agent_links_task ON agent_execution_links(task_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS agent_calls_task ON agent_model_calls(task_id)")
        }
        fun clear(db: SQLiteDatabase, chat: Long) {
            listOf("agent_model_calls", "agent_execution_links", "agent_steps", "agent_runs", "agent_task_messages").forEach { table -> db.execSQL("DELETE FROM $table WHERE task_id IN (SELECT id FROM agent_tasks WHERE chat_id=?)", arrayOf(chat)) }
            db.execSQL("DELETE FROM agent_tasks WHERE chat_id=?", arrayOf(chat))
        }
    }
}
