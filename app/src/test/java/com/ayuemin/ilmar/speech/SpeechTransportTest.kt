package com.ayuemin.ilmar.speech

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import okio.Buffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SpeechTransportTest {
    @Test fun speechEndpointAndPayloadAreIndependentOfChatModel() {
        val request = SpeechTransport.request("https://api.example.com/v1/chat/completions", "key", "tts", "alloy", "hello")
        assertEquals("https://api.example.com/v1/audio/speech", request.url.toString())
        val buffer = Buffer(); request.body!!.writeTo(buffer); val body = JSONObject(buffer.readUtf8())
        assertEquals("tts", body.getString("model")); assertEquals("hello", body.getString("input"))
        assertFalse(body.has("messages"))
    }
    @Test fun openRouterUsesAudioChatProtocol() {
        val request = SpeechTransport.request("https://openrouter.ai/api/v1", "key", "vendor/audio", "alloy", "hello")
        assertEquals("https://openrouter.ai/api/v1/chat/completions", request.url.toString())
        val buffer = Buffer(); request.body!!.writeTo(buffer); val body = JSONObject(buffer.readUtf8())
        assertFalse(body.getBoolean("stream")); assertEquals("audio", body.getJSONArray("modalities").getString(1))
        assertEquals("mp3", body.getJSONObject("audio").getString("format"))
    }
}
