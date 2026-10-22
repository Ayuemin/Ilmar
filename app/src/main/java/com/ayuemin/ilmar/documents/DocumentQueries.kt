package com.ayuemin.ilmar.documents

/** Bounded deterministic decomposition; never requests an LLM. Source text is untouched. */
internal object DocumentQueries {
    fun split(request: String, title: String): List<String> {
        val clean = request.replace(Regex("https?://\\S+"), " ")
        val marker = Regex("(?m)^\\s*\\d+[.)]\\s+")
        val numbered = marker.findAll(clean).toList()
        val questions = if (numbered.isNotEmpty()) numbered.mapIndexed { i, match ->
            clean.substring(match.range.last + 1, numbered.getOrNull(i + 1)?.range?.first ?: clean.length)
        } else clean.split(Regex("(?<=[?？])\\s+|\\n+"))
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
