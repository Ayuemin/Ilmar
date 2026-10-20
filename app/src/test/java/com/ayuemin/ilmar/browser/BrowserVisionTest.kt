package com.ayuemin.ilmar.browser

import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.agent.BoundTool
import com.ayuemin.ilmar.attachments.*
import com.ayuemin.ilmar.chat.*
import com.ayuemin.ilmar.memory.*
import com.ayuemin.ilmar.model.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BrowserVisionTest {
    @Test fun imageObservationIsBoundedChargedToWindowAndSentOnceAfterToolResultNotInTraceOrFutureHistory() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        app.deleteDatabase("ilmar_chats.db")
        com.ayuemin.ilmar.settings.RuntimeLimits.install(app); com.ayuemin.ilmar.settings.RuntimeLimits.reset()
        val files = AttachmentStore(app); files.file("frame-test").writeBytes(byteArrayOf(1, 2, 3))
        val item = ChatAttachment("browser-original", "frame.jpg", "image/jpeg", 3, images = listOf("frame-test"), browserObservation = true)
        val saved = ChatMessage("Browser observation", false, 90, listOf(item))
        assertEquals(saved.attachments, ChatAttachment.decode(ChatAttachment.encode(saved.attachments)))
        assertTrue(AttachmentPayload.build(listOf(saved), ModelCapabilities(), files).isEmpty())
        val frame = saved.copy(fromUser = true, attachments = listOf(item.copy(browserObservation = false)))
        assertTrue(ContextEstimate.messages(listOf(frame)) >= ContextEstimate.messages(listOf(saved)) + 4096)
        var pending: ChatMessage? = null; var calls = 0; var firstInput = 0
        ChatStore(app).use { db ->
            db.create(1, "Vision"); db.append(1, ChatMessage("Inspect the page", true), null)
            val caps = ModelCapabilities(setOf("max_tokens"), contextLength = 32768, inputModalities = setOf("image"))
            val target = ModelTarget(ProviderConnection("p", "Test", "https://example.com/v1"), RegisteredModel("m", "p", "vision"), "key")
            val tools = listOf(
                BoundTool(ToolSchema("browser_screenshot", "Screenshot", JSONObject())) { pending = frame; "{\"saved_in_chat\":true,\"file_id\":\"browser-original\"}" },
                BoundTool(ToolSchema("calculator", "Read", JSONObject())) { "1" })
            ContextExecution(1, ContextStore(db), files, ContextPolicy(), caps, target, {},
                observation = { pending }, consumeObservation = { pending = null }, request = { history, options, _, input, _ ->
                    calls++
                    when (calls) {
                        1 -> { assertNull(options.observation); firstInput = input; GenerationResult("", toolCalls = listOf(ModelToolCall("shot", "browser_screenshot", "{}"))) }
                        2 -> {
                            assertTrue(input >= firstInput + 4096)
                            val body = GenerationPayload.build("vision", history, options)
                            val messages = body.getJSONArray("messages"); val last = messages.getJSONObject(messages.length() - 1)
                            assertEquals("user", last.getString("role")); assertTrue(last.toString().contains("data:image/jpeg;base64,AQID"))
                            assertEquals("tool", messages.getJSONObject(messages.length() - 2).getString("role"))
                            GenerationResult("", toolCalls = listOf(ModelToolCall("calc", "calculator", "{}")))
                        }
                        else -> { assertNull(options.observation); GenerationResult("Visual answer") }
                    }
                }).run(db.load().single().messages, false, null, tools)
            assertEquals(3, calls); assertNull(pending)
            db.readableDatabase.rawQuery("SELECT payload FROM tool_traces WHERE chat_id=1", null).use { rows -> while (rows.moveToNext()) assertFalse(rows.getString(0).contains("base64")) }
        }
    }
}
