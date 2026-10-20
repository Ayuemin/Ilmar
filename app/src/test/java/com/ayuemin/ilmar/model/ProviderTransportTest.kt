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
        for (code in listOf(401, 403, 404, 429)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("private-key and provider details"))
            val error = runCatching {
                OpenAiCompatibleProvider(client).stream(server.url("/").toString(), "m", "fake-key", history) {}
            }.exceptionOrNull()!!
            assertTrue(error.message!!.contains(code.toString()))
            assertFalse(error.message!!.contains("private"))
        }
        assertEquals(4, server.requestCount)
    }
    @Test fun openRouterStyleBaseIsPostedToChatRouteWithoutFallbackOrDuplicate() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("private provider error"))
        val error = runCatching {
            OpenAiCompatibleProvider(client).stream(server.url("/api/v1").toString(), "deepseek/deepseek-v4.1-flash", "fake-key", history) {}
        }.exceptionOrNull() as ProviderFailure
        assertEquals(404, error.httpStatus)
        assertTrue(error.message!!.contains("ID модели"))
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/chat/completions", request.path)
        assertEquals("deepseek/deepseek-v4.1-flash", org.json.JSONObject(request.body.readUtf8()).getString("model"))
        assertEquals(1, server.requestCount)
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

    @Test fun compactDisablesDefaultReasoningAndCommitsOnlyValidatedSummary() = runBlocking {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        app.deleteDatabase("ilmar_chats.db")
        com.ayuemin.ilmar.settings.RuntimeLimits.install(app)
        com.ayuemin.ilmar.settings.RuntimeLimits.reset()
        val db = com.ayuemin.ilmar.chat.ChatStore(app)
        try {
            db.create(1, "Compact")
            repeat(4) { db.append(1, ChatMessage("message $it " + "x".repeat(600), it % 2 == 0), null) }
            val original = db.load().single().messages
            val memory = com.ayuemin.ilmar.memory.ContextStore(db)
            val summary = """{"goal":["continue"],"facts":[],"decisions":[],"progress":[],"files":[],"tests":[],"errors":[],"next":[],"uncertain":[]}"""
            // Reproduce a model spending the entire completion budget on default reasoning.
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val payload = org.json.JSONObject(request.body.readUtf8())
                    val disabled = payload.optJSONObject("reasoning")?.opt("enabled") == false
                    val delta = if (disabled) org.json.JSONObject().put("content", summary)
                        else org.json.JSONObject().put("reasoning", "internal reasoning")
                    val choice = org.json.JSONObject().put("delta", delta).put("finish_reason", if (disabled) "stop" else "length")
                    return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                        "data: " + org.json.JSONObject().put("choices", org.json.JSONArray().put(choice)) + "\n\ndata: [DONE]\n\n")
                }
            }
            val caps = ModelCapabilities(setOf("max_tokens"), contextLength = 8192, maxOutputTokens = 1024)
            val target = ModelTarget(ProviderConnection("p", "OpenRouter", "https://openrouter.ai/api/v1"), RegisteredModel("m", "p", "model"), "fake-key")
            val provider = OpenAiCompatibleProvider(client)
            val engine = com.ayuemin.ilmar.memory.ContextEngine(1, memory, com.ayuemin.ilmar.attachments.AttachmentStore(app),
                com.ayuemin.ilmar.memory.ContextPolicy(recent = 2, automatic = false), caps, target,
                { history, options, _, _, _ -> provider.generate(server.url("/api/v1").toString(), "model", "fake-key", history, options) {} })
            engine.prepare(original, emptyList(), emptyList(), force = true)
            assertEquals(summary, memory.active(1)!!.text)
            assertEquals(original, db.load().single().messages)
            assertEquals(1, server.requestCount)
        } finally { db.close() }
    }

    @Test fun emptyReasoningCompletionIsAnExplicitFailureWithFinishAndUsage() = runBlocking {
        server.enqueue(MockResponse().setBody("""data: {"choices":[{"delta":{"reasoning":"hidden"},"finish_reason":"length"}],"usage":{"completion_tokens":1024,"completion_tokens_details":{"reasoning_tokens":1024}}}

data: [DONE]

"""))
        val error = runCatching { OpenAiCompatibleProvider(client).generate(server.url("/").toString(), "m", "fake-key", history, GenerationOptions()) {} }.exceptionOrNull() as ProviderFailure
        assertTrue(error.message!!, error.message!!.contains("лимит вывода"))
        assertTrue(error.details!!, error.details!!.contains("finish_reason=length"))
        assertTrue(error.details!!.contains("reasoning_tokens=1024"))
        assertEquals(1, server.requestCount)
    }

    @Test fun stoppingArchiveCancelsRealEmbeddingHttpButLeavesGenerationJobRunning() = runBlocking {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        app.deleteDatabase("ilmar_chats.db")
        app.getSharedPreferences("ilmar_embeddings", 0).edit().clear().commit()
        app.getSharedPreferences("ilmar_models", 0).edit().clear().commit()
        app.getSharedPreferences("ilmar_ui", 0).edit().clear().commit()
        app.getSharedPreferences("ilmar_context", 0).edit().clear().commit()
        val registry = ProviderRegistry(app, com.ayuemin.ilmar.chat.MemorySecrets())
        registry.saveConnection(null, "Embedding", server.url("/v1").toString(), listOf("embed"), "key")
        val connection = registry.snapshot().providers.single()
        com.ayuemin.ilmar.memory.EmbeddingPreferences(app).save(com.ayuemin.ilmar.memory.EmbeddingConfig(true, connection.id, "embed"))
        com.ayuemin.ilmar.chat.ChatStore(app).use { db ->
            db.create(1, "Archive")
            repeat(8) { db.append(1, ChatMessage("Meaningful fact $it", true), null) }
        }
        val headers = CompletableDeferred<Unit>(); val failed = CountDownLatch(1)
        val observed = client.newBuilder().eventListener(object : EventListener() {
            override fun responseHeadersEnd(call: Call, response: Response) { headers.complete(Unit) }
            override fun callFailed(call: Call, ioe: IOException) { failed.countDown() }
        }).build()
        server.enqueue(MockResponse().setBodyDelay(3, TimeUnit.SECONDS).setBody("{\"data\":[]}"))
        val indexScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val retrieval = com.ayuemin.ilmar.memory.ArchiveRetrieval(app, registry, com.ayuemin.ilmar.memory.EmbeddingClient(observed))
        val generationGate = CompletableDeferred<Unit>()
        val generation = launch { generationGate.await() }
        try {
            retrieval.schedule(indexScope, 1, {}, force = true)
            withTimeout(5000) { headers.await() }
            retrieval.cancel(1)
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            withTimeout(5000) { indexScope.coroutineContext[Job]!!.children.toList().joinAll() }
            assertTrue(generation.isActive)
            generationGate.complete(Unit); generation.join()
            assertEquals(1, server.requestCount)
            com.ayuemin.ilmar.chat.ChatStore(app).use { db ->
                assertEquals("cancelled", db.readableDatabase.rawQuery("SELECT status FROM embedding_requests", null).use { assertTrue(it.moveToFirst()); it.getString(0) })
            }
        } finally { indexScope.cancel(); generation.cancelAndJoin() }
    }
}
