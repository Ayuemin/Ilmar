package com.ayuemin.ilmar.model

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Accept common API bases and explicit custom chat routes, without probing or retrying. */
object ChatEndpoint {
    fun resolve(value: String): String {
        require(ProviderRegistry.validEndpoint(value)) { "Требуется корректный HTTPS адрес API" }
        val url = value.trim().toHttpUrlOrNull() ?: error("Некорректный адрес API")
        val path = url.encodedPath.trimEnd('/')
        val chatPath = when (path) {
            "" -> "/v1/chat/completions"
            "/v1", "/api/v1" -> "$path/chat/completions"
            else -> return url.toString() // Preserve full/custom routes exactly.
        }
        return url.newBuilder().encodedPath(chatPath).build().toString()
    }
}
