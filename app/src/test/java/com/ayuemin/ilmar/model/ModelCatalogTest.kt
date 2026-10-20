package com.ayuemin.ilmar.model

import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ModelCatalogTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    @Before fun setup() {
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .followRedirects(false).retryOnConnectionFailure(false).readTimeout(2, TimeUnit.SECONDS).build()
    }
    @After fun teardown() { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    @Test fun loadsFullIdsFromCorrectProviderRouteWithItsCredential() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"vendor/b"},{"id":"vendor/a"},{"id":"vendor/a"}]}"""))
        assertEquals(listOf("vendor/a", "vendor/b"), ModelCatalogClient(client).load(server.url("/api/v1/chat/completions").toString(), "provider-key"))
        val request = server.takeRequest()!!
        assertEquals("GET", request.method)
        assertEquals("/api/v1/models", request.path)
        assertEquals("Bearer provider-key", request.getHeader("Authorization"))
    }
    @Test fun endpointUsesSameHostAndPreservesVersionQueries() {
        assertEquals("https://openrouter.ai/api/v1/models?output_modalities=all", ModelCatalogClient.modelsUrl("https://openrouter.ai/api/v1"))
        assertEquals("https://example.com/v1/models?version=1", ModelCatalogClient.modelsUrl("https://example.com/v1/chat/completions?version=1"))
        assertThrows(IllegalArgumentException::class.java) { ModelCatalogClient.modelsUrl("https://example.com/custom") }
    }
    @Test fun errorDoesNotExposeResponseBodyOrRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("private provider information"))
        val error = runCatching { ModelCatalogClient(client).load(server.url("/v1").toString(), "secret") }.exceptionOrNull()!!
        assertTrue(error.message.orEmpty().contains("401"))
        assertFalse(error.message.orEmpty().contains("private"))
        assertEquals(1, server.requestCount)
    }
    @Test fun rejectsMalformedCatalogRatherThanReplacingCacheWithEmptyList() {
        assertThrows(Exception::class.java) { ModelCatalogClient.parse("{}") }
        assertThrows(Exception::class.java) { ModelCatalogClient.parse("not-json") }
        assertEquals(emptyList<String>(), ModelCatalogClient.parse("""{"data":[]}"""))
    }
}
