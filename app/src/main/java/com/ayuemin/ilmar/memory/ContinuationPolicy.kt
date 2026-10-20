package com.ayuemin.ilmar.memory

/** Control-only turns stay in history but add no useful semantic embedding. Never discard short facts. */
internal object ContinuationPolicy {
    fun isControl(text: String): Boolean = text.trim().lowercase(java.util.Locale.ROOT)
        .trimEnd('.', '!', '?', '…', ',', ':', ';').trim() in setOf("далее", "дальше", "продолжай", "продолжить", "continue", "go on", "next", "продолжай пожалуйста", "please continue")
    fun isBriefReply(text: String): Boolean = text.trim().lowercase(java.util.Locale.ROOT).trimEnd('.', '!', '?').trim() in setOf("да", "нет", "yes", "no", "ок", "окей", "хорошо", "согласен")
}
