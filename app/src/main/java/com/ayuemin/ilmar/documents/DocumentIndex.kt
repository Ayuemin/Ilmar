package com.ayuemin.ilmar.documents

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import kotlin.math.ln

internal data class RetrievalOptions(val structure: Boolean = true, val heat: Boolean = true,
    val morphology: Boolean = true, val feedback: Boolean = false, val context: Boolean = true)
internal data class DocumentHit(val first: Int, val until: Int, val score: Double)
internal data class SearchResult(val hits: List<DocumentHit>, val feedbackTerms: List<String>, val backend: String)

/** Per-document, memory-only index. FTS5 is probed on the running SQLite, never inferred from API level. */
internal class DocumentIndex(val document: StructuredDocument, allowFts5: Boolean = true,
    maxBytes: Long = 16L * 1024 * 1024) : AutoCloseable {
    private data class Row(val terms: List<LexicalTerms.Term>, val heading: List<LexicalTerms.Term>)
    private val rows: List<Row>
    private val vocabulary: List<String>
    private val exactDf = mutableMapOf<String, Int>()
    private val stemDf = mutableMapOf<String, Int>()
    private var database: SQLiteDatabase? = null
    var retainedBytes: Long = 0; private set
    private var closed = false
    val backend get() = if (database != null) "fts5-bm25" else "kotlin-bm25"
    init {
        val sections = document.sections.associateBy { it.id }
        rows = document.blocks.map { block ->
            val terms = LexicalTerms.terms(document.text(block))
            val heading = LexicalTerms.terms(sections.getValue(block.section).path.joinToString(" "))
            retainedBytes += (terms + heading).sumOf { 80L + 2L * (it.exact.length + it.stem.length) }
            require(retainedBytes <= maxBytes) { "DOCUMENT_INDEX_LIMIT: лимит памяти индекса; частичный индекс не используется" }
            terms.map { it.exact }.toSet().forEach { exactDf[it] = (exactDf[it] ?: 0) + 1 }
            terms.map { it.stem }.toSet().forEach { stemDf[it] = (stemDf[it] ?: 0) + 1 }
            Row(terms, heading)
        }
        vocabulary = rows.flatMap { it.terms }.filterNot { it.exactOnly }.map { it.exact }.distinct().take(16000)
        if (allowFts5) {
            val db = SQLiteDatabase.create(null)
            try {
                db.execSQL("PRAGMA temp_store=MEMORY")
                db.execSQL("CREATE VIRTUAL TABLE fragments USING fts5(exact, stem, head_exact, head_stem, tokenize='unicode61')")
                db.execSQL("INSERT INTO fragments(rowid,exact,stem) VALUES(0,'probe','probe')")
                check(db.rawQuery("SELECT bm25(fragments) FROM fragments WHERE fragments MATCH ?", arrayOf("probe")).use { it.moveToFirst() })
                db.execSQL("DELETE FROM fragments")
                db.beginTransaction()
                try {
                    rows.forEachIndexed { i, row ->
                        val exact = row.terms.joinToString(" ") { LexicalTerms.ftsKey(it.exact) }
                        val stem = row.terms.joinToString(" ") { LexicalTerms.ftsKey(it.stem) }
                        val headExact = row.heading.joinToString(" ") { LexicalTerms.ftsKey(it.exact) }
                        val headStem = row.heading.joinToString(" ") { LexicalTerms.ftsKey(it.stem) }
                        retainedBytes += 2L * (exact.length + stem.length + headExact.length + headStem.length)
                        require(retainedBytes <= maxBytes) { "DOCUMENT_INDEX_LIMIT: лимит FTS5; документ не индексирован" }
                        db.execSQL("INSERT INTO fragments(rowid,exact,stem,head_exact,head_stem) VALUES(?,?,?,?,?)", arrayOf<Any>(i + 1, exact, stem, headExact, headStem))
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                database = db
            } catch (_: SQLiteException) { db.close() } // Android distributions may ship FTS4 only.
            catch (e: Exception) { db.close(); throw e }
        }
    }
    fun search(query: String, limit: Int = 10, options: RetrievalOptions = RetrievalOptions()): SearchResult {
        check(!closed) { "DOCUMENT_SESSION_CLOSED" }
        require(query.isNotBlank() && query.length <= 1000 && limit in 1..10)
        val original = LexicalTerms.terms(query).filter { it.exactOnly || it.exact !in LexicalTerms.stop }.distinctBy { it.exact }.take(24)
        if (original.isEmpty()) return SearchResult(emptyList(), emptyList(), backend)
        data class Variant(val value: String, val stem: Boolean, val weight: Double, val origin: Int)
        val variants = mutableListOf<Variant>()
        original.forEachIndexed { i, term ->
            variants += Variant(term.exact, false, 1.0, i)
            if (!term.exactOnly) {
                if (options.morphology) variants += Variant(term.stem, true, 0.7, i)
                LexicalTerms.synonyms.firstOrNull { group -> group.any { LexicalTerms.stem(it) == term.stem } }?.let { group ->
                    group.take(6).forEach { synonym -> variants += Variant(if (options.morphology) LexicalTerms.stem(synonym) else synonym, options.morphology, 0.35, i) }
                }
                vocabulary.asSequence().filter { LexicalTerms.typo(term.exact, it) && term.exact != it }.take(2)
                    .forEach { variants += Variant(it, false, 0.25, i) }
            }
        }
        val unique = variants.distinctBy { Triple(it.value, it.stem, it.origin) }
        val phrases = Regex("[\"«]([^\"»]+)[\"»]").findAll(query).map { LexicalTerms.normalize(it.groupValues[1]) }.toList()
        val avg = rows.map { it.terms.size }.average().takeIf { !it.isNaN() && it > 0 } ?: 1.0
        fun lexical(row: Row, variant: Variant): Double {
            val count = row.terms.count { (if (variant.stem) it.stem else it.exact) == variant.value }
            if (count == 0) return 0.0
            val df = (if (variant.stem) stemDf else exactDf)[variant.value] ?: 0
            val idf = ln(1.0 + (rows.size - df + 0.5) / (df + 0.5))
            return variant.weight * idf * count * 2.2 / (count + 1.2 * (0.25 + 0.75 * row.terms.size / avg))
        }
        // Use actual FTS5 BM25 when supported; exactOnly tokens and variants are pre-normalized, SQL is bound.
        val fts = mutableMapOf<Int, Double>()
        database?.let { db ->
            val match = unique.joinToString(" OR ") {
                val col = if (it.stem) "stem" else "exact"; val key = LexicalTerms.ftsKey(it.value)
                if (options.structure) "($col:$key OR head_$col:$key)" else "$col:$key"
            }
            val bm25 = if (options.structure) "bm25(fragments,1,1,2,2)" else "bm25(fragments,1,1,0,0)"
            db.rawQuery("SELECT rowid,$bm25 FROM fragments WHERE fragments MATCH ? ORDER BY $bm25", arrayOf(match)).use { cursor ->
                while (cursor.moveToNext()) fts[cursor.getInt(0) - 1] = -cursor.getDouble(1)
            }
        }
        val maxFts = fts.values.maxOrNull()?.coerceAtLeast(1e-12) ?: 1.0
        fun rank(extra: List<String>): List<Pair<Int, Double>> = rows.mapIndexedNotNull { i, row ->
            val content = row.terms + if (options.structure) row.heading else emptyList()
            val exactOnly = original.filter { it.exactOnly }
            // Do not let a version/code query select a neighboring product purely through a shared generic word.
            if (exactOnly.isNotEmpty() && exactOnly.none { q -> content.any { it.exact == q.exact } }) return@mapIndexedNotNull null
            val matched = unique.filter { v -> content.any { (if (v.stem) it.stem else it.exact) == v.value } }
            if (matched.isEmpty()) return@mapIndexedNotNull null // Feedback never creates an unanchored match.
            val normalizedText = LexicalTerms.normalize(document.text(document.blocks[i]))
            if (phrases.isNotEmpty() && phrases.none { normalizedText.contains(it) }) return@mapIndexedNotNull null
            val base = unique.sumOf { lexical(row, it) + if (options.structure) 0.5 * lexical(Row(row.heading, emptyList()), it) else 0.0 }
            var score = base + if (database != null) 0.25 * (fts[i] ?: 0.0) / maxFts else 0.0
            if (options.heat) {
                val positions = row.terms.mapIndexedNotNull { position, token ->
                    position.takeIf { matched.any { v -> (if (v.stem) token.stem else token.exact) == v.value } }
                }
                val coverage = matched.map { it.origin }.distinct().size.toDouble() / original.size
                score += matched.groupBy { it.origin }.values.sumOf { group -> group.maxOf { it.weight } } * 3 + coverage * coverage * 5
                if (positions.isNotEmpty()) score += 2.0 * positions.size / (positions.last() - positions.first() + 1)
                if (options.structure && row.heading.any { h -> original.any { q -> h.exact == q.exact || options.morphology && h.stem == q.stem } }) score += 2
                score += phrases.count { normalizedText.contains(it) } * 6
                score += original.count { q -> q.exactOnly && row.terms.any { it.exact == q.exact } } * 6
                if (row.terms.any { it.exact.any(Char::isDigit) }) score += 0.4 // Only after a lexical anchor.
            }
            score += extra.count { term -> row.terms.any { it.exact == term } } * 0.15
            i to score
        }.sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })
        val initial = rank(emptyList())
        val feedback = if (options.feedback) initial.take(3).flatMap { rows[it.first].terms }.filter { term ->
            !term.exactOnly && term.exact.length >= 4 && term.exact !in LexicalTerms.stop && original.none { it.stem == term.stem }
        }.groupingBy { it.exact }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(3).map { it.key } else emptyList()
        val ranked = if (feedback.isNotEmpty()) rank(feedback) else initial
        val hits = mutableListOf<DocumentHit>(); val texts = mutableSetOf<String>()
        for ((center, score) in ranked) {
            val block = document.blocks[center]
            var first = center; var until = center + 1
            if (options.context) {
                if (center > 0 && document.blocks[center - 1].section == block.section) first--
                if (until < rows.size && document.blocks[until].section == block.section) until++
            }
            val overlap = hits.indexOfFirst { first < it.until && until > it.first }
            if (overlap >= 0) {
                val prior = hits[overlap]
                val mergedFirst = minOf(first, prior.first); val mergedUntil = maxOf(until, prior.until)
                if (mergedUntil - mergedFirst <= 7 && document.blocks[mergedUntil - 1].end - document.blocks[mergedFirst].start <= 6000)
                    hits[overlap] = DocumentHit(mergedFirst, mergedUntil, maxOf(score, prior.score))
                continue
            }
            val text = document.text.substring(document.blocks[first].start, document.blocks[until - 1].end)
            if (!texts.add(text)) continue
            hits += DocumentHit(first, until, score)
            if (hits.size == limit) break
        }
        return SearchResult(hits, feedback, backend)
    }
    override fun close() { if (!closed) database?.close(); database = null; closed = true }
}
