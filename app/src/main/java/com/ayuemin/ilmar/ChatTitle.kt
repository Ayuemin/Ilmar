package com.ayuemin.ilmar

object ChatTitle {
    fun fromFirstMessage(message: String): String =
        message.trim().take(60).ifBlank { "Новый чат" }
}
