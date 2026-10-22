package com.ayuemin.ilmar.documents

/** Bounded deterministic decomposition; never requests an LLM. Source text is untouched. */
internal object DocumentQueries {
    fun split(request: String, title: String): List<String> {
        val clean = request.replace(Regex("https?://\\S+"), " ")
        val numbered = clean.split(Regex("(?m)^\\s*\\d+[.)]\\s+" )).map { it.trim() }.filter { it.isNotBlank() }
        val questions = if (numbered.size > 1) numbered.drop(1) else clean.split(Regex("(?<=[?？])\\s+|\\n+"))
        return questions.map { it.trim().take(1000) }.filter { it.isNotBlank() }.distinct().take(8)
            .ifEmpty { listOf(title.take(1000).ifBlank { "document" }) }
    }
    fun anchors(query: String): String = LexicalTerms.terms(query).filter { it.exactOnly || it.exact.length >= 4 && it.exact !in LexicalTerms.stop }
        .distinctBy { it.exact }.take(6).joinToString(" ") { it.exact }.take(1000)
    fun coverage(query: String, text: String): Double {
        val terms = LexicalTerms.terms(query).filter { it.exact !in LexicalTerms.stop }.distinctBy { it.stem }
        val found = LexicalTerms.terms(text).map { it.stem }.toSet()
        return if (terms.isEmpty()) 0.0 else terms.count { it.stem in found }.toDouble() / terms.size
    }
}
