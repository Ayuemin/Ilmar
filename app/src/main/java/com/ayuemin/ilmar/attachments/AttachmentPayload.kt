package com.ayuemin.ilmar.attachments

import com.ayuemin.ilmar.chat.ChatMessage
import com.ayuemin.ilmar.model.ModelCapabilities
import org.json.JSONArray
import org.json.JSONObject

internal class AttachmentFailure(message: String) : IllegalArgumentException(message)

internal object AttachmentPayload {
    fun build(history: List<ChatMessage>, capabilities: ModelCapabilities, files: AttachmentStore): Map<Long, JSONArray> {
        val attachments = history.flatMap { it.attachments }
        if (attachments.any { it.images.isNotEmpty() } && "image" !in capabilities.inputModalities) throw AttachmentFailure(
            "Каталог не подтвердил поддержку изображений. Выбери модель с входом image и обнови каталог. PDF отправляется страницами как изображения.")
        if (attachments.flatMap { it.images }.sumOf { files.file(it).length() } > com.ayuemin.ilmar.settings.RuntimeLimits.mib("request_images_mib")) throw AttachmentFailure(
            "Изображения текущего запроса превышают настроенный размер. Включи автоматический Compact или увеличь глобальный лимит изображений запроса.")
        return history.filter { it.attachments.isNotEmpty() }.associate { message ->
            message.id to JSONArray().apply {
                put(JSONObject().put("type", "text").put("text", message.text.ifBlank { "Изучи вложения" }))
                message.attachments.forEach { attachment ->
                    attachment.text?.let { put(JSONObject().put("type", "text").put("text", "Файл: ${attachment.name}\n$it")) }
                    attachment.images.forEach { image -> put(JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", files.dataUrl(image)))) }
                }
            }
        }
    }
}
