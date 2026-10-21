package com.ayuemin.ilmar.documents

import com.ayuemin.ilmar.tools.FetchedDocument
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.text.BreakIterator
import java.util.Locale

internal data class DocumentBlock(val index: Int, val kind: String, val section: String, val start: Int, val end: Int, val paragraph: Int)
internal data class DocumentSection(val id: String, val level: Int, val title: String, val path: List<String>, val first: Int, val until: Int)
internal data class StructuredDocument(val version: String, val title: String, val text: String,
    val blocks: List<DocumentBlock>, val sections: List<DocumentSection>) {
    fun text(block: DocumentBlock) = text.substring(block.start, block.end)
    val retainedBytes get() = 2L * (text.length + title.length + sections.sumOf { it.title.length + it.path.sumOf(String::length) }) + blocks.size * 64L
}

/** Original source is retained separately. These offsets refer to ordered extracted text, not HTML bytes. */
internal object DocumentExtraction {
    private data class Part(val kind: String, val text: String, val level: Int = 0)
    fun extract(source: FetchedDocument): StructuredDocument {
        val parts = mutableListOf<Part>()
        fun add(kind: String, text: String, level: Int = 0) {
            if (text.isBlank()) return
            require(parts.size < 12000) { "DOCUMENT_STRUCTURE_LIMIT: слишком много блоков; документ не индексирован" }
            parts += Part(kind, text.trim(), level)
        }
        var title = ""
        when {
            source.mediaType.contains("html", true) -> {
                val html = Jsoup.parse(source.source, source.url)
                title = html.title()
                // A header may contain useful title/conditions. Keep it; do not choose only the first main/article.
                html.select("script,style,noscript,iframe,svg,template").remove()
                html.select("nav").filter { it.select("p,table,li").isEmpty() && it.text() == it.select("a").text() }.forEach { it.remove() }
                fun inline(node: Node, depth: Int = 0): String {
                    require(depth < 128) { "DOCUMENT_STRUCTURE_LIMIT: слишком глубокая HTML-структура" }
                    return when (node) {
                    is TextNode -> node.text()
                    is Element -> when (node.normalName()) {
                        "br" -> "\n"
                        "a" -> {
                            val label = node.childNodes().joinToString("") { inline(it, depth + 1) }
                            val url = node.absUrl("href")
                            if (url.startsWith("https://") || url.startsWith("http://")) "$label <$url>" else label
                        }
                        else -> node.childNodes().joinToString("") { inline(it, depth + 1) }
                    }
                    else -> ""
                    }
                }
                fun walk(element: Element, depth: Int = 0) {
                    require(depth < 128) { "DOCUMENT_STRUCTURE_LIMIT: слишком глубокая HTML-структура" }
                    val tag = element.normalName()
                    when {
                        tag.matches(Regex("h[1-6]")) -> add("heading", element.text(), tag.last().digitToInt())
                        tag == "table" -> element.select("tr").forEach { row ->
                            add("table_row", row.children().filter { it.normalName() in setOf("td", "th") }.joinToString(" | ") { inline(it).trim() })
                        }
                        tag == "pre" -> add("code", element.wholeText())
                        tag == "li" -> {
                            val copy = element.clone(); copy.select("ul,ol").remove()
                            add("list_item", inline(copy))
                            element.children().filter { it.normalName() in setOf("ul", "ol") }.forEach { walk(it, depth + 1) }
                        }
                        tag in setOf("p", "blockquote", "dt", "dd") && element.select("table,ul,ol,h1,h2,h3,h4,h5,h6,p").none { it !== element } -> add("paragraph", inline(element))
                        else -> {
                            val pending = StringBuilder()
                            fun flush() { add("paragraph", pending.toString()); pending.clear() }
                            element.childNodes().forEach { node ->
                                if (node is Element && (node.isBlock || node.normalName() in setOf("ul", "ol", "table"))) { flush(); walk(node, depth + 1) }
                                else pending.append(inline(node))
                            }
                            flush()
                        }
                    }
                }
                walk(html.body())
            }
            source.mediaType.contains("markdown", true) || source.url.substringBefore('?').endsWith(".md") -> {
                var fence: Char? = null
                val paragraph = StringBuilder()
                fun flush() { add(if (fence != null) "code" else "paragraph", paragraph.toString()); paragraph.clear() }
                val lines = source.source.lines()
                var i = 0
                while (i < lines.size) {
                    val line = lines[i]; val trimmed = line.trimStart()
                    val marker = trimmed.take(3)
                    if (marker == "```" || marker == "~~~") {
                        flush()
                        fence = if (fence == marker.first()) null else marker.first()
                    } else if (fence != null) paragraph.append(line).append('\n')
                    else {
                        val heading = Regex("^ {0,3}(#{1,6})\\s+(.+?)(?:\\s+#+)?$").matchEntire(line)
                        val setext = lines.getOrNull(i + 1)?.trim()?.let { it.matches(Regex("=+|-+")) } == true && line.isNotBlank()
                        when {
                            heading != null -> { flush(); add("heading", heading.groupValues[2], heading.groupValues[1].length) }
                            setext -> { flush(); add("heading", line, if (lines[++i].trim().startsWith('=')) 1 else 2) }
                            line.isBlank() -> flush()
                            trimmed.startsWith('|') -> { flush(); add("table_row", line) }
                            trimmed.matches(Regex("(?:[-+*]|[0-9]+[.)])\\s+.*")) -> { flush(); add("list_item", line) }
                            else -> paragraph.append(line).append('\n')
                        }
                    }
                    i++
                }
                flush()
            }
            else -> source.source.split(Regex("\\n\\s*\\n")).forEach { add("paragraph", it) }
        }
        val text = StringBuilder()
        val blocks = mutableListOf<DocumentBlock>()
        data class Heading(val id: String, val level: Int, val title: String, val path: List<String>, val first: Int, var until: Int = 0)
        val headings = mutableListOf(Heading("s0", 0, "Document", emptyList(), 0))
        val stack = mutableListOf(headings.first())
        parts.forEachIndexed { paragraph, part ->
            if (part.level > 0) {
                while (stack.size > 1 && stack.last().level >= part.level) stack.removeAt(stack.lastIndex).until = blocks.size
                val heading = Heading("s${headings.size}", part.level, part.text, stack.drop(1).map { it.title } + part.text, blocks.size)
                headings += heading; stack += heading
            }
            val chunks = if (part.kind == "paragraph") sentences(part.text) else listOf(part.text)
            chunks.forEach { chunk ->
                require(blocks.size < 12000 && text.length.toLong() + chunk.length < 4L * 1024 * 1024) {
                    "DOCUMENT_STRUCTURE_LIMIT: ресурсный предел извлечения; документ не индексирован"
                }
                if (text.isNotEmpty()) text.append('\n')
                val start = text.length; text.append(chunk)
                blocks += DocumentBlock(blocks.size, part.kind, stack.last().id, start, text.length, paragraph)
            }
        }
        stack.forEach { it.until = blocks.size }
        if (title.isBlank()) title = parts.firstOrNull { it.kind == "heading" }?.text ?: source.url
        return StructuredDocument(com.ayuemin.ilmar.memory.ContextEstimate.hash(source.source), title, text.toString(), blocks,
            headings.map { DocumentSection(it.id, it.level, it.title, it.path, it.first, it.until) })
    }
    private fun sentences(text: String): List<String> {
        val iterator = BreakIterator.getSentenceInstance(Locale.ROOT).apply { setText(text) }
        val result = mutableListOf<String>(); var start = iterator.first(); var end = iterator.next()
        while (end != BreakIterator.DONE) {
            text.substring(start, end).trim().takeIf { it.isNotBlank() }?.let(result::add)
            start = end; end = iterator.next()
        }
        return result.ifEmpty { listOf(text) }
    }
}
