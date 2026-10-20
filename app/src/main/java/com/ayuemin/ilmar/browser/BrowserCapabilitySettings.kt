package com.ayuemin.ilmar.browser

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.ayuemin.ilmar.model.ProviderRegistry

/** Many compatible /models endpoints publish IDs only. Overrides are an explicit user assertion. */
@Composable
internal fun BrowserCapabilitySettings(model: String) {
    val context = LocalContext.current
    val registry = remember { ProviderRegistry(context) }
    var capabilities by remember(model) { mutableStateOf(registry.capabilities(model)) }
    Text("Возможности текущей модели", style = MaterialTheme.typography.labelLarge)
    Text("Если API не публикует возможности, подтверди их по документации модели. Это не добавляет зрение или tools самой модели. Настройка действует только для выбранной модели.", style = MaterialTheme.typography.bodySmall)
    listOf("tools" to "Вызовы инструментов (tools)", "image" to "Зрение: изображения на входе").forEach { (key, title) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(if (key == "tools") capabilities.tools else "image" in capabilities.inputModalities, onCheckedChange = {
                registry.setCapabilityOverride(model, key, it); capabilities = registry.capabilities(model)
            })
            Text(title)
        }
    }
    TextButton(onClick = {
        listOf("tools", "image").forEach { registry.setCapabilityOverride(model, it, null) }; capabilities = registry.capabilities(model)
    }) { Text("Вернуть сведения каталога") }
}
