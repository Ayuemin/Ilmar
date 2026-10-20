package com.ayuemin.ilmar.chat

/** Tool catalogue is independent of the UI and of permission grants.
 * A tool must have a registered executor before it becomes available.
 */
internal object ToolRegistry {
    data class Descriptor(
        val id: String,
        val title: String,
        val configured: Boolean,
        val executorRegistered: Boolean
    )

    fun catalogue(): List<Descriptor> = listOf(
        Descriptor("SSH", "SSH", configured = false, executorRegistered = false),
        Descriptor("Shell", "Shell", configured = false, executorRegistered = false),
        Descriptor("Браузер", "Браузер", configured = false, executorRegistered = false)
    )

    fun availableIds(): Set<String> =
        catalogue().filter { it.configured && it.executorRegistered }.map { it.id }.toSet()
}
