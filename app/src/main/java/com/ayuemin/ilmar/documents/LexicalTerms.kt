package com.ayuemin.ilmar.documents

import java.text.Normalizer
import java.util.Locale

/** Small deterministic stemmers operate on search keys only; source sentences are never edited. */
internal object LexicalTerms {
    private val patterns = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    private fun pattern(value: String) = patterns.getOrPut(value) { Regex(value) }
    data class Term(val exact: String, val stem: String, val exactOnly: Boolean)
    fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    private val words = Regex("https?://[^\\s<>\\)]+|[\\p{L}\\p{N}_]+(?:[.\\-/+:][\\p{L}\\p{N}_]+)*")
    fun terms(text: String): List<Term> = words.findAll(text).map { match ->
        val raw = match.value.trimEnd('.', ',', ';', '!', '"', '\'', '»')
        val exact = normalize(raw)
        val exactOnly = raw.any(Char::isDigit) || raw.any { it in "_/:+" } ||
            raw.matches(pattern("[A-ZА-Я]{2,}")) || raw.drop(1).any(Char::isUpperCase)
        Term(exact, if (exactOnly) exact else stem(exact), exactOnly)
    }.toList()
    fun stem(value: String): String = when {
        value.matches(pattern("[а-яё]+")) -> russian(value.replace('ё', 'е'))
        value.matches(pattern("[a-z]+")) -> english(value)
        else -> value
    }
    private val ruVowels = "аеиоуыэюя"
    private fun russian(word: String): String {
        val rv = word.indexOfFirst { it in ruVowels } + 1
        if (rv <= 0 || rv >= word.length) return word
        val prefix = word.take(rv); var tail = word.drop(rv)
        fun strip(endings: String): Boolean {
            val next = tail.replace(pattern("(?:$endings)$"), "")
            val changed = next != tail; tail = next; return changed
        }
        if (!strip("ившись|ывшись|ивши|ывши|ив|ыв|(?<=[ая])вшись|(?<=[ая])вши|(?<=[ая])в")) {
            strip("ся|сь")
            if (strip("ими|ыми|его|ого|ему|ому|ее|ие|ые|ое|ей|ий|ый|ой|ем|им|ым|ом|их|ых|ую|юю|ая|яя|ою|ею")) {
                strip("ивш|ывш|ующ|(?<=[ая])ем|(?<=[ая])нн|(?<=[ая])вш|(?<=[ая])ющ|(?<=[ая])щ")
            } else if (!strip("ейте|уйте|ила|ыла|ена|ите|или|ыли|ило|ыло|ено|ует|уют|ены|ить|ыть|ишь|ей|уй|ил|ыл|им|ым|ен|ят|ит|ыт|ую|ю|(?<=[ая])ете|(?<=[ая])йте|(?<=[ая])ешь|(?<=[ая])нно|(?<=[ая])ла|(?<=[ая])на|(?<=[ая])ли|(?<=[ая])ем|(?<=[ая])ло|(?<=[ая])но|(?<=[ая])ет|(?<=[ая])ют|(?<=[ая])ны|(?<=[ая])ть|(?<=[ая])й|(?<=[ая])л|(?<=[ая])н")) {
                strip("иями|ией|иям|ием|иях|ами|ями|ию|ью|ия|ья|ев|ов|ие|ье|еи|ии|ей|ой|ий|ям|ем|ам|ом|ах|ях|а|е|и|й|о|у|ы|ь|ю|я")
            }
        }
        strip("и")
        val whole = prefix + tail
        val r1 = region(whole, ruVowels); val r2 = region(whole, ruVowels, r1)
        if (whole.drop(r2).endsWith("ость") || whole.drop(r2).endsWith("ост")) strip("ость|ост")
        if (!strip("ь")) { strip("ейше|ейш"); if (tail.endsWith("нн")) tail = tail.dropLast(1) }
        return prefix + tail
    }
    private fun region(word: String, vowels: String, start: Int = 0): Int {
        for (i in (start + 1) until word.length) if (word[i - 1] in vowels && word[i] !in vowels) return i + 1
        return word.length
    }
    private val replacements = linkedMapOf("ational" to "ate", "tional" to "tion", "ization" to "ize", "ation" to "ate", "fulness" to "ful", "iveness" to "ive", "ousness" to "ous", "biliti" to "ble", "mentli" to "ment", "izer" to "ize", "alli" to "al", "alism" to "al", "aliti" to "al", "iviti" to "ive", "enci" to "ence", "anci" to "ance", "abli" to "able", "ousli" to "ous", "entli" to "ent")
    private fun english(word: String): String {
        if (word.length < 4) return word
        var s = when { word.endsWith("sses") -> word.dropLast(2); word.endsWith("ies") -> word.dropLast(3) + "i";
            word.endsWith("ss") -> word; word.endsWith('s') -> word.dropLast(1); else -> word }
        if (s.endsWith("eed")) { if (region(s, "aeiouy") < s.length - 2) s = s.dropLast(1) }
        else {
            val suffix = listOf("ingly", "edly", "ing", "ed").firstOrNull { s.endsWith(it) && s.dropLast(it.length).any { c -> c in "aeiouy" } }
            if (suffix != null) {
                s = s.dropLast(suffix.length)
                if (s.endsWith("at") || s.endsWith("bl") || s.endsWith("iz")) s += "e"
                else if (s.length > 2 && s.last() == s[s.lastIndex - 1] && s.last() !in "lsz") s = s.dropLast(1)
            }
        }
        if (s.endsWith('y') && s.dropLast(1).any { it in "aeiou" }) s = s.dropLast(1) + "i"
        replacements.entries.firstOrNull { s.endsWith(it.key) && s.length - it.key.length >= region(s, "aeiouy") }?.let { s = s.dropLast(it.key.length) + it.value }
        if (s.endsWith("ness")) s = s.dropLast(4)
        if (s.endsWith('e') && s.length > 4) s = s.dropLast(1)
        return s
    }
    // Explicit, reviewable topic groups, not a downloaded thesaurus or generated word forms.
    val synonyms = listOf(
        listOf("price", "cost", "pricing", "цена", "стоимость"),
        listOf("limit", "quota", "лимит", "ограничение", "квота"),
        listOf("authentication", "login", "аутентификация", "вход"),
        listOf("refund", "возврат"), listOf("subscription", "подписка")
    )
    val stop = "a an the is are was were be to of for and or in on at by with what which how does do not не и в во на с со к от до за для по из как что это ли сколько какой какая какие when where can about можно нужно если без все only except unless".split(' ').toSet()
    fun trigrams(word: String) = ("^$word$").windowed(3).toSet()
    fun typo(a: String, b: String): Boolean {
        if (a.length < 5 || b.length < 5 || kotlin.math.abs(a.length - b.length) > 1) return false
        val x = trigrams(a); val y = trigrams(b)
        if (x.intersect(y).size.toDouble() / x.union(y).size < 0.25) return false
        var i = 0; var j = 0; var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++ } else {
                if (++edits > 1) return false
                when { a.length > b.length -> i++; b.length > a.length -> j++; else -> { i++; j++ } }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }
    // FTS tokenizers never split a exactOnly identifier: encode every already-normalized term as ASCII.
    fun ftsKey(term: String) = buildString {
        append('x'); term.toByteArray(Charsets.UTF_8).forEach { byte ->
            val n = byte.toInt() and 255; append("0123456789abcdef"[n shr 4]); append("0123456789abcdef"[n and 15])
        }
    }
}
