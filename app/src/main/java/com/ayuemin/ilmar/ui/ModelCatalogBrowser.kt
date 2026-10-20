package com.ayuemin.ilmar.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ayuemin.ilmar.model.ModelCatalogViewModel

/** Shared catalogue: settings chooses the default, chat chooses a model for that chat only. */
@Composable
fun ModelCatalogBrowser(controller: ModelCatalogViewModel, selectedModelId: String?,
    onChoose: (String) -> Unit, onConnections: () -> Unit, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val registry = controller.registry
    val data = remember(state.revision) { registry.snapshot() }
    val readyProviders = remember(data, state.revision) { data.providers.filter { registry.hasKey(it.id) }.map { it.id }.toSet() }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var favoriteOnly by remember { mutableStateOf<Set<String>>(emptySet()) }
    val context = LocalContext.current
    Column(modifier) {
        OutlinedTextField(query, { query = it }, label = { Text("Поиск моделей и провайдеров") },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), singleLine = true)
        if (data.providers.isEmpty()) Text("Сначала добавь провайдера в подключениях.")
        LazyColumn(Modifier.weight(1f)) {
            data.providers.forEach { provider ->
                val models = data.models.filter { it.providerId == provider.id }
                val matching = models.filter { (it.name.contains(query, true) || provider.name.contains(query, true)) &&
                    (provider.id !in favoriteOnly || registry.isFavorite(it.id)) }
                val isExpanded = provider.id in expanded || query.isNotBlank()
                item(key = "provider:${provider.id}") {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                        Row(Modifier.fillMaxWidth().clickable {
                            expanded = if (provider.id in expanded) expanded - provider.id else expanded + provider.id
                            if (provider.id in expanded) controller.load(provider.id)
                        }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                            Text(provider.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text(models.size.toString(), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (isExpanded) {
                    item(key = "controls:${provider.id}") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(selected = provider.id in favoriteOnly, onClick = {
                                favoriteOnly = if (provider.id in favoriteOnly) favoriteOnly - provider.id else favoriteOnly + provider.id
                            }, label = { Text("Избранное") })
                            Spacer(Modifier.weight(1f))
                            IconButton(enabled = provider.id !in state.loading, onClick = { controller.load(provider.id, true) }) {
                                Icon(Icons.Outlined.Refresh, "Обновить список моделей ${provider.name}")
                            }
                        }
                        if (provider.id in state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.errors[provider.id]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        if (provider.id !in readyProviders) Text("Добавь API-ключ в подключениях", style = MaterialTheme.typography.bodySmall)
                        if (matching.isEmpty() && provider.id !in state.loading) {
                            Text(if (provider.id in favoriteOnly) "Нет избранных моделей" else "Список пуст. Нажми обновить.",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }
                    items(matching.sortedWith(compareByDescending<com.ayuemin.ilmar.model.RegisteredModel> { registry.isFavorite(it.id) }.thenBy { it.name }), key = { it.id }) { model ->
                        val ready = provider.id in readyProviders && registry.isAvailable(model.id)
                        Row(Modifier.fillMaxWidth().clickable(enabled = ready) { onChoose(model.id) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedModelId == model.id, enabled = ready, onClick = { onChoose(model.id) })
                            Column(Modifier.weight(1f)) {
                                Text(model.name.substringAfterLast('/'), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(model.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (!registry.isAvailable(model.id)) Text("Нет в текущем каталоге", style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("Model ID", model.name))
                                Toast.makeText(context, "ID модели скопирован", Toast.LENGTH_SHORT).show()
                            }) { Icon(Icons.Outlined.ContentCopy, "Скопировать ID ${model.name}") }
                            IconButton(onClick = { controller.favorite(model.id) }) {
                                Icon(if (registry.isFavorite(model.id)) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                    if (registry.isFavorite(model.id)) "Убрать из избранного" else "В избранное")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
        TextButton(onClick = onConnections) { Text("Подключения провайдеров") }
    }
}
