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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ayuemin.ilmar.model.RegisteredModel
import com.ayuemin.ilmar.model.RegistrySnapshot
import com.ayuemin.ilmar.model.ProviderRegistry

@Composable
internal fun SpeechSettings() {
    val context = LocalContext.current
    val preferences = remember { SpeechPreferences(context) }
    val registry = remember { ProviderRegistry(context) }
    val data by produceState<RegistrySnapshot?>(null) { value = withContext(Dispatchers.IO) { registry.snapshot() } }
    var saved by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf(preferences.provider) }
    var model by remember { mutableStateOf(preferences.model) }
    var voice by remember { mutableStateOf(preferences.voice) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(provider.isBlank(), { provider = ""; saved = false }); Text("Системный голос Android")
            }
            data?.providers.orEmpty().forEach { connection -> Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(provider == connection.id, { provider = connection.id; model = ""; saved = false }); Text(connection.name)
            } }
            if (provider.isNotBlank()) {
                Text("Использует API выбранного провайдера и его тариф. Эти расходы не включены в статистику чата.", style = MaterialTheme.typography.bodySmall)
                val audioModels by produceState<List<RegisteredModel>?>(null, provider, data) {
                    value = withContext(Dispatchers.IO) {
                        val caps = registry.providerCapabilities(provider)
                        data?.models.orEmpty().filter { it.providerId == provider && "audio" in caps[it.id]?.outputModalities.orEmpty() && registry.isAvailable(it.id) }
                    }
                }
                var expanded by remember { mutableStateOf(false) }
                if (!audioModels.isNullOrEmpty()) {
                    TextButton(onClick = { expanded = !expanded }) { Text("Аудиомодели из каталога") }
                    if (expanded) audioModels.orEmpty().take(40).forEach { item -> TextButton(onClick = { model = item.name; expanded = false }) { Text(item.name) } }
                }
                OutlinedTextField(model, { model = it; saved = false }, label = { Text("ID модели озвучки") }, singleLine = true)
                OutlinedTextField(voice, { voice = it; saved = false }, label = { Text("ID голоса") }, singleLine = true)
                Text("OpenRouter: audio через chat/completions. Другие подключения: OpenAI-compatible /audio/speech. Модель и голос должны поддерживать этот API.", style = MaterialTheme.typography.bodySmall)
            }
            Button(enabled = provider.isBlank() || (model.isNotBlank() && voice.isNotBlank()), onClick = {
                preferences.save(provider, model, voice); saved = true
            }) { Text("Сохранить") }
            if (saved) Text("Настройки сохранены. Кнопка под ответом сразу запускает озвучку.")
        }
}
