package com.ayuemin.ilmar.tools

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ayuemin.ilmar.security.CredentialStore

@Composable
internal fun ToolSettings() {
    val context = LocalContext.current
    val credentials = remember { CredentialStore(context) }
    var key by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(credentials.hasApiKey(NativeTools.SEARCH_KEY)) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Разрешения ниже действуют глобально. Затем выбери инструменты в меню «+» чата и включи режим агента у модели с поддержкой tools.")
        Text("Brave Search", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(key, { key = it }, label = { Text("API-ключ Brave Search") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
        Text(if (saved) "Ключ сохранён в Android Keystore" else "Для поиска нужен ключ Brave Search")
        Row {
            TextButton(enabled = key.isNotBlank(), onClick = {
                try { credentials.saveApiKey(key.trim(), NativeTools.SEARCH_KEY); key = ""; saved = true; error = null }
                catch (_: Exception) { error = "Не удалось сохранить ключ" }
            }) { Text("Сохранить ключ") }
            TextButton(enabled = saved, onClick = { credentials.clearApiKey(NativeTools.SEARCH_KEY); saved = false }) { Text("Удалить ключ") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("URL: публичный HTTPS, без перенаправлений, до 1 МБ и 15 секунд. Поиск и URL кэшируются на 5 минут (до 32 записей).")
        Text("Созданные UTF-8 файлы до 32 КБ сохраняются в чате. Квота хранилища вложений — 100 МБ для создания файлов; экспорт — кнопкой сохранения. HTML и SVG доступны только для сохранения.")
        Text("Память и планы изолированы по чатам; поиск памяти — SQLite FTS, без эмбеддингов. При удалении чата они удаляются.")
        Text("Журнал tools хранит локально последние 100 вызовов, до 2048 символов аргументов/результата, время и статус. Неизвестная стоимость не считается нулевой.", style = MaterialTheme.typography.bodySmall)
    }
}
