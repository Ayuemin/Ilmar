package com.ayuemin.ilmar.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BrowserUserGateTest {
    @Test fun passwordFormRequiresImmediateHandoff() {
        val page = JSONObject().put("url", "https://4pda.to/forum/index.php?act=Login")
            .put("requires_user", true).put("text", "")
        assertTrue(BrowserUserGate.requiresUser(page))
    }
    @Test fun loginPathWithInputRequiresHandoffEvenWithoutRequiresUserFlag() {
        val page = JSONObject().put("url", "https://example.com/login")
            .put("text", "").put("controls", JSONArray().put(JSONObject().put("tag", "input").put("type", "text")))
        assertTrue(BrowserUserGate.requiresUser(page))
    }
    @Test fun captchaRequiresHandoff() {
        val page = JSONObject().put("url", "https://example.com/download")
            .put("text", "Verify you are human")
        assertTrue(BrowserUserGate.requiresUser(page))
    }
    @Test fun normalPageWithLoginLinkDoesNotRequireHandoff() {
        val page = JSONObject().put("url", "https://example.com/articles")
            .put("text", "Click Login in the menu to access your account")
            .put("controls", JSONArray())
        assertFalse(BrowserUserGate.requiresUser(page))
    }
}
