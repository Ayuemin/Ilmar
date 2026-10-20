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
@Composable
internal fun MarkdownText(text: String, color: androidx.compose.ui.graphics.Color) {
    val lines = remember(text) { text.replace("\r\n", "\n").split("\n") }
    val paragraph = mutableListOf<String>()

    @Composable
    fun paragraphBlock(raw: String) {
        if (raw.isBlank()) return
        SelectionContainer {
            Text(
                text = markdownInline(raw),
                color = color,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    @Composable
    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            paragraphBlock(paragraph.joinToString("\n").trim())
            paragraph.clear()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var index = 0
        while (index < lines.size) {
            val rawLine = lines[index]
            val line = rawLine.trimEnd()
            val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(line.trimStart())
            val unordered = Regex("^\\s*[-+*]\\s+(.+)$").matchEntire(line)
            val ordered = Regex("^\\s*(\\d+)[.)]\\s+(.+)$").matchEntire(line)
            val quote = Regex("^\\s*>\\s?(.*)$").matchEntire(line)
            val horizontalRule = Regex("^\\s*((-{3,})|(\\*{3,})|(_{3,}))\\s*$").matches(line)
            val possibleHeader = markdownTableCells(line)
            val tableStart = possibleHeader.size >= 2 && index + 1 < lines.size &&
                isMarkdownTableSeparator(lines[index + 1], possibleHeader.size)

            when {
                tableStart -> {
                    flushParagraph()
                    val rows = mutableListOf(possibleHeader)
                    index += 2
                    while (index < lines.size) {
                        val cells = markdownTableCells(lines[index])
                        if (cells.size != possibleHeader.size) break
                        rows += cells
                        index++
                    }
                    MarkdownTable(rows = rows, color = color)
                    continue
                }
                line.isBlank() -> flushParagraph()
                heading != null -> {
                    flushParagraph()
                    val level = heading.groupValues[1].length
                    val style = when (level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    SelectionContainer {
                        Text(
                            text = markdownInline(heading.groupValues[2]),
                            color = color,
                            style = style,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                horizontalRule -> {
                    flushParagraph()
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                }
                unordered != null -> {
                    flushParagraph()
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text("•", color = color, modifier = Modifier.width(20.dp))
                        SelectionContainer {
                            Text(
                                markdownInline(unordered.groupValues[1]),
                                color = color,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                ordered != null -> {
                    flushParagraph()
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text("${ordered.groupValues[1]}.", color = color, modifier = Modifier.width(30.dp))
                        SelectionContainer {
                            Text(
                                markdownInline(ordered.groupValues[2]),
                                color = color,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                quote != null -> {
                    flushParagraph()
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text("│", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            SelectionContainer {
                                Text(
                                    markdownInline(quote.groupValues[1]),
                                    color = color,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                else -> paragraph += line
            }
            index++
        }
        flushParagraph()
    }
}

private fun markdownTableCells(line: String): List<String> {
    val trimmed = line.trim()
    if (!trimmed.contains('|')) return emptyList()
    val body = trimmed.removePrefix("|").removeSuffix("|")
    val cells = body.split('|').map { it.trim() }
    return if (cells.size >= 2) cells else emptyList()
}

private fun isMarkdownTableSeparator(line: String, columns: Int): Boolean {
    val cells = markdownTableCells(line)
    if (cells.size != columns) return false
    return cells.all { Regex("^:?-{3,}:?$").matches(it.replace(" ", "")) }
}

@Composable
private fun MarkdownTable(rows: List<List<String>>, color: androidx.compose.ui.graphics.Color) {
    if (rows.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp)
        ) {
            rows.forEachIndexed { rowIndex, row ->
                Row {
                    row.forEach { cell ->
                        SelectionContainer {
                            Text(
                                text = markdownInline(cell),
                                modifier = Modifier.width(160.dp).padding(horizontal = 9.dp, vertical = 8.dp),
                                color = color,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
                if (rowIndex < rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = if (rowIndex == 0) 0.45f else 0.22f))
                }
            }
        }
    }
}

@Composable
private fun markdownInline(source: String): androidx.compose.ui.text.AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val linkColor = MaterialTheme.colorScheme.primary
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = linkColor,
            textDecoration = TextDecoration.Underline,
            fontWeight = FontWeight.Medium
        )
    )
    return buildAnnotatedString {
        val regex = Regex("`([^`\\n]+)`|\\*\\*([^*\\n]+)\\*\\*|__([^_\\n]+)__|~~([^~\\n]+)~~|\\[([^]\\n]+)]\\(([^)\\n]+)\\)|(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)|(?<!_)_([^_\\n]+)_(?!_)|(https?://[^\\s<>()]+)")
        var cursor = 0
        regex.findAll(source).forEach { match ->
            if (match.range.first > cursor) append(source.substring(cursor, match.range.first))
            when {
                match.groupValues[1].isNotEmpty() -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBackground
                    )
                ) { append(match.groupValues[1]) }
                match.groupValues[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[2]) }
                match.groupValues[3].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[3]) }
                match.groupValues[4].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(match.groupValues[4]) }
                match.groupValues[5].isNotEmpty() -> {
                    val label = match.groupValues[5]
                    val target = match.groupValues[6].trim()
                    if (target.startsWith("https://", true) || target.startsWith("http://", true)) {
                        withLink(LinkAnnotation.Url(target, linkStyles)) { append(label) }
                    } else {
                        withStyle(SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium)) {
                            append(label)
                        }
                    }
                }
                match.groupValues[7].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(match.groupValues[7]) }
                match.groupValues[8].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(match.groupValues[8]) }
                match.groupValues[9].isNotEmpty() -> {
                    val rawUrl = match.groupValues[9]
                    val url = rawUrl.trimEnd('.', ',', ';', ':', '!', '?', '"', '\'')
                    val suffix = rawUrl.substring(url.length)
                    withLink(LinkAnnotation.Url(url, linkStyles)) { append(url) }
                    append(suffix)
                }
                else -> append(match.value)
            }
            cursor = match.range.last + 1
        }
        if (cursor < source.length) append(source.substring(cursor))
    }
}

