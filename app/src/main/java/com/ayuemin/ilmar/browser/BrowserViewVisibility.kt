package com.ayuemin.ilmar.browser

import android.view.View
import android.webkit.WebView

/** INVISIBLE preserves measurement and the document, unlike removing the AndroidView. */
internal object BrowserViewVisibility {
    fun apply(view: WebView, visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        view.isEnabled = visible
        view.isFocusable = visible
        view.isFocusableInTouchMode = visible
        view.importantForAccessibility = if (visible) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        view.isVerticalScrollBarEnabled = visible
        view.isHorizontalScrollBarEnabled = visible
        if (!visible) view.clearFocus()
        else view.post { view.requestLayout(); view.invalidate() }
    }
}
