package com.ayuemin.ilmar.tools

import com.ayuemin.ilmar.documents.DocumentExtraction
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DocumentExtractionTest {
    private fun html(text: String) = DocumentExtraction.extract(FetchedDocument("https://example.com/docs", "text/html", text, ""))
    @Test fun headingsBoundariesTablesListsLinksAndConditionsRetainOrder() {
        val source = """<title>API</title><header><h1>Guide</h1></header><main>
            <p>Do not send passwords. Tokens expire unless renewed.</p><h2>Limits</h2>
            <h3>Pro v2.1</h3><table><tr><th>Plan</th><th>Price</th></tr><tr><td>Pro</td><td>12 USD</td></tr></table>
            <ul><li>Only after approval<ul><li>Except trial accounts</li></ul></li></ul>
            <p>See <a href="/terms">terms</a>.</p></main><footer>Cancellation requires 30 days notice.</footer><script>bad()</script>"""
        val doc = html(source)
        assertEquals("API", doc.title)
        assertEquals(listOf("Guide", "Limits", "Pro v2.1"), doc.sections.drop(1).map { it.title })
        val nested = doc.sections.last()
        assertEquals(listOf("Guide", "Limits", "Pro v2.1"), nested.path)
        assertEquals(doc.blocks.size, nested.until)
        assertTrue(doc.text.contains("Plan | Price")); assertTrue(doc.text.contains("Pro | 12 USD"))
        assertEquals(2, doc.blocks.count { it.kind == "table_row" })
        assertEquals(2, doc.blocks.count { it.kind == "list_item" })
        assertEquals(1, Regex("Except trial accounts").findAll(doc.text).count())
        assertTrue(doc.text.contains("Do not send passwords.")); assertTrue(doc.text.contains("unless renewed"))
        assertTrue(doc.text.contains("terms <https://example.com/terms>")); assertTrue(doc.text.contains("30 days notice"))
        assertFalse(doc.text.contains("bad()"))
        doc.blocks.forEach { block -> assertEquals(doc.text.substring(block.start, block.end), doc.text(block)) }
        assertEquals(doc, html(source)) // deterministic version/section IDs and coordinates
    }
    @Test fun malformedHtmlAndMissingHierarchyDoNotLoseTheLateText() {
        val doc = html("<div>First paragraph.<p>Second condition is not optional.<p>Last fact: 42 EUR")
        assertEquals(1, doc.sections.size)
        assertTrue(doc.text.endsWith("42 EUR")); assertTrue(doc.text.contains("not optional"))
        assertTrue(doc.blocks.size >= 3)
    }
    @Test fun markdownHeadingsFencesAndRowsAreDistinctAndPlainTextFallsBack() {
        val source = """# Guide

            ## Authentication
            Do not disclose keys. Use a token instead.
            ### Conditions
            | Plan | Limit |
            | Pro | 100/min |
            - Except trial users
            ```
            # This is code
            ```
            Pricing
            -------
            Price is 10 USD.
        """.trimIndent().replace("            ", "")
        val doc = DocumentExtraction.extract(FetchedDocument("https://example.com/docs.md", "text/markdown", source, source))
        assertEquals(listOf("Guide", "Authentication", "Conditions", "Pricing"), doc.sections.drop(1).map { it.title })
        assertTrue(doc.blocks.any { it.kind == "code" && doc.text(it).contains("# This is code") })
        assertTrue(doc.blocks.any { it.kind == "list_item" && doc.text(it).contains("Except") })
        val plain = DocumentExtraction.extract(FetchedDocument("https://example.com", "text/plain", "Без заголовков. Не удалять.\n\nПоздний факт.", ""))
        assertEquals(1, plain.sections.size); assertTrue(plain.text.endsWith("Поздний факт."))
    }
    @Test fun excessiveStructuresFailExplicitlyRatherThanIndexingAPrefix() {
        assertTrue(runCatching { html("<p>Data</p>".repeat(12001)) }.exceptionOrNull()?.message.orEmpty().contains("STRUCTURE_LIMIT"))
    }
}
