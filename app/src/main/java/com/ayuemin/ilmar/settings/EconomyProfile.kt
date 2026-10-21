package com.ayuemin.ilmar.settings

/** Applied only by an explicit settings action. Never changes persisted preferences on upgrade. */
internal object EconomyProfile {
    val runtimeOverrides = mapOf("rounds" to 8, "browser_rounds" to 8, "calls" to 32,
        "task_seconds" to 300, "model_seconds" to 60, "browser_output_tokens" to 1024)
}
