package com.ayuemin.ilmar.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
internal fun MessageActions(message: ChatMessage, busy: Boolean, onAskAgain: () -> Unit,
    onBranch: () -> Unit, onSpeak: () -> Unit) {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var info by remember(message.id) { mutableStateOf(false) }
    CompositionLocalProvider(LocalContentColor provides muted) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        IconButton(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Сообщение", message.text))
        }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.ContentCopy, "Скопировать", Modifier.size(18.dp), tint = muted) }
        if (message.fromUser) {
            IconButton(enabled = !busy, onClick = onAskAgain, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Refresh, "Спросить снова", Modifier.size(18.dp), tint = muted)
            }
        } else {
            IconButton(onClick = {
                try { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, message.text)
                }, "Поделиться ответом")) } catch (_: android.content.ActivityNotFoundException) {
                    android.widget.Toast.makeText(context, "Нет приложения для отправки", android.widget.Toast.LENGTH_SHORT).show()
                }
            }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Share, "Поделиться", Modifier.size(18.dp), tint = muted) }
            IconButton(onClick = onSpeak, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.VolumeUp, "Озвучить", Modifier.size(18.dp), tint = muted) }
            IconButton(enabled = !busy, onClick = onBranch, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.CallSplit, "Ответвление", Modifier.size(18.dp), tint = muted) }
            IconButton(onClick = { info = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Info, "Об ответе", Modifier.size(18.dp), tint = muted) }
        }
    }
    }
    if (info) AlertDialog(onDismissRequest = { info = false }, title = { Text("Об ответе") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val details = message.info
            if (details == null) Text("Этот ответ сохранён до добавления метаданных. Модель, время и usage неизвестны.")
            else {
                Text(details.model); Text(details.provider)
                Text(DateFormat.getDateTimeInstance().format(Date(details.createdAt)))
                Text("Время выполнения: ${details.durationMs / 1000.0} с · запросов: ${details.requests}")
                Text(UsageLedger(listOf(UsageRecord(details.usage, "completed"))).describe().substringAfter('\n').substringBefore("\nДанные сообщает"))
                if (details.incompleteUsage) Text("Usage неполный: указана только известная часть токенов и стоимости.", style = MaterialTheme.typography.bodySmall)
                        Text("Включает модельные раунды ответа и Compact этого запуска; эмбеддинги, озвучка и отдельные сборы поиска не включены. Старые ответы могли учитывать только генерацию. В ветке сохранены исходные метаданные; копия не создаёт списания.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(onClick = { info = false }) { Text("Закрыть") } })
}
