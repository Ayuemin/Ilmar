package com.ayuemin.ilmar.diagnostics

import com.ayuemin.ilmar.chat.ChatStore
import org.json.JSONArray
import org.json.JSONObject

/** Stream durable state and billing evidence; never export credentials or embedding vectors. */
internal object StateJournalExport {
    fun write(store: ChatStore, writer: java.io.Writer) {
        val queries = listOf(
            "TASK_STATE" to "SELECT id AS task_id,chat_id,anchor_message_id,goal,status,updated_at FROM agent_tasks",
            "TASK_RUN" to "SELECT r.id AS run_id,r.task_id,t.chat_id,r.parent_run_id,r.status,r.started_at,r.finished_at FROM agent_runs r JOIN agent_tasks t ON t.id=r.task_id",
            "TASK_STEP" to "SELECT s.id AS step_id,s.task_id,t.chat_id,s.tool_name,s.arguments,s.status,s.result,s.file_refs,s.execution_id FROM agent_steps s JOIN agent_tasks t ON t.id=s.task_id",
            "TASK_EXECUTION_LINK" to "SELECT l.*,t.chat_id FROM agent_execution_links l JOIN agent_tasks t ON t.id=l.task_id",
            "TASK_MODEL_RESERVATION" to "SELECT c.id AS request_id,c.task_id,c.run_id,c.purpose,c.reserved_tokens,c.reserved_cost,c.status,c.usage,t.chat_id FROM agent_model_calls c JOIN agent_tasks t ON t.id=c.task_id",
            "EMBEDDING_USAGE" to "SELECT id AS request_id,chat_id,purpose,model,inputs,characters,status,usage FROM embedding_requests"
        )
        for ((event, query) in queries) store.readableDatabase.rawQuery(query, null).use { rows ->
            while (rows.moveToNext()) {
                val item = JSONObject().put("event", event)
                rows.columnNames.forEachIndexed { i, key ->
                    val value: Any = when {
                        rows.isNull(i) -> JSONObject.NULL
                        rows.getType(i) == android.database.Cursor.FIELD_TYPE_INTEGER -> rows.getLong(i)
                        key == "file_refs" -> JSONArray(rows.getString(i))
                        key == "usage" -> JSONObject(rows.getString(i))
                        else -> rows.getString(i)
                    }
                    item.put(key, value)
                }
                writer.append(Redaction.metadata(item).toString()).append('\n')
            }
        }
    }
}
