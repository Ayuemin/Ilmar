package com.ayuemin.ilmar.attachments

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.chat.ChatMessage
import com.ayuemin.ilmar.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AttachmentsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Test fun importsOriginalAndExtractedTextAndRejectsBinaryOrUnsupportedFiles() {
        val files = AttachmentStore(context)
        val item = files.ingest(ByteArrayInputStream("Привет".toByteArray()), "notes.txt", "text/plain")
        assertEquals("Привет", item.text)
        assertEquals("Привет", files.file(item.id).readText())
        assertEquals(listOf(item), ChatAttachment.decode(ChatAttachment.encode(listOf(item))))
        assertThrows(IllegalArgumentException::class.java) { files.ingest(ByteArrayInputStream(byteArrayOf(0, 1)), "bad.txt", "text/plain") }
        assertThrows(IllegalStateException::class.java) { files.ingest(ByteArrayInputStream(byteArrayOf(1)), "app.apk", "application/octet-stream") }
    }
    @Test fun docxTextIsExtractedWithoutXmlMarkup() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip -> zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("<document><p><t>First</t></p><p><t>Second</t></p></document>".toByteArray()); zip.closeEntry() }
        val item = AttachmentStore(context).ingest(ByteArrayInputStream(bytes.toByteArray()), "doc.docx", "")
        assertEquals("First\nSecond\n", item.text)
    }
    @Test fun imagePayloadRequiresCapabilityAndKeepsTextAndOriginalIdOutOfNetwork() {
        val files = AttachmentStore(context)
        files.file("image-test").writeBytes(byteArrayOf(1, 2, 3))
        val message = ChatMessage("describe", true, 99, listOf(ChatAttachment("original", "photo.jpg", "image/jpeg", 3, images = listOf("image-test"))))
        assertThrows(AttachmentFailure::class.java) { AttachmentPayload.build(listOf(message), ModelCapabilities(), files) }
        val content = AttachmentPayload.build(listOf(message), ModelCapabilities(inputModalities = setOf("image")), files)
        val body = GenerationPayload.build("vision", listOf(message), GenerationOptions(attachmentContent = content))
        val parts = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("describe", parts.getJSONObject(0).getString("text"))
        assertEquals("data:image/jpeg;base64,AQID", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertFalse(body.toString().contains("original"))
    }
    @Test fun catalogPersistsInputAndOutputModalities() {
        val caps = ModelCapabilities.parse(JSONObject("""{"architecture":{"input_modalities":["text","image"],"output_modalities":["audio"]}}"""))
        assertTrue("image" in caps.inputModalities)
        assertTrue("audio" in caps.outputModalities)
        assertEquals(caps, ModelCapabilities.fromJson(caps.toJson()))
    }
}
