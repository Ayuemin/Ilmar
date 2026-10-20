package com.ayuemin.ilmar.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.model.TokenUsage
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UsageMigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun reset() { context.deleteDatabase("ilmar_chats.db") }
    @Test fun upgradesV1WithoutDeletingHistoryAndPersistsUsageOnce() {
        context.openOrCreateDatabase("ilmar_chats.db", 0, null).use { db ->
            db.execSQL("CREATE TABLE chats (id INTEGER PRIMARY KEY, title TEXT NOT NULL)")
            db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER NOT NULL, text TEXT NOT NULL, from_user INTEGER NOT NULL)")
            db.execSQL("INSERT INTO chats VALUES(42, 'Old chat')")
            db.execSQL("INSERT INTO messages(chat_id,text,from_user) VALUES(42, 'Old message', 1)")
            db.version = 1
        }
        val original = ChatStore(context)
        try {
            val store = original
            assertEquals("Old message", store.load().single().messages.single().text)
            store.recordUsage(42, "same", TokenUsage(10, 4, 14, 5, null, "0.1"), "completed")
            store.recordUsage(42, "same", TokenUsage(costUsd = "99"), "completed")
        } finally { original.close() }
        val reopened = ChatStore(context)
        try {
            val store = reopened
            assertEquals(1, store.usage(42).calls)
            assertEquals("0.1", store.usage(42).costUsd)
            assertEquals(5L, store.usage(42).cached)
            store.clearMessages(42)
            assertEquals(0, store.usage(42).calls)
        } finally { reopened.close() }
    }
    @Test fun sumsExactDecimalsAndLabelsUnknownCosts() {
        val ledger = UsageLedger(listOf(UsageRecord(TokenUsage(costUsd = "0.1"), "completed"),
            UsageRecord(TokenUsage(costUsd = "0.2"), "completed"), UsageRecord(TokenUsage(), "cancelled")))
        assertEquals("0.3", ledger.costUsd)
        assertTrue(ledger.incompleteCost)
        assertNull(ledger.input)
        assertEquals(1, ledger.failed)
        assertTrue(ledger.describe().contains("известная часть"))
        assertNull(UsageLedger(listOf(UsageRecord(TokenUsage(), "failed"))).costUsd)
    }
}
