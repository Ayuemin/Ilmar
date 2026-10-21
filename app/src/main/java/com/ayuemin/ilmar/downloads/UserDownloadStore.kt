package com.ayuemin.ilmar.downloads

import android.content.ContentValues
import android.content.Context
import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.UUID

internal data class SavedUserDownload(val id: String, val location: String, val uri: String, val bytes: Long) {
    // No body, excerpt, image, or attachment enters tool history.
    fun modelResult() = JSONObject().put("download_id", id).put("status", "saved_for_user")
        .put("location", location).put("user_only", true).put("content_available_to_model", false).put("untrusted", true)
}
internal interface DownloadDestination {
    val output: OutputStream
    fun complete(bytes: Long): SavedUserDownload
    fun abort()
}

/** User downloads are separate from chat attachments and all model-readable stores. */
internal class UserDownloadStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("ilmar_user_downloads", Context.MODE_PRIVATE)
    private fun key(url: String) = url.toHttpUrl().let { "${it.scheme}://${it.host}:${it.port}${it.encodedPath}" }
    @Synchronized fun protect(url: String) {
        val values = prefs.getStringSet("protected", emptySet()).orEmpty().toMutableSet(); values += key(url)
        check(prefs.edit().putStringSet("protected", values).commit()) { "Не удалось защитить пользовательскую загрузку" }
    }
    fun requireModelReadable(url: String) {
        require(runCatching { key(url) !in prefs.getStringSet("protected", emptySet()).orEmpty() }.getOrDefault(false)) {
            "Этот URL предназначен для загрузки пользователю. Его содержимое недоступно модели."
        }
    }
    fun modelReadable(url: String) = runCatching { requireModelReadable(url); true }.getOrDefault(false)
    @SuppressLint("Recycle") // Ownership transfers to DownloadDestination: complete()/abort() both close the stream.
    fun destination(name: String, mime: String): DownloadDestination {
        val id = "download:" + UUID.randomUUID().toString()
        val safe = safeName(name)
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = app.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Ilmar")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("Нет доступа к папке «Загрузки»")
            val stream = try { resolver.openOutputStream(uri, "w") ?: error("Не удалось открыть файл в «Загрузках»") }
                catch (e: Exception) { resolver.delete(uri, null, null); throw e }
            return object : DownloadDestination {
                override val output = stream
                private var published = false
                override fun complete(bytes: Long): SavedUserDownload {
                    output.close()
                    check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
                    published = true
                    val actual = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: safe
                    return SavedUserDownload(id, "Download/Ilmar/$actual", uri.toString(), bytes).also(::remember)
                }
                override fun abort() { runCatching { output.close() }; if (!published) resolver.delete(uri, null, null) }
            }
        }
        // Android 8/9: app-scoped external Downloads needs no broad storage permission.
        val folder = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: error("Внешнее хранилище недоступно")
        check(folder.exists() || folder.mkdirs())
        val file = File(folder, UUID.randomUUID().toString() + "-" + safe)
        check(file.createNewFile()) { "Не удалось создать новый файл загрузки" }
        val stream = try { file.outputStream() } catch (e: Exception) { file.delete(); throw e }
        return object : DownloadDestination {
            override val output = stream
            private var published = false
            override fun complete(bytes: Long): SavedUserDownload {
                output.close(); published = true
                val uri = FileProvider.getUriForFile(app, app.packageName + ".files", file)
                return SavedUserDownload(id, file.absolutePath, uri.toString(), bytes).also(::remember)
            }
            override fun abort() { runCatching { output.close() }; if (!published) file.delete() }
        }
    }
    private fun remember(file: SavedUserDownload) {
        // Metadata only. Shared public downloads survive chat deletion and task cancellation.
        val uris = prefs.getStringSet("user_uris", emptySet()).orEmpty().toMutableSet(); uris += file.uri
        val locations = prefs.getStringSet("user_locations", emptySet()).orEmpty().toMutableSet(); locations += file.location
        check(prefs.edit().putStringSet("user_uris", uris).putStringSet("user_locations", locations)
            .putString(file.id, JSONObject().put("location", file.location).put("uri", file.uri).put("bytes", file.bytes).toString()).commit())
    }
    fun isUserFile(uri: Uri): Boolean {
        val known = prefs.getStringSet("user_uris", emptySet()).orEmpty()
        if (uri.toString() in known) return true
        if (known.any { raw ->
            val saved = Uri.parse(raw)
            saved.scheme == uri.scheme && saved.authority == uri.authority &&
                (saved.path == uri.path || uri.authority == "media" && saved.lastPathSegment == uri.lastPathSegment)
        }) return true
        val document = runCatching { android.provider.DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return false
        if (uri.authority == "com.android.providers.downloads.documents" && document.startsWith("msf:"))
            return known.any { Uri.parse(it).lastPathSegment == document.substringAfter(':') }
        val path = document.substringAfter(':', document)
        return path in prefs.getStringSet("user_locations", emptySet()).orEmpty()
    }
    companion object {
        fun safeName(name: String): String {
            var safe = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_").trim().take(160).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "download"
            while (safe.toByteArray(Charsets.UTF_8).size > 180) safe = safe.dropLast(1)
            if (safe.lastOrNull()?.isHighSurrogate() == true) safe = safe.dropLast(1)
            return safe
        }
    }
}
