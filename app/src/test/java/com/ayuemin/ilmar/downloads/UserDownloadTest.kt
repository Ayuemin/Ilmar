package com.ayuemin.ilmar.downloads

import android.content.Context
import android.net.Uri
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.agent.AgentFailure
import com.ayuemin.ilmar.agent.AgentLoopRunner
import com.ayuemin.ilmar.attachments.AttachmentStore
import com.ayuemin.ilmar.browser.*
import com.ayuemin.ilmar.model.*
import com.ayuemin.ilmar.settings.RuntimeLimits
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.*
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UserDownloadTest {
    private lateinit var app: Context
    private lateinit var server: MockWebServer
    private lateinit var transport: UserDownloadTransport
    private lateinit var client: OkHttpClient
    private lateinit var serverTls: HandshakeCertificates
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext(); RuntimeLimits.install(app); RuntimeLimits.reset()
        // Robolectric gives each method a new external-files root; AndroidX caches roots by authority.
        org.robolectric.util.ReflectionHelpers.getStaticField<MutableMap<*, *>>(androidx.core.content.FileProvider::class.java, "sCache").clear()
        app.getSharedPreferences("ilmar_user_downloads", 0).edit().clear().commit()
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
            .protocols(listOf(Protocol.HTTP_1_1)).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        transport = UserDownloadTransport(client)
        Dispatchers.setMain(StandardTestDispatcher())
    }
    @After fun cleanup() { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); RuntimeLimits.reset(); Dispatchers.resetMain() }
    private class Sink(private val fail: Boolean = false) : DownloadDestination {
        var bytes = 0L; @Volatile var complete = false; @Volatile var aborted = false
        override val output = object : OutputStream() {
            override fun write(b: Int) { if (fail) throw IOException("disk full"); bytes++ }
            override fun write(b: ByteArray, off: Int, len: Int) { if (fail) throw IOException("disk full"); bytes += len }
        }
        override fun complete(bytes: Long): SavedUserDownload { complete = true; return SavedUserDownload("download:test", "Download/Ilmar/test.bin", "content://downloads/test", bytes) }
        override fun abort() { aborted = true }
    }
    @Test fun streamingHasNoMegabyteOrIntSizeCeilingAndStillHonorsExplicitCap() {
        val expected = Int.MAX_VALUE.toLong() + 12345
        var remaining = expected
        val source = object : InputStream() {
            override fun read(): Int = error("Use bounded chunks")
            override fun read(b: ByteArray, off: Int, len: Int): Int { if (remaining == 0L) return -1; val count = minOf(remaining, len.toLong()).toInt(); remaining -= count; return count }
        }
        val sink = Sink()
        assertEquals(expected, UserDownloadTransport.stream(source, sink.output)); assertEquals(expected, sink.bytes)
        assertEquals(0, RuntimeLimits.get("download_mib")); assertEquals(0, RuntimeLimits.get("download_seconds"))
        assertTrue(runCatching { UserDownloadTransport.stream(ByteArrayInputStream(ByteArray(5000)), Sink().output, 4000) }.isFailure)
    }
    @Test fun chunkedFileIsSavedWithoutImportAndModelGetsOnlyIdStatusAndLocation() = runTest {
        val marker = "USER_FILE_BYTES_MUST_NOT_ENTER_API"
        server.enqueue(MockResponse().setChunkedBody(marker.repeat(3000), 4096).setHeader("Content-Type", "application/octet-stream"))
        val sink = Sink(); val protected = mutableListOf<String>()
        val saved = transport.save(server.url("/file.bin").toString(), null, emptyMap(), 0, { protected += it }, {}, { _, _ -> sink })
        assertEquals(marker.length * 3000L, saved.bytes); assertTrue(sink.complete); assertFalse(sink.aborted)
        val result = saved.modelResult()
        assertFalse(result.toString().contains(marker)); assertFalse(result.has("text")); assertFalse(result.has("image")); assertFalse(result.has("uri"))
        assertEquals("download:test", result.getString("download_id")); assertFalse(result.getBoolean("content_available_to_model"))
        assertEquals(1, protected.size)
    }
    @Test fun redirectsDropSessionHeadersAcrossOriginsAndRejectNonHttpsBeforeFollowing() = runTest {
        val other = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/file")))
            other.enqueue(MockResponse().setBody("opaque bytes"))
            val saved = transport.save(server.url("/redirect").toString(), null, mapOf("Cookie" to "private-session"), 0, {}, {}, { _, _ -> Sink() })
            assertEquals(12L, saved.bytes)
            assertEquals("private-session", server.takeRequest().getHeader("Cookie")); assertNull(other.takeRequest().getHeader("Cookie"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://localhost:1/private"))
            val error = runCatching { transport.save(server.url("/bad").toString(), null, emptyMap(), 0, {}, {}, { _, _ -> Sink() }) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException); assertEquals(2, server.requestCount)
        } finally { other.shutdown() }
    }

    @Test fun fileLimitDiskFailureAndCancellationNeverPublishPartialFile() = runTest {
        server.enqueue(MockResponse().setBody("abcdef"))
        var destinations = 0
        assertTrue(runCatching { transport.save(server.url("/large").toString(), null, emptyMap(), 2, {}, {}, { _, _ -> destinations++; Sink() }) }.isFailure)
        assertEquals(0, destinations)
        server.enqueue(MockResponse().setBody("abcdef"))
        val full = Sink(fail = true)
        assertTrue(runCatching { transport.save(server.url("/full").toString(), null, emptyMap(), 0, {}, {}, { _, _ -> full }) }.isFailure)
        assertTrue(full.aborted); assertFalse(full.complete)
        server.enqueue(MockResponse().setBody("x".repeat(100000)).throttleBody(1024, 1, TimeUnit.SECONDS))
        val started = CompletableDeferred<Unit>(); val partial = Sink()
        val task = async { transport.save(server.url("/slow").toString(), null, emptyMap(), 0, {}, {}, { _, _ -> started.complete(Unit); partial }) }
        started.await(); task.cancelAndJoin()
        withContext(Dispatchers.IO) { repeat(100) { if (partial.aborted) return@withContext; Thread.sleep(5) } }
        assertTrue(partial.aborted); assertFalse(partial.complete)
    }
    @Test fun userDeclineHappensBeforeNetworkAndBeforeExecutionIntent() = runTest {
        var started = false; var models = 0
        val tool = BrowserDownload.tool(app, 1, BrowserRunControl(1, false), {}, { true }, ask = { _, _, _ -> false }, transport = transport)
        val error = runCatching {
            AgentLoopRunner(onCheckpoint = { _, messages -> started = started || messages.first().optJSONArray("_started_calls")?.length()?.let { it > 0 } == true }).run(listOf(tool)) { _, _ ->
                models++; GenerationResult("", toolCalls = listOf(ModelToolCall("file", "browser_download", JSONObject().put("url", server.url("/file")).toString())))
            }
        }.exceptionOrNull() as AgentFailure
        assertEquals("DOWNLOAD_CANCELLED", error.code); assertEquals(1, models); assertFalse(started); assertEquals(0, server.requestCount)
        assertTrue(app.getSharedPreferences("ilmar_user_downloads", 0).all.isEmpty())
    }
    @Test fun approvedDownloadReturnsOnlyMetadataAcrossEntireModelLoopAndModeChangeRevokesIt() = runTest {
        val marker = "OPAQUE_USER_DOWNLOAD_NEVER_UPLOAD"
        server.enqueue(MockResponse().setBody(marker))
        val tool = BrowserDownload.tool(app, 1, BrowserRunControl(1, false), {}, { true }, ask = { _, _, _ -> true }, transport = transport)
        var requests = 0; var result = JSONObject()
        AgentLoopRunner().run(listOf(tool)) { history, _ ->
            val body = GenerationPayload.build("fake-model", emptyList(), GenerationOptions(toolHistory = history))
            assertFalse(body.toString().contains(marker))
            if (requests++ == 0) GenerationResult("", toolCalls = listOf(ModelToolCall("d", "browser_download", JSONObject().put("url", server.url("/file").toString()).toString())))
            else { result = JSONObject(history.last().getString("content")); GenerationResult("File saved for you") }
        }
        assertEquals(2, requests); assertEquals(1, server.requestCount); assertEquals(result.toString(), "saved_for_user", result.optString("status"))
        assertTrue(result.getString("download_id").startsWith("download:")); assertFalse(result.has("text"))
        var allowed = true
        val revoked = BrowserDownload.tool(app, 1, BrowserRunControl(1, false), {}, { allowed }, ask = { _, _, _ -> allowed = false; true }, transport = transport)
        val error = runCatching { revoked.beforeExecute(JSONObject().put("url", server.url("/second").toString())) }.exceptionOrNull() as AgentFailure
        assertEquals("PERMISSION_REVOKED", error.code); assertEquals(1, server.requestCount)
    }
    @Test fun savedUserFileCannotBeImportedOrReadAsAnAttachmentAndProtectedUrlsStayBlocked() = runTest {
        val store = UserDownloadStore(app)
        val target = store.destination("../../report.zip", "application/zip")
        target.output.write("PRIVATE-DOWNLOADED-BYTES".toByteArray())
        val saved = target.complete(24)
        assertTrue(store.isUserFile(Uri.parse(saved.uri)))
        assertTrue(runCatching { AttachmentStore(app).import(Uri.parse(saved.uri)) }.isFailure)
        assertTrue(runCatching { AttachmentStore(app).file(saved.id) }.isFailure)
        store.protect("https://example.com/export?token=secret")
        assertFalse(store.modelReadable("https://example.com/export?token=another")); assertTrue(store.modelReadable("https://example.com/public"))
        assertFalse(store.modelReadable("file:///sdcard/Download/report.zip"))
        assertEquals("report.zip", UserDownloadStore.safeName("../../report.zip"))
        assertTrue(UserDownloadStore.safeName("😀".repeat(200)).toByteArray(Charsets.UTF_8).size <= 180)
        assertFalse(saved.modelResult().toString().contains("PRIVATE-DOWNLOADED-BYTES"))
        // A completed download is not deleted by an abort/late task cancellation.
        target.abort(); assertTrue(File(saved.location).exists())
    }
    @Test fun webViewOnlyOffersDownloadsAndDataUrlsNeverLeakBytesIntoObservation() {
        val view = WebView(app); val page = AndroidBrowserPage(view)
        val listener = shadowOf(view).downloadListener
        listener.onDownloadStart("data:text/plain;base64,UEVSTUFORU5ULVNFQ1JFVA==", "UA", null, "text/plain", 24)
        val field = page.javaClass.getDeclaredField("downloadHint").apply { isAccessible = true }
        val hint = field.get(page) as JSONObject
        assertFalse(hint.toString().contains("UEVSTUFORU5ULVNFQ1JFVA")); assertFalse(hint.has("url"))
        assertEquals("download_source_unsupported", hint.getString("status"))
        assertTrue(app.getSharedPreferences("ilmar_user_downloads", 0).all.isEmpty())
        page.stop(); view.destroy()
    }
}
