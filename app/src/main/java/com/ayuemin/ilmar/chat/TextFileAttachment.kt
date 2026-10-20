package com.ayuemin.ilmar.chat

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Existing chat supports text only; bounded local files become an editable draft, never an automatic request. */
internal suspend fun readTextAttachment(resolver: ContentResolver, uri: Uri): String = withContext(Dispatchers.IO) {
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 64 * 1024) { "Текстовый файл должен быть не больше 64 КБ" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("Не удалось открыть файл")
    require(bytes.none { it == 0.toByte() }) { "Этот файл не является текстовым" }
    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
    decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
}
