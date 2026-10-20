package com.ayuemin.ilmar.attachments

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.util.UUID
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser

/** Originals stay private; images/PDF pages are normalized once, then reused across turns/branches. */
class AttachmentStore(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "attachments").apply { mkdirs() }
    fun file(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9.-]+"))) { "Некорректное вложение" }
        return File(root, id)
    }
    suspend fun import(uri: Uri): ChatAttachment = withContext(Dispatchers.IO) {
        val resolver = app.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }?.take(160) ?: "Файл"
        val mime = resolver.getType(uri).orEmpty()
        resolver.openInputStream(uri)?.use { ingest(it, name, mime) } ?: error("Не удалось открыть файл")
    }
    fun ingest(input: InputStream, name: String, mime: String): ChatAttachment {
        val created = mutableListOf<File>()
        fun newFile() = file(UUID.randomUUID().toString()).also { created += it }
        try {
            val original = newFile()
            original.outputStream().use { output -> copyLimited(input, output, 20 * 1024 * 1024) }
            val ext = name.substringAfterLast('.', "").lowercase()
            val image = mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp")
            val images = mutableListOf<String>()
            val text: String?
            when {
                image -> { val target = newFile(); normalizeImage(original, target); images += target.name; text = null }
                mime == "application/pdf" || ext == "pdf" -> {
                    ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                        PdfRenderer(fd).use { pdf ->
                            require(pdf.pageCount in 1..12) { "PDF: поддерживается от 1 до 12 страниц" }
                            repeat(pdf.pageCount) { pageIndex -> pdf.openPage(pageIndex).use { page ->
                                val scale = minOf(1400f / maxOf(page.width, page.height), 2f)
                                val bitmap = Bitmap.createBitmap(maxOf(1, (page.width * scale).toInt()), maxOf(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888)
                                try {
                                    bitmap.eraseColor(Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    val target = newFile(); target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }; images += target.name
                                } finally { bitmap.recycle() }
                            } }
                        }
                    }; text = null
                }
                ext == "docx" || mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> text = docxText(original)
                mime.startsWith("text/") || ext in setOf("txt", "md", "csv", "json", "xml", "yaml", "yml", "log", "kt", "py", "js", "html") || mime == "application/json" -> {
                    require(original.length() <= 256 * 1024) { "Текстовый файл: не больше 256 КБ" }
                    val bytes = original.readBytes(); require(bytes.none { it == 0.toByte() }) { "Файл не является текстовым" }
                    text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                }
                else -> error("Поддерживаются изображения, PDF, DOCX и текстовые файлы UTF-8")
            }
            require(text == null || text.isNotBlank()) { "В документе нет доступного текста" }
            require(images.sumOf { file(it).length() } <= 12 * 1024 * 1024) { "Изображения документа слишком велики" }
            return ChatAttachment(original.name, name, mime.ifBlank { when (ext) { "pdf" -> "application/pdf"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; else -> "application/octet-stream" } }, original.length(), text, images)
        } catch (e: Exception) { created.forEach { it.delete() }; throw e }
    }
    fun dataUrl(id: String): String {
        val image = file(id); require(image.exists()) { "Файл вложения отсутствует" }
        return "data:image/jpeg;base64," + Base64.encodeToString(image.readBytes(), Base64.NO_WRAP)
    }
    private fun normalizeImage(source: File, target: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Формат изображения не поддерживается" }
        var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Не удалось прочитать изображение")
        val scale = minOf(1f, 1600f / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            when (runCatching { ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)) {
                2 -> postScale(-1f, 1f); 3 -> postRotate(180f); 4 -> postScale(1f, -1f)
                5 -> { postRotate(90f); postScale(-1f, 1f) }; 6 -> postRotate(90f)
                7 -> { postRotate(270f); postScale(-1f, 1f) }; 8 -> postRotate(270f)
            }
        }
        val adjusted = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        try { target.outputStream().use { adjusted.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        finally { if (adjusted !== decoded) adjusted.recycle(); decoded.recycle() }
    }
    private fun docxText(source: File): String {
        ZipInputStream(source.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name != "word/document.xml") continue
                val xml = ByteArrayOutputStream(); copyLimited(zip, xml, 2 * 1024 * 1024)
                val parser = Xml.newPullParser().apply { setInput(ByteArrayInputStream(xml.toByteArray()), "UTF-8") }
                val result = StringBuilder()
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.TEXT) result.append(parser.text)
                    if (parser.eventType == XmlPullParser.END_TAG && parser.name == "p") result.append('\n')
                    require(result.length <= 256 * 1024) { "В DOCX слишком много текста" }; parser.next()
                }
                return result.toString()
            }
        }; error("В DOCX отсутствует документ")
    }
    companion object {
        private fun copyLimited(input: InputStream, output: OutputStream, limit: Int) {
            val buffer = ByteArray(8192); var total = 0
            while (true) { val n = input.read(buffer); if (n < 0) break; total += n
                require(total <= limit) { "Файл превышает допустимый размер" }; output.write(buffer, 0, n) }
        }
    }
}
