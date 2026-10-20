package com.ayuemin.ilmar.diagnostics

import android.content.Context

/** Only fixed event codes and local chat IDs; no exception text, bodies, URLs, or credentials. */
class Diagnostics(context: Context) {
    private val prefs = context.getSharedPreferences("ilmar_diagnostics", Context.MODE_PRIVATE)
    @Synchronized fun record(code: String, chatId: Long) {
        require(code in setOf("START", "SUCCESS", "CANCELLED", "NETWORK_ERROR", "INTERRUPTED"))
        val line = "${System.currentTimeMillis()} $code chat=$chatId"
        prefs.edit().putString("events", (read().lines() + line).takeLast(100).joinToString("\n")).apply()
    }
    fun read(): String = prefs.getString("events", "").orEmpty()
    fun clear() { prefs.edit().remove("events").apply() }
}
