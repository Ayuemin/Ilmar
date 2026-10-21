package com.ayuemin.ilmar.browser

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.ayuemin.ilmar.chat.ChatModePreferences

@Composable
internal fun SearchControls(chat: Long, preferences: ChatModePreferences, providerId: String?, modelId: String?, onOpenBrowser: () -> Unit) {
    var source by remember(chat) { mutableStateOf(SearchRouting.source(preferences.choice(chat, "search_source"))) }
    val context = LocalContext.current
    val providerPrefs = remember { ProviderSearchPreferences(context) }
    var protocol by remember(providerId) { mutableStateOf(providerId?.let(providerPrefs::selected) ?: ProviderSearchProtocol.AUTO) }
    var engine by remember(providerId) { mutableStateOf(providerId?.let(providerPrefs::engine) ?: ProviderSearchEngine.AUTO) }
    var advanced by remember { mutableStateOf(false) }
    val browser by BrowserRuntime.state.collectAsState()
    Text("Источник поиска", style = MaterialTheme.typography.labelLarge)
    SearchSource.entries.forEach { option ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(source == option, onClick = { source = option; preferences.setChoice(chat, "search_source", option.name.lowercase()) })
            Text(option.title)
        }
    }
    Text("Сначала — подключённый поиск поставщика с выбранным движком. В «Авто» локальный браузер доступен дополнительно, когда результатов недостаточно или нужны действия на странице; перед первым использованием он запросит разрешение. Поиск поставщика может оплачиваться отдельно.", style = MaterialTheme.typography.bodySmall)
    Text("Модель может нажимать элементы, заполнять обычные поля и смотреть снимок экрана при поддержке зрения. Пароли, CAPTCHA и подтверждения — в переданном вам браузере; после действий нажмите возврат модели.", style = MaterialTheme.typography.bodySmall)
    if (browser.chat == chat) {
        Text(browser.status, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { BrowserRuntime.show(chat); onOpenBrowser() }) { Text("Открыть браузер") }
    }
    TextButton(onClick = { advanced = !advanced }) { Text("Веб-поиск текущего поставщика") }
    if (advanced && modelId != null) BrowserCapabilitySettings(modelId)
    if (advanced && providerId != null) {
        Text("Для совместимого API выбери поддерживаемый им протокол. Настройка действует во всех чатах этого подключения. Смена поставщика не меняет его параметры автоматически.", style = MaterialTheme.typography.bodySmall)
        ProviderSearchProtocol.entries.forEach { option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(protocol == option, onClick = { protocol = option; providerPrefs.save(providerId, option) })
                Text(option.title, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text("Движок веб-поиска OpenRouter", style = MaterialTheme.typography.labelLarge)
        Text("Применяется только к протоколу OpenRouter. Для других API используется их собственная настройка поиска.", style = MaterialTheme.typography.bodySmall)
        ProviderSearchEngine.entries.forEach { option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(engine == option, onClick = { engine = option; providerPrefs.saveEngine(providerId, option) })
                Text(option.title)
            }
        }
    }
}
