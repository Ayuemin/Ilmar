package com.ayuemin.ilmar.browser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.agent.AgentFailure
import com.ayuemin.ilmar.chat.ChatModePreferences
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BrowserAuditPermissionTest {
    private fun modes(chat: Long) = ChatModePreferences(ApplicationProvider.getApplicationContext<Context>()).apply {
        setEnabled(chat, "Агент", true); setEnabled(chat, "tool_Браузер", true)
    }
    @Test fun globalDenyExposesNoBrowserTools() {
        assertTrue(BrowserTools.forChat(801, modes(801), false, true, globalAllowed = { false }).isEmpty())
    }
    @Test fun neutralSubmitNeedsNativeApprovalAndRevocationCannotBeApprovedAway() = runTest {
        var allowed = true; var prompts = 0
        val tools = BrowserTools.forChat(802, modes(802), false, true, globalAllowed = { allowed },
            approveAction = { _, title, _ -> if (title == "Общая сессия браузера") true else { prompts++; false } })
        val click = tools.single { it.schema.name == "browser_click" }
        val args = JSONObject().put("page_id", "page").put("ref", 1)
        val denied = runCatching { click.beforeExecute(args) }.exceptionOrNull() as AgentFailure
        assertEquals("BROWSER_CANCELLED", denied.code); assertEquals(1, prompts)
        allowed = false
        assertEquals("PERMISSION_REVOKED", (runCatching { click.execute(args) }.exceptionOrNull() as AgentFailure).code)
        assertEquals(1, prompts) // No executor or new consent after revoke.
        BrowserRuntime.forgetChat(802)
    }
    @Test fun approvalCannotGrantRevokedCapability() = runTest {
        var allowed = true
        val tools = BrowserTools.forChat(803, modes(803), false, true, globalAllowed = { allowed },
            approveAction = { _, _, _ -> allowed = false; true })
        assertEquals("PERMISSION_REVOKED", (runCatching { tools.first().beforeExecute(JSONObject()) }.exceptionOrNull() as AgentFailure).code)
        BrowserRuntime.forgetChat(803)
    }
    @Test fun changingSessionChatNeedsNewExplicitConsent() = runTest {
        var prompts = 0
        val approve: suspend (Long, String, String) -> Boolean = { _, _, _ -> prompts++; true }
        BrowserRuntime.confirmSession(901, approve); BrowserRuntime.confirmSession(901, approve)
        assertEquals(1, prompts)
        BrowserRuntime.confirmSession(902, approve); assertEquals(2, prompts)
        assertTrue(runCatching { BrowserRuntime.confirmSession(903) { _,_,_ -> false } }.exceptionOrNull() is AgentFailure)
        BrowserRuntime.forgetChat(902)
    }
}
