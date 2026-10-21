package com.ayuemin.ilmar.github

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GitHubClientTest {
    private val credential = "github_pat_test_private_fixture"
    private fun client(server: MockWebServer, allowed: (Long) -> Boolean = { true }, approve: suspend (Long, String, String) -> Boolean = { _,_,_ -> true }) =
        GitHubClient({ credential }, allowed, approve, OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(), server.url("/"))

    @Test fun credentialsStayInHeaderAndPaginationAndRateLimitArePreserved() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[{\"name\":\"private\"}]").addHeader("Link", "<https://api.github.com/user/repos?page=2>; rel=\"next\"").addHeader("X-RateLimit-Remaining", "42"))
            val result = JSONObject(client(server).request(12, "GET", "/user/repos?per_page=1&page=1"))
            assertEquals(200, result.getInt("status")); assertEquals("42", result.getString("rate_remaining"))
            assertTrue(result.getString("next").contains("page=2"))
            val request = server.takeRequest()
            assertEquals("Bearer $credential", request.getHeader("Authorization"))
            assertEquals("/user/repos?per_page=1&page=1", request.path)
            assertFalse(result.toString().contains(credential))
        }
    }
    @Test fun writesRequireExactNativeApprovalAndSendOnce() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("{\"number\":1}"))
            val body = "{\"title\":\"review fixture\",\"body\":\"literal body\"}"
            var prompts = 0
            val api = client(server, approve = { chat, title, message ->
                assertEquals(71L, chat); assertTrue(title.contains("POST")); assertTrue(message.contains(body)); assertTrue(message.contains("/repos/test/repo/issues")); prompts++; true
            })
            assertTrue(JSONObject(api.request(71, "POST", "/repos/test/repo/issues", body)).getBoolean("ok"))
            assertEquals(body, server.takeRequest().body.readUtf8()); assertEquals(1, server.requestCount); assertEquals(1, prompts)
        }
    }
    @Test fun denialAndRevokeDuringApprovalNeverSend() = runTest {
        MockWebServer().use { server ->
            assertTrue(runCatching { client(server, approve = { _,_,_ -> false }).request(1, "DELETE", "/repos/test/repo") }.isFailure)
            var allowed = true
            assertTrue(runCatching { client(server, { allowed }, { _,_,_ -> allowed = false; true }).request(1, "PATCH", "/repos/test/repo", "{}") }.isFailure)
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun redirectsNeverForwardTokenAndErrorsKeepStatusWithoutRetry() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://example.com/leak"))
            val redirect = JSONObject(client(server).request(1, "GET", "/user"))
            assertEquals(302, redirect.getInt("status")); assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "60").setBody("{\"message\":\"rate limit\"}"))
            val limited = JSONObject(client(server).request(1, "GET", "/user"))
            assertFalse(limited.getBoolean("ok")); assertEquals("60", limited.getString("retry_after")); assertEquals(2, server.requestCount)
        }
    }
    @Test fun reflectedCredentialIsRedactedAndLargeResponseBounded() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(credential + "x".repeat(GitHubClient.MAX_RESPONSE + 2000)))
            val result = JSONObject(client(server).request(1, "GET", "/user"))
            assertTrue(result.getBoolean("truncated")); assertTrue(result.getString("body").length <= GitHubClient.MAX_RESPONSE)
            assertFalse(result.toString().contains(credential))
        }
    }
    @Test fun rejectOriginEscapeTraversalSecretsAndMalformedBodies() {
        assertEquals("api.github.com", GitHubClient.validate("GET", "/search/code?q=repo%3Aowner%2Frepo", "").host)
        assertEquals("api.github.com", GitHubClient.validate("GET", "/repos/owner/repo/branches/feature%2Ftest", "").host)
        for (path in listOf("https://evil.example/user", "//evil.example/user", "/user#fragment", "/repos/a/../b", "/repos/a/%2e%2e/b", "/user?access_token=secret", "/graphql", "/repos/a\\b")) {
            assertTrue(path, runCatching { GitHubClient.validate("GET", path, "") }.isFailure)
        }
        assertTrue(runCatching { GitHubClient.validate("GET", "/user", "{}") }.isFailure)
        assertTrue(runCatching { GitHubClient.validate("POST", "/user", "not json") }.isFailure)
        assertTrue(runCatching { GitHubClient.validate("POST", "/user", "{\"a\":\"" + "x".repeat(66000) + "\"}") }.isFailure)
    }
    @Test fun jobLogsUseFreshAnonymousRequestAndRemainBounded() = runTest {
        MockWebServer().use { api -> MockWebServer().use { storage ->
            api.enqueue(MockResponse().setResponseCode(302).addHeader("Location", storage.url("/signed-log?sig=fixture")))
            storage.enqueue(MockResponse().setBody("step one\nstep two\n"))
            val client = GitHubClient({ credential }, { true }, { _,_,_ -> false },
                OkHttpClient.Builder().followRedirects(false).build(), api.url("/"), { it.host == storage.hostName && it.port == storage.port })
            val result = JSONObject(client.request(1, "GET", "/repos/owner/repo/actions/jobs/123/logs"))
            assertEquals(200, result.getInt("status")); assertTrue(result.getString("body").contains("step two"))
            assertEquals("Bearer $credential", api.takeRequest().getHeader("Authorization"))
            assertNull(storage.takeRequest().getHeader("Authorization")); assertEquals(1, storage.requestCount)
        } }
    }
}
