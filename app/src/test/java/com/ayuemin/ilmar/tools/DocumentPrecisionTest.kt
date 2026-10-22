package com.ayuemin.ilmar.tools

import com.ayuemin.ilmar.documents.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DocumentPrecisionTest {
    private fun search(body: String, query: String): String {
        val doc = DocumentExtraction.extract(FetchedDocument("https://example.com/guide", "text/html", body, ""))
        return DocumentIndex(doc, false).use { index -> index.search(query, 10, RetrievalOptions(context = false)).hits.joinToString("\n") {
            doc.text.substring(doc.blocks[it.first].start, doc.blocks[it.until - 1].end)
        } }
    }
    @Test fun urlPathsPreserveCaseAndProductPlusVersionMustBothMatch() {
        val links = "<p>Upper endpoint <a href='https://example.com/Secret'>upper</a></p><p>Lower endpoint <a href='https://example.com/secret'>lower</a></p>"
        val precise = search(links, "https://example.com/Secret")
        assertTrue(precise.contains("Upper endpoint")); assertFalse(precise.contains("Lower endpoint"))
        val products = "<h2>NovaPro v2.0</h2><p>Price is 10 USD.</p><h2>NovaLite v2.0</h2><p>Price is 20 USD.</p>"
        val product = search(products, "NovaPro v2.0 price")
        assertTrue(product.contains("10 USD")); assertFalse(product.contains("20 USD"))
        val comparison = search(products, "compare NovaPro v2.0 versus NovaLite v2.0 price")
        assertTrue(comparison.contains("10 USD")); assertTrue(comparison.contains("20 USD"))
    }
    @Test fun ordinaryAllCapsQuestionsStillNormalizeAndHeadingOnlyTitleCanMatch() {
        assertTrue(search("<p>Price is 10 USD.</p>", "WHAT IS PRICE").contains("10 USD"))
        assertTrue(search("<title>NovaPro</title><p>Version is v2.0. Price is 10 USD.</p>", "NovaPro price").contains("10 USD"))
    }
}
