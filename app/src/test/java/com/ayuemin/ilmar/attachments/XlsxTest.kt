package com.ayuemin.ilmar.attachments

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class XlsxTest {
    private fun workbook(entries: Map<String, String>): File {
        val app = ApplicationProvider.getApplicationContext<Context>()
        return File.createTempFile("sheet", ".xlsx", app.cacheDir).apply {
            ZipOutputStream(outputStream()).use { zip -> entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
            } }
        }
    }
    @Test fun extractsSharedInlineAndCachedFormulaValuesWithoutExecutingFormulas() {
        val file = workbook(mapOf("xl/sharedStrings.xml" to "<sst><si><t>Доход</t></si></sst>",
            "xl/worksheets/sheet1.xml" to "<worksheet><sheetData><row><c r='A1' t='s'><v>0</v></c><c r='B1'><f>2+3</f><v>5</v></c><c r='C1' t='inlineStr'><is><t>Привет</t></is></c></row></sheetData></worksheet>"))
        try {
            val text = XlsxText.read(file)
            assertTrue(text.contains("A1: Доход")); assertTrue(text.contains("B1: 5")); assertTrue(text.contains("C1: Привет")); assertFalse(text.contains("2+3"))
            val app = ApplicationProvider.getApplicationContext<Context>()
            val item = file.inputStream().use { AttachmentStore(app).ingest(it, "данные.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") }
            assertTrue(item.text!!.contains("Доход"))
        } finally { file.delete() }
    }
    @Test fun rejectsXmlEntitiesAndOversizedExpansion() {
        listOf("<!DOCTYPE worksheet [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><worksheet>&x;</worksheet>",
            "<worksheet>" + "a".repeat(com.ayuemin.ilmar.settings.RuntimeLimits.mib("xml_mib").toInt()) + "</worksheet>").forEach { xml ->
            val file = workbook(mapOf("xl/worksheets/sheet1.xml" to xml))
            try { assertTrue(runCatching { XlsxText.read(file) }.isFailure) } finally { file.delete() }
        }
    }
}
