package com.ayuemin.ilmar.speech

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ayuemin.ilmar.model.ProviderRegistry

@Composable
internal fun SpeechSettings(onDismiss: () -> Unit, onSpeak: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { SpeechPreferences(context) }
    val registry = remember { ProviderRegistry(context) }
    val data = registry.snapshot()
    var provider by remember { mutableStateOf(preferences.provider) }
    var model by remember { mutableStateOf(preferences.model) }
    var voice by remember { mutableStateOf(preferences.voice) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Озвучить ответ") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(provider.isBlank(), { provider = "" }); Text("Системный голос Android")
            }
            data.providers.forEach { connection -> Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(provider == connection.id, { provider = connection.id; model = "" }); Text(connection.name)
            } }
            if (provider.isNotBlank()) {
                Text("Использует API выбранного провайдера и его тариф. Эти расходы не включены в статистику чата.", style = MaterialTheme.typography.bodySmall)
                val audioModels = data.models.filter { it.providerId == provider && "audio" in registry.capabilities(it.id).outputModalities && registry.isAvailable(it.id) }
                var expanded by remember { mutableStateOf(false) }
                if (audioModels.isNotEmpty()) {
                    TextButton(onClick = { expanded = !expanded }) { Text("Аудиомодели из каталога") }
                    if (expanded) audioModels.forEach { item -> TextButton(onClick = { model = item.name; expanded = false }) { Text(item.name) } }
                }
                OutlinedTextField(model, { model = it }, label = { Text("ID модели озвучки") }, singleLine = true)
                OutlinedTextField(voice, { voice = it }, label = { Text("ID голоса") }, singleLine = true)
                Text("OpenRouter: audio через chat/completions. Другие подключения: OpenAI-compatible /audio/speech. Модель и голос должны поддерживать этот API.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(enabled = provider.isBlank() || (model.isNotBlank() && voice.isNotBlank()), onClick = {
        preferences.save(provider, model, voice); onDismiss(); onSpeak()
    }) { Text("Озвучить") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}
