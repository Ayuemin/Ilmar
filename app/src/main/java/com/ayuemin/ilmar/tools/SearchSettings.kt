package com.ayuemin.ilmar.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun SearchSettings() {
    val context = LocalContext.current
    val registry = remember { SearchRegistry(context) }
    var revision by remember { mutableIntStateOf(0) }
    val profiles = remember(revision) { registry.profiles() }
    var editing by remember { mutableStateOf<SearchProfile?>(null) }
    var query by remember { mutableStateOf("OpenAI") }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Text("API поиска", style = MaterialTheme.typography.titleMedium)
    Text("Brave, Tavily, SerpAPI или свой HTTPS JSON API. Можно настроить GET/POST, ключ, любые заголовки, тело запроса и пути полей ответа. Активный профиль действует во всех чатах.")
    profiles.forEach { profile ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(registry.selected() == profile.id, { registry.select(profile.id); revision++ })
            Text(profile.name, Modifier.weight(1f))
            TextButton(onClick = { editing = profile }) { Text("Изменить") }
        }
    }
    Button(onClick = { editing = SearchProfile.preset("Custom") }) { Text("Добавить API поиска") }
    OutlinedTextField(query, { query = it }, label = { Text("Запрос для проверки API") }, singleLine = true)
    TextButton(enabled = !busy && query.isNotBlank() && registry.ready(), onClick = {
        scope.launch {
            busy = true
            result = try { WebTools().search(query, registry.target()) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { com.ayuemin.ilmar.diagnostics.Redaction.text(e.message ?: "Ошибка поиска") }
            finally { busy = false }
        }
    }) { Text("Проверить API (один запрос)") }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    result?.let { Text(it.take(8000), style = MaterialTheme.typography.bodySmall) }
    editing?.let { item -> SearchEditor(item, registry, { editing = null }, { editing = null; revision++; result = null }) }
}

@Composable
private fun SearchEditor(initial: SearchProfile, registry: SearchRegistry, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var profile by remember(initial.id) { mutableStateOf(initial) }
    var key by remember { mutableStateOf("") }
    var headers by remember { mutableStateOf(if (registry.profiles().none { it.id == initial.id }) "{}" else "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Поисковое подключение") }, text = {
        Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Шаблон")
            listOf("Brave", "Tavily", "SerpAPI", "Custom").chunked(2).forEach { kinds -> Row { kinds.forEach { kind ->
                TextButton(onClick = { profile = SearchProfile.preset(kind, profile.id); headers = SearchProfile.headers(kind) }) { Text(kind) }
            } } }
            OutlinedTextField(profile.name, { profile = profile.copy(name = it) }, label = { Text("Название") })
            OutlinedTextField(profile.endpoint, { profile = profile.copy(endpoint = it) }, label = { Text("HTTPS endpoint") })
            Row { listOf("GET", "POST").forEach { method -> FilterChip(profile.method == method, { profile = profile.copy(method = method) }, label = { Text(method) }) } }
            OutlinedTextField(key, { key = it }, label = { Text(if (registry.hasKey(profile.id)) "Новый ключ (пусто — сохранить)" else "API-ключ (необязательно)") }, visualTransformation = PasswordVisualTransformation())
            Text("{query}, {api_key}, {count} подставляются в query-параметры URL, заголовки и JSON-тело. Ключи и заголовки хранятся зашифрованно. В URL и теле используй {api_key}, а не сам ключ.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(headers, { headers = it }, label = { Text("HTTP-заголовки JSON; пусто — сохранить") }, minLines = 2)
            Text("Например: {\"Authorization\":\"Bearer {api_key}\"}", style = MaterialTheme.typography.bodySmall)
            if (profile.method == "GET") OutlinedTextField(profile.queryParameter, { profile = profile.copy(queryParameter = it) }, label = { Text("Имя параметра запроса; пусто — только шаблон URL") })
            else OutlinedTextField(profile.body, { profile = profile.copy(body = it) }, label = { Text("JSON-тело POST") }, minLines = 3)
            OutlinedTextField(profile.resultsPath, { profile = profile.copy(resultsPath = it) }, label = { Text("Путь к массиву результатов") })
            Text("Примеры: web.results, organic_results, /data/items; пусто — корневой массив. Поля ниже — относительно одного результата.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(profile.titlePath, { profile = profile.copy(titlePath = it) }, label = { Text("Поле заголовка") })
            OutlinedTextField(profile.urlPath, { profile = profile.copy(urlPath = it) }, label = { Text("Поле URL") })
            OutlinedTextField(profile.snippetPath, { profile = profile.copy(snippetPath = it) }, label = { Text("Поле сниппета/текста") })
            TextButton(onClick = { registry.clearKey(profile.id); key = "" }) { Text("Удалить сохранённый ключ") }
            TextButton(onClick = { registry.delete(profile.id); onSaved() }) { Text("Удалить профиль") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        try { registry.save(profile, key, headers.takeIf { it.isNotBlank() }); key = ""; onSaved() }
        catch (e: Exception) { error = e.message ?: "Не удалось сохранить API поиска" }
    }) { Text("Сохранить и выбрать") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}
