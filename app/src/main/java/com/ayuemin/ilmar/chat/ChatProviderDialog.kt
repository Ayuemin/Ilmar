package com.ayuemin.ilmar.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuemin.ilmar.model.ProviderRegistry
import com.ayuemin.ilmar.model.ModelCatalogViewModel
import com.ayuemin.ilmar.ui.ModelCatalogBrowser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatProviderDialog(chatId: Long, registry: ProviderRegistry,
    onSettings: () -> Unit, onSelected: () -> Unit, onDismiss: () -> Unit,
    catalog: ModelCatalogViewModel = viewModel()) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
            Text("Модели", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { registry.select(chatId, null); onSelected(); onDismiss() }) { Text("Использовать модель по умолчанию") }
            ModelCatalogBrowser(catalog, registry.modelForChat(chatId)?.id,
                onChoose = { registry.select(chatId, it); onSelected(); onDismiss() },
                onConnections = { onDismiss(); onSettings() }, modifier = Modifier.weight(1f))
        }
    }
}
