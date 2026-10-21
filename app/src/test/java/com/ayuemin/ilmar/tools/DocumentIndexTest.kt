package com.ayuemin.ilmar.tools

import com.ayuemin.ilmar.documents.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DocumentIndexTest {
    private fun document(body: String, type: String = "text/html") = DocumentExtraction.extract(FetchedDocument("https://example.com/guide", type, body, ""))
    private fun found(index: DocumentIndex, query: String, options: RetrievalOptions = RetrievalOptions()) =
        index.search(query, 3, options).hits.joinToString("\n") { index.document.let { doc -> doc.text.substring(doc.blocks[it.first].start, doc.blocks[it.until - 1].end) } }
    @Test fun bilingualStemsUnicodeAndSynonymsDoNotRewriteSource() {
        assertEquals(LexicalTerms.stem("ограничение"), LexicalTerms.stem("ограничениями"))
        assertEquals(LexicalTerms.stem("token"), LexicalTerms.stem("tokens"))
        assertEquals("api", LexicalTerms.normalize("ＡＰＩ"))
        val doc = document("<h1>Pricing</h1><p>Стоимость подписки составляет 12 EUR.</p><p>Ограничениями запрещена отправка паролей.</p><p>Tokens expire in 30 days.</p>")
        DocumentIndex(doc, false).use { index ->
            assertTrue(found(index, "цена подписки").contains("12 EUR"))
            assertTrue(found(index, "ограничение", RetrievalOptions(context = false)).contains("Ограничениями"))
            assertFalse(found(index, "ограничение", RetrievalOptions(morphology = false, context = false)).contains("Ограничениями"))
            assertTrue(found(index, "token expiry").contains("30 days"))
        }
        assertTrue(doc.text.contains("Ограничениями запрещена отправка паролей."))
    }
    @Test fun exactCodesVersionsNamesAndQuotedPhrasesCannotBeFuzzedIntoOtherProducts() {
        val doc = document("<h2>NovaPro v1.2</h2><p>ERR_401 requires login. Price is 10 USD.</p><h2>NovaLite v1.3</h2><p>ERR_403 requires permission. Price is 20 USD.</p>")
        DocumentIndex(doc, false).use { index ->
            val precise = found(index, "ERR_403 price")
            assertTrue(precise.contains("20 USD")); assertFalse(precise.contains("10 USD"))
            assertFalse(found(index, "ERR_404 price").contains("USD"))
            assertTrue(found(index, "NovaPro v1.2 price").contains("10 USD"))
            assertFalse(found(index, "NovaPro v1.2 price").contains("20 USD"))
            assertTrue(found(index, "\"requires permission\"").contains("ERR_403"))
        }
    }
    @Test fun typosLateAnswerAndNeighboringExceptionsAreRetrievedLocally() {
        val doc = document("<h1>Guide</h1>" + "<p>Background information.</p>".repeat(300) + "<h2>Authentication</h2><p>Authentication tokens expire after 7 days. Except trial tokens, which expire after 1 day.</p>")
        DocumentIndex(doc, false).use { index ->
            assertTrue(found(index, "authentcation token").contains("7 days"))
            assertTrue(found(index, "authentcation token").contains("Except trial"))
            val result = index.search("authentication token", options = RetrievalOptions(feedback = true))
            assertTrue(result.feedbackTerms.size <= 3); assertTrue(result.hits.isNotEmpty())
            assertEquals(emptyList<String>(), index.search("authentication token").feedbackTerms)
            assertTrue(index.search("unrelated quantum", options = RetrievalOptions(feedback = true)).hits.isEmpty())
        }
    }
    @Test fun ftsProbeAndFallbackBothWorkAndQueriesCannotBecomeSql() {
        val doc = document("<p>Price is 12 USD.</p><p>Tokens expire.</p>")
        for (fts in listOf(false, true)) DocumentIndex(doc, fts).use { index ->
            assertTrue(index.backend in setOf("fts5-bm25", "kotlin-bm25"))
            assertTrue(found(index, "price").contains("12 USD"))
            java.io.File("build/reports/retrieval-backend.txt").apply { parentFile?.mkdirs(); appendText("allowFts5=$fts backend=${index.backend}\n") }
            index.search("\" OR * NEAR(SELECT); DROP TABLE fragments; --")
            assertTrue(found(index, "price").contains("12 USD"))
        }
        assertTrue(runCatching { DocumentIndex(doc, false, maxBytes = 1) }.isFailure)
        val closed = DocumentIndex(doc, false); closed.close(); closed.close()
        assertTrue(runCatching { closed.search("price") }.isFailure)
        DocumentIndex(document("<p>OR is an exact opcode. https://example.com/specific</p>"), false).use { index ->
            assertTrue(found(index, "OR").contains("opcode"))
            assertTrue(found(index, "https://example.com/specific").contains("opcode"))
        }
    }
    @Test fun duplicateWindowsAndDisabledHeatStillHaveDeterministicBm25Order() {
        val doc = document("<p>Rare refund condition.</p>".repeat(8) + "<p>Refund takes 5 days.</p>")
        DocumentIndex(doc, false).use { index ->
            val options = RetrievalOptions(context = false, heat = false)
            val result = index.search("refund", 10, options)
            assertEquals(2, result.hits.size); assertEquals(result, index.search("refund", 10, options))
            assertTrue(result.hits.all { it.score > 0 })
        }
    }
}
