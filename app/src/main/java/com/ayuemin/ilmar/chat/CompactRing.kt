package com.ayuemin.ilmar.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Progress to the next automatic Compact, not lifetime chat usage. */
@Composable
internal fun CompactRing(gauge: CompactGauge?, onClick: () -> Unit) {
    val progress = (gauge?.percent ?: 0).coerceIn(0, 100)
    val foreground = if (progress >= 90) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.outlineVariant
    Box(Modifier.size(48.dp).semantics {
        contentDescription = if (gauge == null) "Статистика контекста, ожидается первый запрос"
            else "До следующего сжатия: $progress процентов. Открыть статистику"
    }.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(40.dp)) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2
            val diameter = size.minDimension - stroke
            drawArc(background, 0f, 360f, false, Offset(inset, inset), Size(diameter, diameter), style = Stroke(stroke))
            if (progress > 0) drawArc(foreground, -90f, 360f * progress / 100f, false,
                Offset(inset, inset), Size(diameter, diameter), style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(if (gauge == null) "—" else "$progress%", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun CompactStats(gauge: CompactGauge?, ledger: UsageLedger, messageCount: Int, onDismiss: () -> Unit) {
    @Composable fun Line(label: String, value: String) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Контекст и использование") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("До следующего Compact", style = MaterialTheme.typography.titleSmall)
                Line("Заполнение порога", gauge?.let { "${it.percent}%" } ?: "Нет данных")
                Line("Текущий вход (оценка)", gauge?.input?.toString() ?: "Нет данных")
                Line("Порог 80% окна", gauge?.threshold?.toString() ?: "Нет данных")
                Line("До сжатия (оценка)", gauge?.remaining?.toString() ?: "Нет данных")
                Line("Резерв ответа", gauge?.outputReserve?.toString() ?: "Нет данных")
                Line("Окно модели", gauge?.window?.toString() ?: "Нет данных")
                HorizontalDivider()
                Text("За весь чат", style = MaterialTheme.typography.titleSmall)
                Line("Сообщений", messageCount.toString())
                Line("Запросов к модели", ledger.calls.toString())
                Line("Входные токены", ledger.input?.toString() ?: "Нет данных")
                Line("Выходные токены", ledger.output?.toString() ?: "Нет данных")
                Line("Кэшированные входные", ledger.cached?.toString() ?: "Нет данных")
                Line("Сжато (оценка)", ledger.estimatedSaved.toString())
                Line("Сжатий", ledger.compactCalls.toString())
                Line("Стоимость USD", ledger.costUsd ?: "Нет данных")
                if (ledger.incompleteCost) Text("Стоимость неполная: показана известная часть.", style = MaterialTheme.typography.bodySmall)
                Text("Процент отражает последний подготовленный запрос, а не размер всей истории. Обновляется при работе агента; до первого запроса неизвестен.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}
