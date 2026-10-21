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
    var token by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(credentials.hasApiKey(GitHubClient.KEY)) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Text("GitHub", style = MaterialTheme.typography.titleMedium)
    Text("Подключи fine-grained Personal Access Token выбранных репозиториев. Для файлов нужны Contents, для задач — Issues, для PR — Pull requests, для запусков — Actions. Выдай только нужные права. Токен хранится через Android Keystore и не передаётся модели.")
    OutlinedTextField(token, { token = it }, label = { Text(if (saved) "Новый токен GitHub" else "Токен GitHub") },
        singleLine = true, visualTransformation = PasswordVisualTransformation())
    TextButton(enabled = token.isNotBlank(), onClick = {
        credentials.saveApiKey(token.trim(), GitHubClient.KEY); token = ""; saved = true; status = "Токен сохранён"
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
    Text("Включи GitHub ниже и выбери его в меню «+» чата. Чтение постраничное; каждая запись, merge, удаление, запуск Actions и публикация требуют отдельного подтверждения с телом запроса. Бинарные uploads/downloads и GitHub Enterprise этим REST-инструментом не поддерживаются.")
}
