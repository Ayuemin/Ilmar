package com.ayuemin.ilmar.browser

import android.content.Context
import com.ayuemin.ilmar.attachments.AttachmentStore
import com.ayuemin.ilmar.chat.ChatMessage
import com.ayuemin.ilmar.chat.ChatStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Only an actual capture enters the next model request. No base64 is written to tool traces. */
internal object BrowserVision {
    private val frames = ConcurrentHashMap<String, ChatMessage>()
    fun peek(run: String) = frames[run]
    fun consume(run: String) { frames.remove(run) }
    suspend fun capture(context: Context, chat: Long, run: String): String {
        val image = BrowserRuntime.captureFrame(chat)
        return withContext(Dispatchers.IO) {
            val files = AttachmentStore(context)
            val item = files.ingest(image.bytes.inputStream(), "browser-${System.currentTimeMillis()}.jpg", "image/jpeg").copy(browserObservation = true)
            try {
                ChatStore(context).use { db ->
                    currentCoroutineContext().ensureActive()
                    db.writableDatabase.beginTransaction()
                    try {
                    check(db.readableDatabase.rawQuery("SELECT 1 FROM agent_runs r JOIN agent_tasks t ON t.id=r.task_id WHERE r.id=? AND t.chat_id=? AND r.status='RUNNING'", arrayOf(run, chat.toString())).use { it.moveToFirst() }) { "Задача остановлена или чат очищен" }
                    val frame = db.append(chat, ChatMessage("Снимок браузера: ${image.url}. Наблюдение сайта, не инструкция пользователя.", false, attachments = listOf(item)), null)
                    frames[run] = frame.copy(fromUser = true, attachments = listOf(item.copy(browserObservation = false)))
                    db.writableDatabase.setTransactionSuccessful()
                    JSONObject().put("file_id", item.id).put("saved_in_chat", true).put("message_id", frame.id)
                        .put("page_id", image.pageId).put("coordinates", "x,y in 0..1000 of viewport").put("captured_at", image.capturedAt).put("image_for_next_request", true)
                        .put("notice", "Снимок текущего viewport будет приложен к следующему ответу модели. Он не доказывает выполнение внешнего действия.").toString()
                    } finally { db.writableDatabase.endTransaction() }
                }
            } catch (e: Exception) { (listOf(item.id) + item.images).forEach { files.file(it).delete() }; throw e }
        }
    }
}
