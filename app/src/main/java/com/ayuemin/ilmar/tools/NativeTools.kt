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
import com.ayuemin.ilmar.settings.RuntimeLimits as Limits

internal class ClarificationRequired(val question: String) : Exception(question)

/** Built-in, bounded native operations. These executors never run downloaded code or arbitrary shell commands. */
internal object NativeTools {
    private var installedFor: Context? = null
    private var resources: ToolDataStore? = null
    const val SEARCH_KEY = "tool-brave-search"
    @Synchronized fun install(context: Context) {
        val app = context.applicationContext
        Limits.install(app)
        if (installedFor === app) return
        resources?.close()
        val web = WebTools()
        val data = ToolDataStore(app)
        resources = data
        val diagnostics = com.ayuemin.ilmar.diagnostics.Diagnostics(app)
        fun register(name: String, title: String, description: String, fields: JSONObject, required: List<String>, ready: () -> Boolean = { true },
            execute: suspend (Long, JSONObject) -> String) {
            val schema = JSONObject().put("type", "object").put("properties", fields).put("required", JSONArray(required)).put("additionalProperties", false)
            ToolRegistry.registerForChat(name, title, ToolSchema(name, description, schema), ready) { chat, args ->
                withContext(Dispatchers.IO) {
                    val started = android.os.SystemClock.elapsedRealtime()
                    try {
                        // Validate before execution, independently of the model's schema compliance.
                        require(args.keys().asSequence().all { fields.has(it) } && required.all { args.has(it) && !args.isNull(it) }) { "Аргументы не соответствуют схеме" }
                        fields.keys().forEach { field -> if (args.has(field)) {
                            val value = args.get(field)
                            require(when (fields.getJSONObject(field).getString("type")) { "string" -> value is String; "array" -> value is JSONArray; "integer" -> value is Int || value is Long; else -> false }) { "Неверный тип аргумента" }
                        } }
                        val result = execute(chat, args)
                        diagnostics.record("TOOL_RESULT", chat, JSONObject().put("tool", name).put("arguments", args).put("result", result)
                            .put("latency_ms", android.os.SystemClock.elapsedRealtime() - started))
                        data.log(chat, name, args, result, android.os.SystemClock.elapsedRealtime() - started, "completed")
                        if (ChatStore(app).use { chats -> chats.load().none { it.id == chat } }) data.clear(chat)
                        result
                    } catch (e: ClarificationRequired) {
                        data.log(chat, name, args, e.question, android.os.SystemClock.elapsedRealtime() - started, "awaiting_user"); throw e
                    } catch (e: CancellationException) { diagnostics.record("TOOL_CANCELLED", chat, JSONObject().put("tool", name)); throw e }
                    catch (e: Exception) {
                        diagnostics.failure("TOOL_ERROR", chat, e, JSONObject().put("tool", name).put("arguments", args))
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
        val searchRegistry = SearchRegistry(app)
        register("web_search", "Поиск в интернете", "Поиск через выбранный пользователем API. Возвращает сниппеты и URL.", field("query"), listOf("query"), ready = { searchRegistry.ready() }) { chat, args ->
            val target = searchRegistry.target()
            val query = args.getString("query").trim()
            diagnostics.record("SEARCH_REQUEST", chat, JSONObject().put("profile", target.profile.name).put("host", target.profile.endpoint.substringBefore('?')).put("query", query))
            data.cached("search:" + hash(target.profile.json().toString() + target.headers.toString() + target.key + query)) { web.search(query, target) }
        }
        val readFields = field("file_id").put("offset", JSONObject().put("type", "integer").put("minimum", 0))
            .put("limit", JSONObject().put("type", "integer").put("minimum", 1))
        register("read_file", "Прочитать вложение / trace", "Читай вложения текущего чата частями: file_id, offset и limit (символы). Пустой file_id возвращает список. trace:<id> читает раунд tools; message:<id> — полный текст сообщения. PDF/фото используют vision-вход.", readFields, listOf("file_id")) { chat, args ->
            val id = args.getString("file_id")
            val offset = args.optInt("offset", 0); val limit = args.optInt("limit", Limits.get("excerpt_chars"))
            require(offset >= 0 && limit in 1..Limits.get("excerpt_chars")) { "Проверь offset/limit; максимум задаётся в расширенных лимитах" }
            ChatStore(app).use { store ->
                val history = store.load().find { it.id == chat }?.messages.orEmpty()
                val attachments = history.flatMap { it.attachments }.distinctBy { it.id }
                if (id.isBlank()) JSONObject().put("files", JSONArray().apply { attachments.takeLast(100).forEach { put(JSONObject().put("file_id", it.id).put("name", it.name).put("mime", it.mime)) } }).put("total", attachments.size).toString()
                else {
                    val item = attachments.find { it.id == id }
                    val text = if (id.startsWith("message:")) history.find { it.id == id.removePrefix("message:").toLongOrNull() }?.text ?: error("Сообщение не принадлежит этому чату")
                        else if (id.startsWith("trace:")) store.readToolTrace(chat, id.removePrefix("trace:")) ?: error("Trace не принадлежит этому чату")
                        else item?.text ?: if (item == null) error("Вложение не принадлежит этому чату") else "Текст отсутствует; используй vision для изображения/PDF."
                    require(offset <= text.length) { "offset за пределами текста (${text.length})" }
                    val end = (offset.toLong() + limit).coerceAtMost(text.length.toLong()).toInt()
                    JSONObject().put("file_id", id).put("name", item?.name).put("text", text.substring(offset, end))
                        .put("total_chars", text.length).put("next_offset", if (end < text.length) end else JSONObject.NULL)
                        .put("truncated", end < text.length).put("vision_required", item?.images?.isNotEmpty() == true).toString()
                }
            }
        }
        register("create_file", "Создать файл", "Создай UTF-8 файл (txt/md/csv/json/html/xml/yaml/py/js/kt/svg), с настраиваемым глобальным размером; сохрани его как вложение текущего чата. Вернёт file_id.",
            field("name").put("content", JSONObject().put("type", "string")), listOf("name", "content")) { chat, args ->
            val name = args.getString("name"); val content = args.getString("content")
            val mime = generatedMime(name)
            val bytes = content.toByteArray(Charsets.UTF_8); require(bytes.size <= Limits.bytes("create_kib")) { "Файл превышает глобальный лимит создания (${Limits.get("create_kib")} КиБ). Измени расширенные лимиты." }
            ChatStore(app).use { chats ->
                val history = chats.load().find { it.id == chat } ?: error("Чат удалён")
                val existing = history.messages.flatMap { it.attachments }.find { it.name == name && it.text == content }
                val item = existing ?: run {
                    val files = AttachmentStore(app)
                    val root = java.io.File(app.filesDir, "attachments")
                    require(Limits.get("storage_mib") == 0 || root.listFiles().orEmpty().sumOf { it.length() } + bytes.size <= Limits.mib("storage_mib")) { "Достигнута глобальная квота файлов. Измени расширенные лимиты." }
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
        val archive = com.ayuemin.ilmar.memory.ArchiveRetrieval(app, com.ayuemin.ilmar.model.ProviderRegistry(app))
        register("search_history", "Поиск в полной истории", "Найди исходные сообщения текущего чата. mode: lexical (SQLite FTS), semantic (настроенные API-эмбеддинги), hybrid (оба). Результат — справочные данные с ID; оригинал читай через read_file(message:<id>).", field("query").put("mode", JSONObject().put("type", "string").put("enum", JSONArray(listOf("lexical", "semantic", "hybrid")))), listOf("query")) { chat, args ->
            val query = args.getString("query").trim()
            require(query.isNotBlank() && query.length <= Limits.get("message_chars")) { "Пустой или слишком большой поисковый запрос" }
            val mode = args.optString("mode", "hybrid")
            require(mode in setOf("lexical", "semantic", "hybrid")) { "Неизвестный режим поиска" }
            val lexical = if (mode == "semantic") "" else ChatStore(app).use { com.ayuemin.ilmar.memory.ContextStore(it).search(chat, query) }
            val semantic = if (mode == "lexical") "" else archive.search(chat, query, Long.MAX_VALUE)
            JSONObject().put("lexical", lexical).put("semantic", semantic.take(Limits.get("excerpt_chars")))
                .put("note", "Оригиналы сохранены. Пустой semantic может означать, что эмбеддинги не настроены или индекс ещё не готов; используй lexical и read_file.").put("untrusted", true).toString()
        }
        register("save_memory", "Сохранить память", "Сохрани явный факт для этого чата, с настраиваемым размером. Не сохраняй пароли и ключи.", field("text"), listOf("text")) { chat, args ->
            JSONObject().put("memory_id", data.saveMemory(chat, args.getString("text"))).toString()
        }
        register("recall_memory", "Найти в памяти", "Локальный полнотекстовый поиск фактов, сохранённых в этом чате (без эмбеддингов).", field("query"), listOf("query")) { chat, args -> data.recall(chat, args.getString("query")) }
        val stepSchema = JSONObject().put("type", "object").put("additionalProperties", false)
            .put("required", JSONArray(listOf("text", "status")))
            .put("properties", field("text").put("status", JSONObject().put("type", "string")
                .put("enum", JSONArray(listOf("pending", "in_progress", "completed")))))
        val stepsSchema = JSONObject().put("steps", JSONObject().put("type", "array").put("items", stepSchema))
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
            "md" -> "text/markdown"
            "py" -> "text/x-python"; "js" -> "text/javascript"; "kt" -> "text/x-kotlin"; "yaml", "yml" -> "application/yaml"; "txt" -> "text/plain"
            "csv" -> "text/csv"; "json" -> "application/json"; "html", "htm" -> "text/html"; "xml" -> "application/xml"; "svg" -> "image/svg+xml"
            else -> error("Создание этого формата не поддерживается")
        }
    }
    private fun hash(text: String) = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
