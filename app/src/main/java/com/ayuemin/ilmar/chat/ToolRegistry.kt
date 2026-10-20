package com.ayuemin.ilmar.chat

import com.ayuemin.ilmar.agent.BoundTool
import com.ayuemin.ilmar.agent.ToolPermissionBroker
import com.ayuemin.ilmar.model.ToolSchema
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Only application-owned executors can register; downloaded plugins remain out of scope. */
internal object ToolRegistry {
    data class Descriptor(val id: String, val title: String, val configured: Boolean, val executorRegistered: Boolean)
    private data class Entry(val descriptor: Descriptor, val schema: ToolSchema, val ready: () -> Boolean, val executor: suspend (Long, JSONObject) -> String)
    private val installed = ConcurrentHashMap<String, Entry>()
    fun register(id: String, title: String, schema: ToolSchema, executor: suspend (JSONObject) -> String) {
        registerForChat(id, title, schema) { _, args -> executor(args) }
    }
    fun registerForChat(id: String, title: String, schema: ToolSchema, ready: () -> Boolean = { true }, executor: suspend (Long, JSONObject) -> String) {
        require(schema.name.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        require(installed.values.none { it.schema.name == schema.name && it.descriptor.id != id })
        installed[id] = Entry(Descriptor(id, title, true, true), schema, ready, executor)
    }
    fun unregister(id: String) { installed.remove(id) }
    fun catalogue(): List<Descriptor> {
        val base = listOf("SSH", "Shell").map { Descriptor(it, it, false, false) } + Descriptor("Браузер", "Браузер", true, true)
        return base.filterNot { installed.containsKey(it.id) } + installed.values.map { it.descriptor.copy(configured = it.ready()) }.sortedBy { it.id }
    }
    fun canExecute(id: String, enabledForChat: Boolean, permissionGranted: Boolean): Boolean =
        enabledForChat && permissionGranted && installed[id]?.ready?.invoke() == true
    fun availableIds(): Set<String> = installed.filterValues { it.ready() }.keys.toSet() + "Браузер"
    fun authorized(chatId: Long, modes: ChatModePreferences, broker: ToolPermissionBroker): List<BoundTool> =
        installed.values.filter { canExecute(it.descriptor.id, modes.enabled(chatId, "tool_${it.descriptor.id}"), broker.granted(it.descriptor.id)) }.map { entry ->
            BoundTool(entry.schema) { arguments ->
                val id = entry.descriptor.id
                if (!canExecute(id, modes.enabled(chatId, "tool_$id"), broker.granted(id))) throw com.ayuemin.ilmar.agent.AgentFailure("PERMISSION_REVOKED", "$id: разрешение отозвано или инструмент больше не настроен.")
                check(installed[id] === entry) { "Исполнитель изменился" }
                entry.executor(chatId, arguments)
            }
        }
}
