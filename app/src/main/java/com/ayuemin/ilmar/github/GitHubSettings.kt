package com.ayuemin.ilmar.github

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.ayuemin.ilmar.security.CredentialStore
import kotlinx.coroutines.launch

@Composable
internal fun GitHubSettings() {
    val context = LocalContext.current
    val credentials = remember { CredentialStore(context) }
    val prefs = remember { context.getSharedPreferences("ilmar_github", android.content.Context.MODE_PRIVATE) }
    var authorName by remember { mutableStateOf(prefs.getString("author_name", "").orEmpty()) }
    var authorEmail by remember { mutableStateOf(prefs.getString("author_email", "").orEmpty()) }
    var token by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(credentials.hasApiKey(GitHubClient.KEY)) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Text("GitHub", style = MaterialTheme.typography.titleMedium)
    Text("Подключи fine-grained Personal Access Token выбранных репозиториев. Для файлов/push нужны Contents, для задач — Issues, для PR — Pull requests, для запусков — Actions, для изменения YAML workflow — Workflows (write). Выдай только нужные права. Токен хранится через Android Keystore и не передаётся модели.")
    OutlinedTextField(token, { token = it }, label = { Text(if (saved) "Новый токен GitHub" else "Токен GitHub") },
        singleLine = true, visualTransformation = PasswordVisualTransformation())
    TextButton(enabled = token.isNotBlank(), onClick = {
        try {
            require(token.trim().none { it.isISOControl() })
            credentials.saveApiKey(token.trim(), GitHubClient.KEY); token = ""; saved = true; status = "Токен сохранён"
        } catch (_: Exception) { status = "Не удалось сохранить токен в Keystore; проверь токен и доступность хранилища" }
    }) { Text("Сохранить токен") }
    TextButton(enabled = saved, onClick = { credentials.clearApiKey(GitHubClient.KEY); token = ""; saved = false; status = "Токен удалён с устройства" }) { Text("Удалить токен") }
    TextButton(enabled = saved && !busy, onClick = { scope.launch {
        busy = true
        try {
            val result = org.json.JSONObject(GitHubClient({ credentials.loadApiKey(GitHubClient.KEY) }, { true }, { _,_,_ -> false }).request(0, "GET", "/user"))
            status = if (result.optBoolean("ok")) "Подключено: " + org.json.JSONObject(result.getString("body")).optString("login") else "GitHub: HTTP ${result.optInt("status")}. Проверь срок, права токена и доступ организации."
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { status = "Не удалось проверить подключение GitHub" }
        finally { busy = false }
    } }) { Text("Проверить подключение") }
    Text(status)
    OutlinedTextField(authorName, { authorName = it }, label = { Text("Имя автора Git-коммитов") }, singleLine = true)
    OutlinedTextField(authorEmail, { authorEmail = it }, label = { Text("Email автора Git (можно GitHub noreply)") }, singleLine = true)
    TextButton(enabled = authorName.isNotBlank() && authorEmail.isNotBlank(), onClick = {
        if (prefs.edit().putString("author_name", authorName.trim()).putString("author_email", authorEmail.trim()).commit()) status = "Автор Git сохранён"
    }) { Text("Сохранить автора Git") }
    Text("Для настоящих commit/push установи Alpine и git ca-certificates, включи GitHub и «Git · Alpine» ниже и выбери Git в меню «+» чата. Сохранённый токен используется краткоживущим credential helper, без записи в URL, shell-команды или файл. Git hooks отключены, push без force, pull только fast-forward.")
    Text("Включи GitHub ниже и выбери его в меню «+» чата. Чтение постраничное; каждая запись, merge, удаление, запуск Actions и публикация требуют отдельного подтверждения с телом запроса. Бинарные uploads/downloads и GitHub Enterprise этим REST-инструментом не поддерживаются.")
}
