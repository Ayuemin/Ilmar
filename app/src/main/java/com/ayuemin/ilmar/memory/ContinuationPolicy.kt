package com.ayuemin.ilmar.memory

/** Control-only turns stay in history but add no useful semantic embedding. Never discard short facts. */
internal object ContinuationPolicy {
    fun isControl(text: String): Boolean = text.trim().lowercase(java.util.Locale.ROOT)
        .trimEnd('.', '!', '?', '…') in setOf("далее", "дальше", "продолжай", "продолжить", "continue", "go on", "next")
}
