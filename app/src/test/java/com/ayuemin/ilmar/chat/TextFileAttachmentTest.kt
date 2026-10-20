package com.ayuemin.ilmar.chat

import org.junit.Assert.*
import org.junit.Test

class TextFileAttachmentTest {
    @Test fun preservesUtf8TextForEditing() {
        val text = "Текст файла\n第二行\n🙂"
        assertEquals(text, readTextStream(text.byteInputStream()))
    }
    @Test fun allowsLimitAndRejectsOversizedFile() {
        assertEquals(64 * 1024, readTextStream("a".repeat(64 * 1024).byteInputStream()).length)
        assertThrows(IllegalArgumentException::class.java) { readTextStream("a".repeat(64 * 1024 + 1).byteInputStream()) }
    }
    @Test fun rejectsBinaryAndMalformedUtf8() {
        assertThrows(IllegalArgumentException::class.java) { readTextStream(byteArrayOf(1, 0, 2).inputStream()) }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { readTextStream(byteArrayOf(0xC3.toByte()).inputStream()) }
    }
}
