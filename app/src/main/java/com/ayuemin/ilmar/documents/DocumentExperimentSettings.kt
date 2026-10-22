package com.ayuemin.ilmar.documents

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun DocumentExperimentSettings() {
    val context = LocalContext.current
    val preferences = remember { DocumentExperimentPreferences(context) }
    var value by remember { mutableStateOf(preferences.load()) }
    var status by remember { mutableStateOf("") }
    Text("Эксперимент применяется к следующим задачам. Для локального поиска нужны агент, модель с tools и разрешённый инструмент «Прочитать URL».")
    Text("Обработка", style = MaterialTheme.typography.titleMedium)
    listOf(DocumentMode.LEGACY to "Legacy Fetch", DocumentMode.LOCAL to "Local Retrieval", DocumentMode.COMPARE to "Compare").forEach { (mode, title) ->
        Choice(title, value.mode == mode) { value = value.copy(mode = mode); status = "" }
    }
    Text("Compare рассчитывает оба варианта на одной загрузке, передаёт модели только Local и сохраняет числовые оценки в диагностике. Дополнительные модели не используются.", style = MaterialTheme.typography.bodySmall)
    Toggle("Структурная карта", value.options.structure) { value = value.copy(options = value.options.copy(structure = it)) }
    Toggle("Тепловое ранжирование", value.options.heat) { value = value.copy(options = value.options.copy(heat = it)) }
    Toggle("Нормализация словоформ RU/EN", value.options.morphology) { value = value.copy(options = value.options.copy(morphology = it)) }
    Toggle("Расширение запроса из найденного текста", value.options.feedback) { value = value.copy(options = value.options.copy(feedback = it)) }
    Toggle("Соседний контекст и расширение окна", value.options.context) { value = value.copy(options = value.options.copy(context = it)) }
    Text("Максимум начальных фрагментов")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(3, 5, 10).forEach { n -> FilterChip(value.fragments == n, { value = value.copy(fragments = n) }, label = { Text(n.toString()) }) }
    }
    Text("Автоматический браузер", style = MaterialTheme.typography.titleMedium)
    Choice("Существующее поведение", value.browser == ExperimentalBrowser.EXISTING) { value = value.copy(browser = ExperimentalBrowser.EXISTING) }
    Choice("Запретить в тестовой задаче", value.browser == ExperimentalBrowser.DISABLED) { value = value.copy(browser = ExperimentalBrowser.DISABLED) }
    Choice("Ограниченная эскалация", value.browser == ExperimentalBrowser.LIMITED) { value = value.copy(browser = ExperimentalBrowser.LIMITED) }
    Text("При ограниченной эскалации открытие конкретного URL требует причины (JavaScript, вход или взаимодействие) и вашего подтверждения. Общий поиск браузером отключён. Все обычные разрешения и подтверждения действий сохраняются.", style = MaterialTheme.typography.bodySmall)
    Button(onClick = { preferences.save(value); status = "Сохранено для следующих задач" }) { Text("Сохранить эксперимент") }
    OutlinedButton(onClick = { value = DocumentExperiment(); preferences.save(value); status = "Восстановлен Legacy Fetch" }) { Text("Сбросить эксперимент") }
    if (status.isNotEmpty()) Text(status)
}

@Composable
private fun Choice(title: String, selected: Boolean, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = action).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null); Spacer(Modifier.width(8.dp)); Text(title, Modifier.weight(1f))
    }
}
@Composable
private fun Toggle(title: String, checked: Boolean, action: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = action).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f)); Switch(checked, onCheckedChange = null)
    }
}
