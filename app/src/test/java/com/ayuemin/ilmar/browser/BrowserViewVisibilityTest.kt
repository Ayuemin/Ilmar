package com.ayuemin.ilmar.browser

import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BrowserViewVisibilityTest {
    @Test fun hiddenViewKeepsViewportAndCannotReceiveTouchesOrAccessibilityFocus() {
        val view = WebView(ApplicationProvider.getApplicationContext())
        view.layout(0, 0, 1080, 1800)
        BrowserViewVisibility.apply(view, false)
        assertEquals(View.INVISIBLE, view.visibility)
        assertFalse(view.isEnabled)
        assertFalse(view.isFocusable)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, view.importantForAccessibility)
        assertEquals(1080, view.width); assertEquals(1800, view.height)
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 20f, 20f, 0)
        try { assertFalse(view.dispatchTouchEvent(event)) } finally { event.recycle() }
        BrowserViewVisibility.apply(view, true)
        assertEquals(View.VISIBLE, view.visibility); assertTrue(view.isEnabled)
        BrowserViewVisibility.apply(view, false)
        assertEquals(1800, view.height)
        view.destroy()
    }
}
