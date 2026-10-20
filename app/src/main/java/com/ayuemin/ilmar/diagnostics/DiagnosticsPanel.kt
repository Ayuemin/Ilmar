package com.ayuemin.ilmar.diagnostics

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DiagnosticsPanel() {
    val context = LocalContext.current
    val diagnostics = remember { Diagnostics(context) }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var errors by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var clear by remember { mutableStateOf(false) }
    val events by produceState("Загрузка…", revision, errors) { value = withContext(Dispatchers.IO) { diagnostics.read(errors) } }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { response ->
        val uri = response.data?.data
        if (response.resultCode == Activity.RESULT_OK && uri != null) scope.launch {
            busy = true
            result = withContext(Dispatchers.IO) { runCatching {
                context.contentResolver.openOutputStream(uri)?.use { diagnostics.export(it) } ?: error("Нет доступа к файлу")
            }.fold({ "Журнал сохранён" }, { "Не удалось выгрузить журнал" }) }
            busy = false
        }
    }
    Text("Ilmar · ${com.ayuemin.ilmar.BuildConfig.VERSION_NAME} · ${com.ayuemin.ilmar.BuildConfig.BUILD_SHA.take(12)}")
    Text("Запросы моделей, Compact, агентные раунды, инструменты и причины ошибок. Ниже — последние 200 событий; выгрузка включает весь сохранённый журнал и прежние tool logs. Ключи и заголовки авторизации скрываются. Текст файлов/запросов может содержать личные данные.")
    Row {
        FilterChip(errors, { errors = !errors }, label = { Text("Только ошибки") })
        TextButton(onClick = { revision++ }) { Text("Обновить") }
    }
    Button(enabled = !busy, onClick = {
        try { save.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, "Ilmar-log-${System.currentTimeMillis()}.jsonl")) }
        catch (_: android.content.ActivityNotFoundException) { result = "Системное сохранение файлов недоступно" }
    }) { Text("Выгрузить журнал .jsonl") }
    OutlinedButton(enabled = !busy, onClick = { clear = true }) { Text("Очистить журнал") }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Очистить журнал?") },
        confirmButton = { TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { diagnostics.clear() }; revision++ }; clear = false }) { Text("Очистить") } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("Отмена") } })
    result?.let { Text(it) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    SelectionContainer { Text(events.ifBlank { "Событий пока нет" }, style = MaterialTheme.typography.bodySmall) }
}
