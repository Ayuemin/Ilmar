package com.ayuemin.ilmar.model

import com.ayuemin.ilmar.chat.ChatMessage
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProviderTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val history = listOf(ChatMessage("hello", true))
    @Before fun setup() {
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .retryOnConnectionFailure(false).followRedirects(false).readTimeout(2, TimeUnit.SECONDS).build()
    }
    @After fun teardown() { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    @Test fun deliversCompleteAnswerWithExpectedModelAndKey() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\ndata: [DONE]\n\n"))
        val result = OpenAiCompatibleProvider(client).stream(server.url("/chat/completions").toString(), "chosen", "fake-key", history) {}
        assertEquals("answer", result)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("Bearer fake-key", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("chosen"))
        assertEquals(1, server.requestCount)
    }
    @Test fun preservesHttpStatusButNeverLeaksErrorBodyOrRetries() = runBlocking {
        for (code in listOf(401, 429)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("private-key and provider details"))
            val error = runCatching {
                OpenAiCompatibleProvider(client).stream(server.url("/").toString(), "m", "fake-key", history) {}
            }.exceptionOrNull()!!
            assertTrue(error.message!!.contains(code.toString()))
            assertFalse(error.message!!.contains("private"))
        }
        assertEquals(2, server.requestCount)
    }
    @Test fun cancelsActualCallWhileResponseBodyReadIsBlocked() = runBlocking {
        val headers = CompletableDeferred<Unit>()
        val failed = CountDownLatch(1)
        val observed = client.newBuilder().eventListener(object : EventListener() {
            override fun responseHeadersEnd(call: Call, response: Response) { headers.complete(Unit) }
            override fun callFailed(call: Call, ioe: IOException) { failed.countDown() }
        }).build()
        server.enqueue(MockResponse().setBodyDelay(3, TimeUnit.SECONDS).setBody("data: [DONE]\n\n"))
        val job = launch(Dispatchers.Default) {
            OpenAiCompatibleProvider(observed).stream(server.url("/").toString(), "m", "fake-key", history) {}
        }
        withTimeout(5000) { headers.await() }
        withTimeout(2000) { job.cancelAndJoin() }
        assertTrue("HTTP call must actually stop", failed.await(2, TimeUnit.SECONDS))
        observed.connectionPool.evictAll()
        assertEquals(1, server.requestCount)
    }
    @Test fun timeoutDoesNotRetryOrExposeRequestDetails() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val shortClient = client.newBuilder().readTimeout(100, TimeUnit.MILLISECONDS).build()
        val error = runCatching {
            withTimeout(5000) { OpenAiCompatibleProvider(shortClient).stream(server.url("/").toString(), "m", "fake-key", history) {} }
        }.exceptionOrNull()!!
        assertTrue(error is IOException)
        assertFalse(error.message.orEmpty().contains("fake-key"))
        assertEquals(1, server.requestCount)
    }
}
