package com.ayuemin.ilmar.downloads

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.attachments.*
import com.ayuemin.ilmar.chat.ChatMessage
import com.ayuemin.ilmar.model.*
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UserDownloadMediaStoreTest {
    private lateinit var app: Context
    private lateinit var media: Media
    private class Media(private val root: File) : ContentProvider() {
        val rows = linkedMapOf<Uri, ContentValues>(); val files = linkedMapOf<Uri, File>(); var queries = 0
        override fun onCreate() = true
        override fun getType(uri: Uri) = rows[uri]?.getAsString(MediaStore.MediaColumns.MIME_TYPE)
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val item = Uri.withAppendedPath(uri, (rows.size + 1).toString()); rows[item] = ContentValues(values)
            files[item] = File.createTempFile("user-file-", ".bin", root); return item
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(files.getValue(uri), ParcelFileDescriptor.MODE_READ_WRITE)
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, order: String?): Cursor {
            queries++; val columns = projection ?: arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
            return MatrixCursor(columns).apply { rows[uri]?.let { row -> addRow(columns.map { row[it] }.toTypedArray()) } }
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int { rows[uri]?.putAll(values) ?: return 0; return 1 }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { files.remove(uri)?.delete(); return if (rows.remove(uri) != null) 1 else 0 }
    }
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext(); app.getSharedPreferences("ilmar_user_downloads", 0).edit().clear().commit()
        media = Media(app.cacheDir).apply { attachInfo(app, ProviderInfo().apply { authority = "media" }) }
        ShadowContentResolver.registerProviderInternal("media", media)
    }
    @Test fun sharedDownloadsPublishOnlyAfterCompletionAndRemainOutsideEveryModelAttachment() = runTest {
        val store = UserDownloadStore(app); val target = store.destination("report.bin", "application/octet-stream")
        val uri = media.rows.keys.single()
        assertEquals("Download/Ilmar", media.rows.getValue(uri).getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals(1, media.rows.getValue(uri).getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        val secret = "USER_FILE_ONLY_NEVER_SEND_THIS_TO_THE_MODEL"
        target.output.write(secret.toByteArray()); val saved = target.complete(secret.length.toLong())
        assertEquals(0, media.rows.getValue(uri).getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(secret, media.files.getValue(uri).readText()); assertEquals("Download/Ilmar/report.bin", saved.location)
        val queries = media.queries
        assertTrue(runCatching { AttachmentStore(app).import(uri) }.isFailure); assertEquals(queries, media.queries)
        val document = android.provider.DocumentsContract.buildDocumentUri("com.android.providers.downloads.documents", "msf:${uri.lastPathSegment}")
        assertTrue(store.isUserFile(document))
        assertTrue(store.isUserFile(uri.buildUpon().appendQueryParameter("alias", "true").build()))
        assertTrue(store.isUserFile(Uri.parse("content://media/external_primary/file/${uri.lastPathSegment}")))
        val history = listOf(ChatMessage(saved.modelResult().toString(), false))
        val payload = GenerationPayload.build("model", history, GenerationOptions(attachmentContent = AttachmentPayload.build(history, ModelCapabilities(), AttachmentStore(app))))
        assertTrue(payload.toString().contains(saved.id)); assertFalse(payload.toString().contains(secret)); assertFalse(payload.toString().contains("image_url"))
        assertTrue(history.single().attachments.isEmpty())
        target.abort(); assertTrue(media.files.getValue(uri).exists())
    }
    @Test fun interruptedDownloadsDeleteOnlyTheirOwnPendingFile() {
        val store = UserDownloadStore(app)
        val existing = store.destination("existing.zip", "application/zip"); existing.output.write(1); val complete = existing.complete(1)
        val userUri = Uri.parse(complete.uri)
        val partial = store.destination("partial.zip", "application/zip"); partial.output.write(2)
        partial.abort()
        assertEquals(setOf(userUri), media.rows.keys); assertTrue(media.files.getValue(userUri).exists())
    }
}
