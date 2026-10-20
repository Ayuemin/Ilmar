package com.ayuemin.ilmar.browser

import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.chat.ChatStore
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BrowserJournalTest {
    @Test fun unknownWriteAfterReopenBlocksChangedPageRefsButAllowsReadsAndUserTakeover() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        app.deleteDatabase("ilmar_chats.db")
        ChatStore(app).use { db ->
            db.create(1, "Browser")
            db.writableDatabase.execSQL("INSERT INTO task_executions VALUES(?,?,?,?,?,?,?,?)", arrayOf("old:click", 1, "old", "browser_click", "{\"page_id\":\"old\",\"ref\":3}", "UNKNOWN", null, 1))
        }
        ChatStore(app).use { db ->
            val changed = "{\"page_id\":\"new-after-restart\",\"ref\":99}"
            for (name in listOf("browser_click", "browser_type", "browser_select")) assertTrue(db.unresolvedExecution(1, "new:action", name, changed))
            for (name in listOf("browser_open", "browser_read", "browser_search", "browser_screenshot", "browser_takeover", "calculator")) assertFalse(db.unresolvedExecution(1, "new:read", name, "{}"))
            assertFalse(db.unresolvedExecution(2, "new:action", "browser_click", changed))
            assertFalse(db.unresolvedExecution(1, "old:click", "browser_click", changed))
        }
    }
}
