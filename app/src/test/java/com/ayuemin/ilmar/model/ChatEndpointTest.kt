package com.ayuemin.ilmar.model

import org.junit.Assert.*
import org.junit.Test

class ChatEndpointTest {
    @Test fun resolvesOpenRouterBaseWithOrWithoutTrailingSlash() {
        for (base in listOf("https://openrouter.ai/api/v1", "https://openrouter.ai/api/v1/")) {
            assertEquals("https://openrouter.ai/api/v1/chat/completions", ChatEndpoint.resolve(base))
        }
    }
    @Test fun resolvesOpenAiBaseAndBareHost() {
        assertEquals("https://api.openai.com/v1/chat/completions", ChatEndpoint.resolve("https://api.openai.com/v1"))
        assertEquals("https://api.openai.com/v1/chat/completions", ChatEndpoint.resolve(" https://api.openai.com/ "))
    }
    @Test fun neverDuplicatesChatCompletionsOrRewritesCustomRoutes() {
        val full = "https://openrouter.ai/api/v1/chat/completions"
        assertEquals(full, ChatEndpoint.resolve(full))
        val custom = "https://proxy.example/custom/chat?api-version=2025"
        assertEquals(custom, ChatEndpoint.resolve(custom))
    }
    @Test fun retainsQueryAndPortWhenAppendingRoute() {
        assertEquals("https://proxy.example:8443/api/v1/chat/completions?version=1", ChatEndpoint.resolve("https://proxy.example:8443/api/v1?version=1"))
    }
    @Test fun rejectsUntrustedUrlShapesBeforeCreatingRequest() {
        for (value in listOf("http://example.com/v1", "https://user:password@example.com", "https://example.com/#fragment")) {
            assertThrows(IllegalArgumentException::class.java) { ChatEndpoint.resolve(value) }
        }
    }
}
