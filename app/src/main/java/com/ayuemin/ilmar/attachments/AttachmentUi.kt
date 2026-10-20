package com.ayuemin.ilmar.attachments

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
internal fun AttachmentList(items: List<ChatAttachment>, onRemove: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val files = remember { AttachmentStore(context) }
    val scope = rememberCoroutineScope()
    var exportingId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val item = items.find { it.id == exportingId }; exportingId = null
        val uri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && item != null && uri != null) scope.launch {
            val success = withContext(Dispatchers.IO) { runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output -> files.file(item.id).inputStream().use { it.copyTo(output) } } ?: error("Нет доступа к файлу")
            }.isSuccess }
            android.widget.Toast.makeText(context, if (success) "Файл сохранён" else "Не удалось сохранить файл", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    fun export(item: ChatAttachment) {
        exportingId = item.id
        try { save.launch(FileExport.intent(item.name, item.mime)) }
        catch (_: android.content.ActivityNotFoundException) { exportingId = null; android.widget.Toast.makeText(context, "Сохранение файлов недоступно", android.widget.Toast.LENGTH_SHORT).show() }
    }
    Column(Modifier.heightIn(max = if (onRemove == null) 480.dp else 160.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            Row(Modifier.fillMaxWidth().clickable {
                if (item.mime in setOf("text/html", "image/svg+xml", "application/xml")) export(item) else try {
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", files.file(item.id), item.name)
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) { android.widget.Toast.makeText(context, "Нет приложения для просмотра этого файла", android.widget.Toast.LENGTH_SHORT).show() }
            }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                val image = item.images.firstOrNull()
                if (image != null) {
                    val bitmap by produceState<android.graphics.Bitmap?>(null, image) {
                        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(files.file(image).path, BitmapFactory.Options().apply { inSampleSize = 8 }) }
                    }
                    bitmap?.let { Image(it.asImageBitmap(), item.name, Modifier.size(56.dp).padding(end = 8.dp)) }
                }
                Column(Modifier.weight(1f)) {
                    Text(item.name + (item.version?.let { " · v$it" } ?: ""), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    Text("${item.size / 1024} КБ" + if (item.images.size > 1) " · ${item.images.size} стр." else "",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(enabled = exportingId == null, onClick = { export(item) }) { Text("Сохранить") }
                onRemove?.let { remove -> IconButton(onClick = { remove(item.id) }) { Icon(Icons.Outlined.Close, "Убрать вложение") } }
            }
        }
    }
}
