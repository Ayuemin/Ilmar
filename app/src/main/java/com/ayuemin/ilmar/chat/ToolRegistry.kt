package com.ayuemin.ilmar.chat

import com.ayuemin.ilmar.agent.BoundTool
import com.ayuemin.ilmar.agent.ToolPermissionBroker
import com.ayuemin.ilmar.model.ToolSchema
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Only application-owned executors can register; downloaded plugins remain out of scope. */
internal object ToolRegistry {
    data class Descriptor(val id: String, val title: String, val configured: Boolean, val executorRegistered: Boolean)
    private data class Entry(val descriptor: Descriptor, val schema: ToolSchema, val executor: suspend (JSONObject) -> String)
    private val installed = ConcurrentHashMap<String, Entry>()
    fun register(id: String, title: String, schema: ToolSchema, executor: suspend (JSONObject) -> String) {
        require(schema.name.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        require(installed.values.none { it.schema.name == schema.name && it.descriptor.id != id })
        installed[id] = Entry(Descriptor(id, title, true, true), schema, executor)
    }
    fun unregister(id: String) { installed.remove(id) }
    fun catalogue(): List<Descriptor> {
        val base = listOf("SSH", "Shell", "Браузер").map { Descriptor(it, it, false, false) }
        return base.filterNot { it.id in installed } + installed.values.map { it.descriptor }.sortedBy { it.id }
    }
    fun canExecute(id: String, enabledForChat: Boolean, permissionGranted: Boolean): Boolean =
        enabledForChat && permissionGranted && installed.containsKey(id)
    fun availableIds(): Set<String> = installed.keys.toSet()
    fun authorized(chatId: Long, modes: ChatModePreferences, broker: ToolPermissionBroker): List<BoundTool> =
        installed.values.filter { canExecute(it.descriptor.id, modes.enabled(chatId, "tool_${it.descriptor.id}"), broker.granted(it.descriptor.id)) }.map { entry ->
            BoundTool(entry.schema) { arguments ->
                val id = entry.descriptor.id
                check(canExecute(id, modes.enabled(chatId, "tool_$id"), broker.granted(id))) { "Разрешение инструмента отозвано" }
                check(installed[id] === entry) { "Исполнитель изменился" }
                entry.executor(arguments)
            }
        }
}
