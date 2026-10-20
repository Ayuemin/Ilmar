package com.ayuemin.ilmar.ui.markdown

import org.junit.Assert.*
import org.junit.Test

class RichMessageTest {
    @Test fun separatesCodeCopyAndTextWithoutLosingOrder() {
        val parts = splitRichBlocks("Before\n```kotlin\nval x = 1\n```\n:::copy\nСкопируй меня\n:::\nAfter")
        assertEquals(listOf(MessagePartKind.PLAIN, MessagePartKind.CODE, MessagePartKind.PLAIN, MessagePartKind.COPY, MessagePartKind.PLAIN), parts.map { it.kind })
        assertEquals("kotlin", parts[1].language)
        assertEquals("val x = 1", parts[1].text)
        assertEquals("Скопируй меня", parts[3].text)
    }
    @Test fun incompleteFenceRemainsVisibleAsText() {
        val text = "```python\nprint('hello')"
        assertEquals(text, splitRichBlocks(text).single().text)
        assertEquals(MessagePartKind.PLAIN, splitRichBlocks(text).single().kind)
    }
}
