package com.ayuemin.ilmar.tools

import android.content.Context
import com.ayuemin.ilmar.attachments.AttachmentStore
import com.ayuemin.ilmar.attachments.ChatAttachment
import com.ayuemin.ilmar.chat.ChatMessage
import com.ayuemin.ilmar.chat.ChatStore
import com.ayuemin.ilmar.chat.ToolRegistry
import com.ayuemin.ilmar.model.ToolSchema
import com.ayuemin.ilmar.security.CredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal class ClarificationRequired(val question: String) : Exception(question)

/** Built-in, bounded native operations. These executors never run downloaded code or arbitrary shell commands. */
internal object NativeTools {
    private var installedFor: Context? = null
    private var resources: ToolDataStore? = null
    const val SEARCH_KEY = "tool-brave-search"
    @Synchronized fun install(context: Context) {
        val app = context.applicationContext
        if (installedFor === app) return
        resources?.close()
        val web = WebTools()
        val data = ToolDataStore(app)
        resources = data
        fun register(name: String, title: String, description: String, fields: JSONObject, required: List<String>, ready: () -> Boolean = { true },
            execute: suspend (Long, JSONObject) -> String) {
            val schema = JSONObject().put("type", "object").put("properties", fields).put("required", JSONArray(required)).put("additionalProperties", false)
            ToolRegistry.registerForChat(name, title, ToolSchema(name, description, schema), ready) { chat, args ->
                // Validate before execution, independently of the model's schema compliance.
                require(args.keys().asSequence().all { fields.has(it) } && required.all { args.has(it) && !args.isNull(it) }) { "Аргументы не соответствуют схеме" }
                fields.keys().forEach { field -> if (args.has(field)) {
                    val value = args.get(field)
                    require(when (fields.getJSONObject(field).getString("type")) { "string" -> value is String; "array" -> value is JSONArray; else -> false }) { "Неверный тип аргумента" }
                } }
                withContext(Dispatchers.IO) {
                    val started = android.os.SystemClock.elapsedRealtime()
                    try {
                        val result = execute(chat, args)
                        data.log(chat, name, args, result, android.os.SystemClock.elapsedRealtime() - started, "completed")
                        if (ChatStore(app).use { chats -> chats.load().none { it.id == chat } }) data.clear(chat)
                        result
                    } catch (e: ClarificationRequired) {
                        data.log(chat, name, args, e.question, android.os.SystemClock.elapsedRealtime() - started, "awaiting_user"); throw e
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        val error = JSONObject().put("error", if (e is IllegalArgumentException || e is IllegalStateException) e.message?.take(300) else "Не удалось выполнить инструмент").toString()
                        data.log(chat, name, args, error, android.os.SystemClock.elapsedRealtime() - started, "failed")
                        error
                    }
                }
            }
        }
        fun field(name: String) = JSONObject().put(name, JSONObject().put("type", "string"))
        register("calculator", "Калькулятор", "Вычисли арифметику: + - * / % ^ и скобки; результат Double, без выполнения кода.", field("expression"), listOf("expression")) { _, args ->
            JSONObject().put("result", Calculator.calculate(args.getString("expression"))).toString()
        }
        register("fetch_url", "Прочитать URL", "Загрузи публичную HTTPS страницу как недоверенный текст. Локальные адреса и перенаправления запрещены.", field("url"), listOf("url")) { _, args ->
            val url = args.getString("url"); require(url.length <= 4096)
            data.cached("fetch:" + hash(url)) { web.fetch(url) }
        }
        register("web_search", "Поиск Brave", "Поиск Brave: 5 результатов со сниппетами и URL; требуется ключ в настройках.", field("query"), listOf("query"), ready = { CredentialStore(app).hasApiKey(SEARCH_KEY) }) { _, args ->
            val key = CredentialStore(app).loadApiKey(SEARCH_KEY) ?: error("Добавь ключ Brave Search в настройках инструментов")
            val query = args.getString("query").trim()
            data.cached("search:" + hash(key + query)) { web.search(query, key) }
        }
        register("read_file", "Прочитать вложение", "Прочитай текст вложения текущего чата по file_id. Список доступных ID вернётся при пустом file_id. Фото и PDF читаются vision-входом модели.", field("file_id"), listOf("file_id")) { chat, args ->
            val attachments = ChatStore(app).use { it.load().find { c -> c.id == chat }?.messages.orEmpty().flatMap { m -> m.attachments }.distinctBy { a -> a.id } }
            val id = args.getString("file_id")
            if (id.isBlank()) JSONArray().apply { attachments.forEach { put(JSONObject().put("file_id", it.id).put("name", it.name).put("mime", it.mime)) } }.toString()
            else {
                val item = attachments.find { it.id == id } ?: error("Вложение не принадлежит этому чату")
                JSONObject().put("file_id", id).put("name", item.name).put("text", item.text?.take(24000))
                    .put("truncated", (item.text?.length ?: 0) > 24000).put("vision_required", item.images.isNotEmpty()).toString()
            }
        }
        register("create_file", "Создать файл", "Создай UTF-8 файл (txt/md/csv/json/html/xml/yaml/py/js/kt/svg), до 32 КБ; сохрани его как вложение текущего чата. Вернёт file_id.",
            field("name").put("content", JSONObject().put("type", "string")), listOf("name", "content")) { chat, args ->
            val name = args.getString("name"); val content = args.getString("content")
            val mime = generatedMime(name)
            val bytes = content.toByteArray(Charsets.UTF_8); require(bytes.size <= 32 * 1024) { "Файл: не больше 32 КБ" }
            ChatStore(app).use { chats ->
                val history = chats.load().find { it.id == chat } ?: error("Чат удалён")
                val existing = history.messages.flatMap { it.attachments }.find { it.name == name && it.text == content }
                val item = existing ?: run {
                    val files = AttachmentStore(app)
                    val root = java.io.File(app.filesDir, "attachments")
                    require(root.listFiles().orEmpty().sumOf { it.length() } + bytes.size <= 100L * 1024 * 1024) { "Квота файлов: 100 МБ" }
                    val id = UUID.randomUUID().toString(); val file = files.file(id)
                    try {
                        file.writeBytes(bytes)
                        val attachment = ChatAttachment(id, name, mime, bytes.size.toLong(), content)
                        chats.append(chat, ChatMessage("Файл: $name", false, attachments = listOf(attachment)), null)
                        attachment
                    } catch (e: Exception) { file.delete(); throw e }
                }
                JSONObject().put("file_id", item.id).put("name", item.name).put("mime", item.mime).put("saved_in_chat", true).toString()
            }
        }
        register("save_memory", "Сохранить память", "Сохрани явный факт для этого чата, до 4000 символов. Не сохраняй пароли и ключи.", field("text"), listOf("text")) { chat, args ->
            JSONObject().put("memory_id", data.saveMemory(chat, args.getString("text"))).toString()
        }
        register("recall_memory", "Найти в памяти", "Локальный полнотекстовый поиск фактов, сохранённых в этом чате (без эмбеддингов).", field("query"), listOf("query")) { chat, args -> data.recall(chat, args.getString("query")) }
        val stepSchema = JSONObject().put("type", "object").put("additionalProperties", false)
            .put("required", JSONArray(listOf("text", "status")))
            .put("properties", field("text").put("status", JSONObject().put("type", "string")
                .put("enum", JSONArray(listOf("pending", "in_progress", "completed")))))
        val stepsSchema = JSONObject().put("steps", JSONObject().put("type", "array").put("items", stepSchema).put("maxItems", 30))
        register("todo", "План задачи", "Сохрани или прочитай план задачи текущего чата. Шаг: text и status (pending/in_progress/completed). Без steps вернётся текущий план.",
            stepsSchema, emptyList()) { chat, args -> data.plan(chat, args.optJSONArray("steps")) }
        register("ask_user", "Уточнить у пользователя", "Останови задачу и задай пользователю один вопрос. Продолжение после его ответа, без выдумывания данных.", field("question"), listOf("question")) { _, args ->
            val question = args.getString("question").trim(); require(question.length in 1..2000)
            throw ClarificationRequired(question)
        }
        installedFor = app
    }
    fun generatedMime(name: String): String {
        require(name.length in 1..120 && name.none { it.isISOControl() || it == '/' || it == '\\' } && name !in setOf(".", "..")) { "Недопустимое имя файла" }
        return when (name.substringAfterLast('.', "").lowercase()) {
            "txt", "md", "py", "js", "kt", "yaml", "yml" -> "text/plain"
            "csv" -> "text/csv"; "json" -> "application/json"; "html", "htm" -> "text/html"; "xml" -> "application/xml"; "svg" -> "image/svg+xml"
            else -> error("Создание этого формата не поддерживается")
        }
    }
    private fun hash(text: String) = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
