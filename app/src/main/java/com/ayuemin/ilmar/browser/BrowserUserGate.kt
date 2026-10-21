package com.ayuemin.ilmar.browser

import org.json.JSONObject

/** A conservative second-line gate for snapshots that missed visible auth/CAPTCHA controls. */
internal object BrowserUserGate {
    private val authPath = Regex("""(?:^|/)(?:login|signin|sign-in|auth|authorization)(?:/|$)""", RegexOption.IGNORE_CASE)
    private val challenge = Regex("""(?:captcha|recaptcha|hcaptcha|verify you are human|подтвердите, что вы человек|капча)""", RegexOption.IGNORE_CASE)
    fun requiresUser(snapshot: JSONObject): Boolean {
        if (snapshot.optBoolean("requires_user")) return true
        val url = snapshot.optString("url")
        val path = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault("")
        val controls = snapshot.optJSONArray("controls")
        val formPresent = (0 until (controls?.length() ?: 0)).any { i ->
            val control = controls?.optJSONObject(i)
            control?.optString("tag") in setOf("input", "textarea") ||
                control?.optString("type") in setOf("password", "email")
        }
        if (authPath.containsMatchIn(path) && formPresent) return true
        val text = snapshot.optString("text").take(4000)
        return challenge.containsMatchIn(text)
    }
}
