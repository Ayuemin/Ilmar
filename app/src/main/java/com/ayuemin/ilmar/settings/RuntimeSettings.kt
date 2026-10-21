package com.ayuemin.ilmar.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun RuntimeSettings() {
    val context = LocalContext.current
    RuntimeLimits.install(context)
    var values by remember { mutableStateOf(RuntimeLimits.fields.associate { it.key to RuntimeLimits.get(it.key).toString() }) }
    var group by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    Text("Глобальные настройки для всех чатов. Новые значения применяются к следующим задачам. Ноль отключает ограничение только там, где это указано. Ограничения памяти устройства, прав доступа и окна модели сохраняются.")
    OutlinedButton(onClick = {
        values = values + EconomyProfile.runtimeOverrides.mapValues { it.value.toString() }
        status = "Подготовлен экономичный профиль: до 8 модельных раундов, 32 tools, 5 минут на задачу и 60 секунд на запрос. Проверь значения и нажми «Сохранить лимиты». Денежная квота и выбор модели не меняются."
    }) { Text("Подготовить экономичный профиль") }
    Text("Для экономии браузера можно менять размер DOM, число ссылок и полных наблюдений в истории, резерв ответа модели и длину локальных цепочек. Очень маленький объём может потребовать дополнительных чтений. Полные результаты сохраняются локально.", style = MaterialTheme.typography.bodySmall)
    Text("«Авто» сначала использует подключённый поиск поставщика; браузер дополняет его для сложных случаев. При доступном браузере поиск поставщика по умолчанию подключается к первому запросу, число таких запросов можно увеличить. Встроенный в саму модель поиск (например, Perplexity) этим лимитом не отключается.", style = MaterialTheme.typography.bodySmall)
    Text("Файлы из интернета скачиваются только после кнопки «Скачать» и сохраняются для вас в «Загрузки/Ilmar» (Android 10+). Модель получает только ID и место сохранения. По умолчанию лимитов МиБ и общего времени скачивания нет; действуют свободное место и доступность сети. Эти файлы не являются вложениями для модели.", style = MaterialTheme.typography.bodySmall)
    RuntimeLimits.fields.groupBy { it.group }.forEach { (title, fields) ->
        TextButton(onClick = { group = if (group == title) null else title }) { Text("${if (group == title) "⌄" else "›"} $title") }
        if (group == title) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            fields.forEach { field ->
                OutlinedTextField(values.getValue(field.key), { values = values + (field.key to it); status = null },
                    label = { Text(field.label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    Button(onClick = {
        try { RuntimeLimits.save(values.mapValues { it.value.toInt() }); status = "Глобальные лимиты сохранены" }
        catch (e: Exception) { status = e.message ?: "Проверь целые числа" }
    }) { Text("Сохранить лимиты") }
    OutlinedButton(onClick = { RuntimeLimits.reset(); values = RuntimeLimits.fields.associate { it.key to RuntimeLimits.get(it.key).toString() }; status = "Восстановлены расширенные значения по умолчанию" }) { Text("Восстановить значения") }
    status?.let { Text(it) }
    Text("Контекст, резерв ответа и бюджет токенов — в разделе «Память и контекст». Пустой денежный бюджет и нулевой токеновый бюджет отключают соответствующую локальную квоту.", style = MaterialTheme.typography.bodySmall)
}
