package com.ayuemin.ilmar.model

import com.ayuemin.ilmar.settings.RuntimeLimits as Limits
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.EOFException
import java.io.IOException

/** Adapted from Umnik's stream parser. Ilmar still requires [DONE] before accepting a result. */
internal object SseReader {
    fun read(reader: BufferedReader, onDelta: (String) -> Unit): String = readResult(reader, onDelta).text
    fun readResult(reader: BufferedReader, onDelta: (String) -> Unit): GenerationResult {
        val text = StringBuilder()
        val event = mutableListOf<String>()
        val calls = sortedMapOf<Int, CallBuffer>()
        var done = false
        var finish: String? = null
        var usage = TokenUsage()
        fun dispatch() {
            if (event.isEmpty()) return
            val data = event.joinToString("\n").trim(); event.clear()
            if (data.isEmpty()) return
            if (data == "[DONE]") { done = true; return }
            val root = JSONObject(data)
            if (root.has("error") && !root.isNull("error")) throw IOException("Ошибка потока провайдера")
            val exactReportedCost = if (root.optJSONObject("usage")?.has("cost") == true || root.has("cost")) {
                runCatching {
                    val precise = com.google.gson.JsonParser.parseString(data).asJsonObject
                    val value = precise.getAsJsonObject("usage")?.get("cost")?.takeUnless { it.isJsonNull }
                        ?: precise.get("cost")?.takeUnless { it.isJsonNull }
                    exactCost(value?.asString)
                }.getOrNull()
            } else null
            root.optJSONObject("usage")?.let { u ->
                usage = TokenUsage(nonNegativeLong(u, "prompt_tokens") ?: nonNegativeLong(u, "input_tokens") ?: usage.input,
                    nonNegativeLong(u, "completion_tokens") ?: nonNegativeLong(u, "output_tokens") ?: usage.output,
                    nonNegativeLong(u, "total_tokens") ?: usage.total,
                    nonNegativeLong(u.optJSONObject("prompt_tokens_details"), "cached_tokens") ?: usage.cached,
                    nonNegativeLong(u.optJSONObject("completion_tokens_details"), "reasoning_tokens") ?: usage.reasoning,
                    exactReportedCost ?: usage.costUsd)
            }
            exactReportedCost?.let { usage = usage.copy(costUsd = it) }
            val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return
            if ((choice.has("error") && !choice.isNull("error")) || choice.optString("finish_reason") == "error")
                throw IOException("Ошибка генерации внутри потока")
            (choice.opt("finish_reason") as? String)?.let { finish = it }
            val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return
            val piece = contentText(delta.opt("content"))
            if (piece.isNotEmpty()) { text.append(piece); onDelta(piece) }
            delta.optJSONArray("tool_calls")?.let { parts ->
                for (index in 0 until parts.length()) {
                    val part = parts.getJSONObject(index)
                    val call = calls.getOrPut(part.optInt("index", index)) { CallBuffer() }
                    (part.opt("id") as? String)?.let { call.id = merge(call.id, it) }
                    part.optJSONObject("function")?.let { fn ->
                        (fn.opt("name") as? String)?.let { call.name = merge(call.name, it) }
                        (fn.opt("arguments") as? String)?.let {
                            val bytes = it.toByteArray(Charsets.UTF_8).size
                            if (call.bytes.toLong() + bytes > Limits.bytes("argument_kib")) throw IOException("Аргументы инструмента превышают глобальный лимит; измени расширенные настройки")
                            call.bytes += bytes; call.arguments.append(it)
                        }
                    }
                }
                val maxCalls = Limits.get("calls")
                if (maxCalls > 0 && calls.size > maxCalls) throw IOException("Слишком много инструментальных вызовов; проверь глобальный лимит")
            }
        }
        while (!done) {
            val line = reader.readLine() ?: break
            when { line.isEmpty() -> dispatch(); line.startsWith("data:") -> event.add(line.substring(5).trimStart()) }
        }
        if (!done) dispatch()
        if (!done) throw EOFException("Поток прерван до завершения")
        val tools = calls.values.map {
            if (it.id.isBlank() || it.name.isBlank()) throw IOException("Неполный вызов инструмента")
            ModelToolCall(it.id, it.name, it.arguments.toString())
        }
        if (text.isBlank() && tools.isEmpty()) throw ProviderFailure(
            if (finish == "length") "Модель исчерпала лимит вывода без текста. Увеличь лимит или выбери другую модель Compact."
            else "Модель завершила поток без текста ответа",
            details = "empty_response; finish_reason=${finish ?: "unknown"}; output_tokens=${usage.output}; reasoning_tokens=${usage.reasoning}")
        return GenerationResult(text.toString(), usage, finish, tools)
    }
    private class CallBuffer(var id: String = "", var name: String = "", val arguments: StringBuilder = StringBuilder(), var bytes: Int = 0)
    private fun merge(current: String, part: String) = current + part
    private fun contentText(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> (0 until value.length()).joinToString("") { i ->
            when (val part = value.opt(i)) { is String -> part; is JSONObject -> part.opt("text") as? String ?: ""; else -> "" }
        }
        else -> ""
    }
}
