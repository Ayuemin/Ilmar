package com.ayuemin.ilmar.ui.markdown

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.dp

// Owner-authorized adaptation from Ayuemin/Umnik; split into Ilmar modules, 2026-10-20.
internal enum class MessagePartKind { PLAIN, CODE, COPY }

internal data class MessagePart(
    val text: String,
    val kind: MessagePartKind,
    val language: String = ""
)

@Composable
fun RichMessageContent(text: String, color: androidx.compose.ui.graphics.Color) {
    val parts = remember(text) { splitRichBlocks(text) }
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { part ->
            when (part.kind) {
                MessagePartKind.PLAIN -> if (part.text.isNotBlank()) {
                    MarkdownText(part.text.trim(), color)
                }
                MessagePartKind.CODE -> IsolatedBlock(
                    title = part.language.ifBlank { "Код" },
                    text = part.text,
                    monospace = true,
                    onCopy = { copyBlock(context, part.text) }
                )
                MessagePartKind.COPY -> IsolatedBlock(
                    title = "Для копирования",
                    text = part.text,
                    monospace = false,
                    onCopy = { copyBlock(context, part.text) }
                )
            }
        }
    }
}

@Composable
private fun IsolatedBlock(
    title: String,
    text: String,
    monospace: Boolean,
    onCopy: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 1.dp
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = onCopy) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Копировать блок", modifier = Modifier.size(18.dp))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            SelectionContainer {
                Text(
                    text,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

internal fun splitRichBlocks(text: String): List<MessagePart> {
    val regex = Regex(":::copy[ \\t]*\\n([\\s\\S]*?)\\n:::[ \\t]*|```([^\\n`]*)\\n?([\\s\\S]*?)```")
    val result = mutableListOf<MessagePart>()
    var cursor = 0

    regex.findAll(text).forEach { match ->
        if (match.range.first > cursor) {
            result += MessagePart(text.substring(cursor, match.range.first), MessagePartKind.PLAIN)
        }
        if (match.value.startsWith(":::copy")) {
            result += MessagePart(match.groupValues[1].trim(), MessagePartKind.COPY)
        } else {
            result += MessagePart(
                text = match.groupValues[3].trimEnd(),
                kind = MessagePartKind.CODE,
                language = match.groupValues[2].trim()
            )
        }
        cursor = match.range.last + 1
    }

    if (cursor < text.length) result += MessagePart(text.substring(cursor), MessagePartKind.PLAIN)
    if (result.isEmpty()) result += MessagePart(text, MessagePartKind.PLAIN)
    return result
}


private fun copyBlock(context: android.content.Context, text: String) {
    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
        .setPrimaryClip(android.content.ClipData.newPlainText("Код", text))
}
