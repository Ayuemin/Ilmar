package com.ayuemin.ilmar.model

import java.io.EOFException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SseReaderTest {
    private fun read(text: String, delta: (String) -> Unit = {}) = SseReader.read(text.reader().buffered(), delta)
    @Test fun handlesCommentsRoleUsageAndCompleteStream() {
        val text = ": keepalive\n\ndata: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"Привет\"}}]}\n\n" +
            "data: {\"choices\":[],\"usage\":{\"total_tokens\":9}}\n\ndata: [DONE]\n\n"
        val chunks = mutableListOf<String>()
        assertEquals("Привет", read(text, chunks::add))
        assertEquals(listOf("Привет"), chunks)
    }
    @Test fun joinsMultilineEventAndAcceptsDoneAtEof() {
        assertEquals("ok", read("data: {\"choices\":\ndata: [{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]"))
    }
    @Test fun truncatedOutputNeverCountsAsSuccessfulAnswer() {
        assertThrows(EOFException::class.java) { read("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n") }
    }
    @Test fun rejectsEmptyAndErrorStreams() {
        assertThrows(java.io.IOException::class.java) { read("data: [DONE]\n\n") }
        assertThrows(java.io.IOException::class.java) { read("data: {\"error\":{\"message\":\"private details\"}}\n\n") }
    }
}
