package com.ayuemin.ilmar.agent

import android.content.Context

/** Grants are separate from chat selection, and are never accepted from model/tool text. */
internal class ToolPermissionBroker(context: Context) {
    private val prefs = context.getSharedPreferences("ilmar_tool_permissions", Context.MODE_PRIVATE)
    fun granted(id: String) = prefs.getBoolean(id, false)
    fun setGranted(id: String, allowed: Boolean) { check(prefs.edit().putBoolean(id, allowed).commit()) }
}
