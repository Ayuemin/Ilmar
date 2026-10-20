package com.ayuemin.ilmar.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ayuemin.ilmar.memory.MemorySnapshot
import org.json.JSONObject

/** Live SQLite facts; never inferred from a model capsule. */
@Composable
internal fun TaskStateCard(snapshot: MemorySnapshot?) {
    snapshot?.task?.let { task ->
        Text(task.optString("goal"), style = MaterialTheme.typography.bodyMedium)
        Text(when (task.optString("status")) {
            "RUNNING" -> "Задача выполняется"
            "ANSWERED" -> "Ответ завершён; результаты операций показаны ниже"
            "AWAITING_USER" -> "Ожидается ответ пользователя"
            "PAUSED", "INTERRUPTED" -> "Задача прервана; подтверждённые результаты сохранены"
            else -> "Последняя попытка не завершилась успешно"
        }, style = MaterialTheme.typography.bodySmall)
        val steps = task.optJSONArray("steps")
        if (steps != null && steps.length() > 0) {
            Text("Последние операции (факты исполнителей)", style = MaterialTheme.typography.labelLarge)
            repeat(steps.length()) { i ->
                val step = steps.getJSONObject(i)
                val label = when (step.optString("status")) { "SUCCEEDED" -> "Подтверждено"; "FAILED" -> "Ошибка исполнителя"; else -> "Исход неизвестен · повтор записи заблокирован" }
                Text("${step.optString("tool")}: $label" + (step.optString("summary").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    val plan = snapshot?.plan
    if (plan != null && plan.length() > 0) {
        Text("Актуальный план todo", style = MaterialTheme.typography.labelLarge)
        Text("Статусы плана задаёт агент. Подтверждения операций показаны отдельно выше.", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 200.dp)) {
            items(plan.length(), key = { plan.getJSONObject(it).getString("id") }) { i ->
                val step = plan.getJSONObject(i)
                Text(when (step.getString("status")) { "completed" -> "✓ "; "in_progress" -> "→ "; else -> "○ " } + step.getString("text"), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (snapshot?.task == null && (plan == null || plan.length() == 0)) Text("Подтверждённого состояния задачи пока нет.", style = MaterialTheme.typography.bodySmall)
}
