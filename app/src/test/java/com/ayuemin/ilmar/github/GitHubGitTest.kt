package com.ayuemin.ilmar.github

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.alpine.AlpineRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GitHubGitTest {
    private val token = "github_pat_test_secret_fixture"
    private fun runtime() = AlpineRuntime(ApplicationProvider.getApplicationContext<Context>())
    private fun args(action: String) = JSONObject().put("action", action).put("repository", "owner/repo").put("branch", "feature/check")

    @Test fun pushUsesCredentialHelperWithoutSecretInCommandAndNoForce() = runTest {
        var approved = false; var ran = false
        val git = GitHubGit(runtime(), { token }, { "Author" to "author@example.com" }, { true },
            { _, _, message -> assertTrue(message.contains("feature/check")); assertFalse(message.contains(token)); approved = true; true },
            runner = { command, _, allowed ->
                assertTrue(approved); assertTrue(allowed()); assertFalse(command.contains(token)); assertFalse(command.contains("--force"))
                assertTrue(command.contains("push --porcelain -- 'https://github.com/owner/repo.git' 'HEAD:refs/heads/feature/check'"))
                assertTrue(command.contains("core.hooksPath=/dev/null")); assertTrue(command.contains("credential.helper="))
                val url = Regex("http://127\\.0\\.0\\.1:[0-9]+/[a-f0-9-]+").find(command)!!.value
                val credential = withContext(Dispatchers.IO) {
                    val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    try {
                        connection.requestMethod = "POST"; connection.doOutput = true; connection.setFixedLengthStreamingMode(0)
                        connection.outputStream.close(); connection.inputStream.bufferedReader().readText()
                    } finally { connection.disconnect() }
                }
                assertTrue(credential.contains("password=$token")); ran = true
                "{\"exit_code\":0,\"output\":\"pushed\"}"
            })
        assertEquals(0, JSONObject(git.execute(1, args("push"))).getInt("exit_code")); assertTrue(ran)
    }
    @Test fun denyOrRevokeCannotRunGit() = runTest {
        var ran = false
        val denied = GitHubGit(runtime(), { token }, { "a" to "b" }, { true }, { _,_,_ -> false }, runner = { _,_,_ -> ran = true; "{}" })
        assertTrue(runCatching { denied.execute(1, args("push")) }.isFailure); assertFalse(ran)
        var allowed = true
        val revoked = GitHubGit(runtime(), { token }, { "a" to "b" }, { allowed }, { _,_,_ -> allowed = false; true }, runner = { _,_,_ -> ran = true; "{}" })
        assertTrue(runCatching { revoked.execute(1, args("push")) }.isFailure); assertFalse(ran)
    }
    @Test fun commitUsesSavedIdentityAndQuotesMessageAsData() = runTest {
        val message = "fix: literal '\$(touch /tmp/never-execute)'"
        val git = GitHubGit(runtime(), { null }, { "My Author" to "private@noreply.example" }, { true }, { _,_,_ -> true }, runner = { command, _, _ ->
            assertTrue(command.contains("user.name='My Author'")); assertTrue(command.contains("user.email='private@noreply.example'"))
            assertTrue(command.contains("commit --message=" + GitCredentialBridge.quote(message)))
            assertFalse(command.contains("http://127.0.0.1")); "{\"exit_code\":0}"
        })
        assertEquals(0, JSONObject(git.execute(1, args("commit").put("message", message))).getInt("exit_code"))
    }
    @Test fun branchesPathsAndRemoteCannotInjectShellOrForceRefspec() {
        for (value in listOf("../escape", "a:refs/heads/main", "-f", "a;touch /tmp/oops", "refs//x", "a.lock", "a..b")) assertTrue(value, runCatching { GitHubGit.branch(value) }.isFailure)
        for (value in listOf("../outside", "a/b", ";oops", ".")) assertTrue(value, runCatching { GitHubGit.directory(value) }.isFailure)
        for (value in listOf("https://github.com/a/b", "a/b;rm", "a/b/c", "a/..")) assertTrue(value, runCatching { GitHubGit.repository(value) }.isFailure)
    }
    @Test fun pushRequiresExplicitBranch() = runTest {
        val git = GitHubGit(runtime(), { token }, { "a" to "b" }, { true }, { _,_,_ -> true }, runner = { _,_,_ -> error("must not run") })
        assertTrue(runCatching { git.execute(1, args("push").apply { remove("branch") }) }.isFailure)
    }
}
