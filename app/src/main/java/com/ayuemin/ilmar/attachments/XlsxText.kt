package com.ayuemin.ilmar.attachments

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

/** Small OOXML reader: cached values only; never executes formulas, macros or external links. */
internal object XlsxText {
    fun read(file: File): String = ZipFile(file).use { zip ->
        require(zip.size() <= 2000) { "Слишком много компонентов XLSX" }
        var expanded = 0
        fun parser(name: String): XmlPullParser {
            val entry = zip.getEntry(name) ?: error("В XLSX отсутствует $name")
            val bytes = zip.getInputStream(entry).use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break
                    expanded += n; require(expanded <= 8 * 1024 * 1024 && output.size() + n <= 2 * 1024 * 1024) { "Распакованный XLSX слишком велик" }; output.write(buffer, 0, n) }
                output.toByteArray()
            }
            require(!bytes.toString(Charsets.UTF_8).contains(Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE))) { "DTD в XLSX запрещён" }
            return Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false); setInput(ByteArrayInputStream(bytes), "UTF-8") }
        }
        val strings = mutableListOf<String>()
        if (zip.getEntry("xl/sharedStrings.xml") != null) {
            val p = parser("xl/sharedStrings.xml"); var current = StringBuilder()
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "si") current = StringBuilder()
                if (p.eventType == XmlPullParser.START_TAG && p.name == "t") current.append(p.nextText())
                if (p.eventType == XmlPullParser.END_TAG && p.name == "si") { require(strings.size < 20000 && current.length <= 32000); strings += current.toString() }
                p.next()
            }
        }
        val sheets = zip.entries().asSequence().map { it.name }.filter { it.matches(Regex("xl/worksheets/sheet[0-9]+\\.xml")) }.sorted().toList()
        require(sheets.size in 1..10) { "XLSX: от 1 до 10 листов" }
        val text = StringBuilder("XLSX: исходные/кэшированные значения; формулы не выполнялись, формат дат и чисел не применялся.\n")
        sheets.forEach { name ->
            text.append("\nЛист: $name\n"); val p = parser(name); var type = ""; var cell = ""; var count = 0
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
                    "c" -> { type = p.getAttributeValue(null, "t").orEmpty(); cell = p.getAttributeValue(null, "r").orEmpty(); require(++count <= 20000) { "Слишком много ячеек" } }
                    "v", "t" -> { val raw = p.nextText(); val value = if (type == "s") strings.getOrNull(raw.toIntOrNull() ?: -1) ?: error("Некорректная таблица строк XLSX") else raw
                        text.append(cell).append(": ").append(value).append('\n') }
                }
                require(text.length <= 256 * 1024) { "В XLSX слишком много текста" }; p.next()
            }
        }
        text.toString()
    }
}
