package com.ayuemin.ilmar.browser

import android.content.Context
import com.ayuemin.ilmar.agent.AgentFailure
import com.ayuemin.ilmar.agent.BoundTool
import com.ayuemin.ilmar.agent.ToolOperationPolicy
import com.ayuemin.ilmar.downloads.UserDownloadStore
import com.ayuemin.ilmar.downloads.UserDownloadTransport
import com.ayuemin.ilmar.model.ToolSchema
import com.ayuemin.ilmar.settings.RuntimeLimits as Limits
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** A per-download UI approval is required; a model assertion cannot grant it. */
internal object BrowserDownload {
    fun tool(context: Context, chat: Long, control: BrowserRunControl, prepare: suspend () -> Unit, authorized: () -> Boolean,
        ask: suspend (Long, String, String) -> Boolean = BrowserPrompts::askDownload,
        transport: UserDownloadTransport = UserDownloadTransport()): BoundTool {
        val limit = Limits.mib("download_mib")
        var approved: String? = null
        fun validate(args: JSONObject) {
            require(args.keys().asSequence().all { it in setOf("url", "name") })
            require(args.get("url") is String); BrowserAddress.parse(args.getString("url"))
            if (args.has("name")) require(args.get("name") is String && args.getString("name").length <= 300)
        }
        suspend fun permission(args: JSONObject) {
            validate(args); prepare()
            val key = ToolOperationPolicy.canonical(args.toString())
            if (approved != key) {
                val name = args.optString("name").takeIf { it.isNotBlank() }?.let(UserDownloadStore::safeName) ?: "Файл с сайта"
                val folder = if (android.os.Build.VERSION.SDK_INT >= 29) "Загрузки/Ilmar" else "папку загрузок приложения (Android/data)"
                if (!ask(chat, "Скачать файл для вас?", "$name\n${args.getString("url")}\nСохранить в $folder? Файл предназначен только вам: модель получит ID и место сохранения, без содержимого. ${if (limit == 0L) "Лимит размера не установлен." else "Лимит: ${limit / 1024 / 1024} МиБ."}"))
                    throw AgentFailure("DOWNLOAD_CANCELLED", "Пользователь отменил скачивание. Файл не скачан; скрытого повтора нет.")
                if (!authorized()) throw AgentFailure("PERMISSION_REVOKED", "Режим изменён во время подтверждения загрузки")
                approved = key
            }
        }
        val fields = JSONObject().put("url", JSONObject().put("type", "string")).put("name", JSONObject().put("type", "string"))
        return BoundTool(ToolSchema("browser_download",
            "Скачать файл ТОЛЬКО по просьбе пользователя в папку Загрузки. Каждый файл требует кнопки Скачать в интерфейсе. Не использовать для чтения моделью: вернёт только ID, статус и место сохранения; содержимое недоступно read_file/vision. url — публичный HTTPS, name — необязательное имя. По умолчанию без лимита МиБ; потоковая запись на диск. Не предлагай самостоятельных загрузок.",
            JSONObject().put("type", "object").put("properties", fields).put("required", JSONArray(listOf("url"))).put("additionalProperties", false)),
            beforeExecute = { args -> if (runCatching { validate(args) }.isSuccess) permission(args) }) { args ->
            permission(args); approved = null
            currentCoroutineContext().ensureActive()
            control.actionStarted("browser_download")
            val store = UserDownloadStore(context)
            val headers = BrowserRuntime.downloadHeaders(chat, args.getString("url"))
            val file = transport.save(args.getString("url"), args.optString("name").takeIf { it.isNotBlank() }, headers, limit,
                protect = store::protect, authorized = {
                    if (!authorized()) throw AgentFailure("PERMISSION_REVOKED", "Режим браузера изменён; загрузка остановлена")
                }, destination = store::destination)
            val result = file.modelResult().toString()
            control.actionResult("browser_download", result)
            withContext(Dispatchers.Main.immediate) { android.widget.Toast.makeText(context, "Сохранено: ${file.location}", android.widget.Toast.LENGTH_LONG).show() }
            result
        }
    }
}
