package com.ayuemin.ilmar.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.attachments.AttachmentStore
import com.ayuemin.ilmar.chat.*
import com.ayuemin.ilmar.model.*
import com.ayuemin.ilmar.settings.RuntimeLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TokenEconomyTest {
    private lateinit var app: Context
    private lateinit var db: ChatStore
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext(); app.deleteDatabase("ilmar_chats.db")
        listOf("ilmar_embeddings", "ilmar_context", "ilmar_models").forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
        RuntimeLimits.install(app); RuntimeLimits.reset(); db = ChatStore(app); db.create(1, "Economy")
    }
    @After fun cleanup() { db.close(); RuntimeLimits.reset() }
    @Test fun batchesDifferentMessagesKeepsShortFactsAndSkipsOnlyControlTurns() = runTest {
        val facts = listOf("Версия 42", "Бюджет 5 USD", "Ветка protected", "Имя Илья", "Срок пятница", "Код 123", "Да", "Нет")
        (facts + listOf("далее", "Продолжай!")).forEach { db.append(1, ChatMessage(it, true), null) }
        var requests = 0; val inputs = mutableListOf<String>()
        val archive = SemanticArchive(db)
        archive.index(1, db.load().single().messages, "space", { texts -> requests++; inputs += texts; texts.map { floatArrayOf(1f) } })
        assertEquals("8 factual messages should use one batch, not eight requests", 1, requests)
        assertEquals(facts, inputs); assertEquals(10, db.load().single().messages.size)
        archive.index(1, db.load().single().messages, "space", { error("Persisted vectors must not be bought again") })
    }
    @Test fun interruptedCrossMessageBatchResumesOnlyMissingVectorsAfterDatabaseReopen() = runTest {
        RuntimeLimits.save(RuntimeLimits.fields.associate { it.key to RuntimeLimits.get(it.key) } + ("embedding_batch" to 2))
        repeat(5) { db.append(1, ChatMessage("Fact $it", true), null) }
        var calls = 0
        assertTrue(runCatching { SemanticArchive(db).index(1, db.load().single().messages, "space", { texts ->
            if (++calls == 2) throw CancellationException("stop")
            texts.map { floatArrayOf(1f) }
        }) }.isFailure)
        db.close(); db = ChatStore(app)
        assertEquals(2, SemanticArchive(db).count(1, "space"))
        val bought = mutableListOf<String>()
        SemanticArchive(db).index(1, db.load().single().messages, "space", { texts -> bought += texts; texts.map { floatArrayOf(1f) } })
        assertEquals(listOf("Fact 2", "Fact 3", "Fact 4"), bought)
    }
    @Test fun semanticQueryCacheSurvivesRetrievalObjectAndDatabaseRestartAndContinueMakesNoRequest() = runTest {
        val registry = ProviderRegistry(app, MemorySecrets())
        registry.saveConnection(null, "Embeddings", "https://embed.example/v1", listOf("embed"), "key")
        val connection = registry.snapshot().providers.single()
        val config = EmbeddingConfig(true, connection.id, "embed")
        EmbeddingPreferences(app).save(config)
        db.append(1, ChatMessage("Delivery takes three days", false), null)
        SemanticArchive(db).index(1, db.load().single().messages, config.space(connection.endpoint), { texts -> texts.map { floatArrayOf(1f, 0f) } })
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).code(200).message("OK").protocol(Protocol.HTTP_1_1).body(
                """{"data":[{"index":0,"embedding":[1,0]}],"usage":{"prompt_tokens":5,"total_tokens":5,"prompt_tokens_details":{"cached_tokens":2},"cost":0.0000001234567890123456789}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        assertTrue(ArchiveRetrieval(app, registry, EmbeddingClient(http)).search(1, "When will it arrive?", Long.MAX_VALUE).contains("Delivery"))
        db.close(); db = ChatStore(app)
        val restarted = ArchiveRetrieval(app, registry, EmbeddingClient(http))
        assertTrue(restarted.search(1, "When will it arrive?", Long.MAX_VALUE).contains("Delivery"))
        repeat(5) { assertEquals("", restarted.search(1, "далее", Long.MAX_VALUE)) }
        assertEquals(1, requests)
        val usage = EmbeddingLedger(db).summary(1)
        assertEquals(1, usage.getInt("calls")); assertEquals(5L, usage.getLong("input_tokens")); assertEquals(2L, usage.getLong("cached_tokens"))
        assertEquals("0.0000001234567890123456789", usage.getString("known_cost_usd")); assertFalse(usage.getBoolean("incomplete_cost"))
        db.clearMessages(1)
        assertNull(SemanticArchive(db).queryVector(1, config.space(connection.endpoint), "When will it arrive?"))
    }
    @Test fun missingEmbeddingUsageRemainsUnknownAndInterruptedLedgerIsNotFree() {
        val ledger = EmbeddingLedger(db)
        val id = ledger.start(1, "index", "embed", 2, 40); ledger.finish(id, "completed", TokenUsage())
        ledger.start(1, "query", "embed", 1, 20)
        db.close(); db = ChatStore(app)
        EmbeddingLedger(db).interruptOpen()
        val summary = EmbeddingLedger(db).summary(1)
        assertEquals(2, summary.getInt("calls")); assertTrue(summary.isNull("known_cost_usd")); assertTrue(summary.isNull("input_tokens"))
        assertTrue(summary.getBoolean("incomplete_cost"))
        assertEquals("interrupted", db.readableDatabase.rawQuery("SELECT status FROM embedding_requests WHERE purpose='query'", null).use { it.moveToFirst(); it.getString(0) })
    }
    @Test fun lateEmbeddingCannotReintroduceAnEditedSource() = runTest {
        val message = db.append(1, ChatMessage("Old source", true), null)
        SemanticArchive(db).index(1, listOf(message), "space", { texts ->
            db.writableDatabase.execSQL("UPDATE messages SET text='Updated source' WHERE id=?", arrayOf(message.id))
            texts.map { floatArrayOf(1f) }
        })
        assertEquals(0, SemanticArchive(db).count(1, "space"))
    }
    @Test fun boundedWorkingWindowReducesCumulativeContextAcrossSeveralCompactions() = runTest {
        val caps = ModelCapabilities(setOf("max_tokens"), contextLength = 1000000, maxOutputTokens = 4096)
        val target = ModelTarget(ProviderConnection("p", "test", "https://example.com/v1"), RegisteredModel("m", "p", "model"), "key")
        val summary = """{"goal":["Continue"],"facts":["Version 42"],"decisions":[],"progress":[],"files":[],"tests":[],"errors":[],"next":[],"uncertain":[]}"""
        val memory = ContextStore(db); memory.setRules(1, "Never delete protected branch")
        var paidCompactInput = 0L; var compactions = 0; var projected = 0L; var naive = 0L
        repeat(100) { index ->
            db.append(1, ChatMessage("Version 42 question $index " + "x".repeat(800), true), null)
            db.append(1, ChatMessage("answer $index " + "y".repeat(800), false), null)
            val history = db.load().single().messages
            val result = ContextEngine(1, memory, AttachmentStore(app), ContextPolicy(), caps, target,
                { _, _, _, input, _ -> paidCompactInput += input; compactions++; GenerationResult(summary) }).prepare(history, emptyList(), emptyList())
            naive += ContextEstimate.messages(history); projected += result.estimatedInput
            assertEquals(history.takeLast(6), result.history.takeLast(6))
            assertTrue(result.options.systemPrefix.contains("protected branch"))
            assertTrue(result.estimatedInput + result.output <= 32768)
        }
        assertTrue("Compactions: $compactions", compactions >= 2)
        assertTrue("projected=$projected compact=$paidCompactInput naive=$naive", projected + paidCompactInput < naive * 0.75)
        assertEquals(200, db.load().single().messages.size)
        assertTrue(memory.search(1, "Version 42").contains("Version 42"))
    }
    @Test fun versionSevenMigrationPreservesHistoryAndExistingPaidVectors() = runTest {
        val message = db.append(1, ChatMessage("Paid original fact", true), null)
        SemanticArchive(db).index(1, listOf(message), "space", { texts -> texts.map { floatArrayOf(1f) } })
        db.close()
        android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath("ilmar_chats.db").path, null, 0).use { old ->
            old.execSQL("DROP TABLE embedding_queries"); old.execSQL("DROP TABLE embedding_requests"); old.version = 7
        }
        db = ChatStore(app)
        assertEquals("Paid original fact", db.load().single().messages.single().text)
        SemanticArchive(db).index(1, db.load().single().messages, "space", { error("Migration must not rebuy existing vectors") })
        assertEquals(1, SemanticArchive(db).count(1, "space"))
        assertEquals(0, EmbeddingLedger(db).summary(1).getInt("calls"))
    }
    @Test fun backgroundAccumulatesSixteenContinuedAnswersIntoOneRequestAndManualFlushRemainsAvailable() = runTest {
        var paidRequests = 0; var embedded = 0
        suspend fun background() = SemanticArchive(db).indexBatched(1, db.load().single().messages, "space", { texts -> paidRequests++; embedded += texts.size; texts.map { floatArrayOf(1f) } }, flushPartial = false)
        repeat(15) { i ->
            db.append(1, ChatMessage("далее", true), null); db.append(1, ChatMessage("Important answer $i", false), null)
            background(); assertEquals(0, paidRequests)
        }
        db.close(); db = ChatStore(app)
        db.append(1, ChatMessage("далее", true), null); db.append(1, ChatMessage("Important answer 15", false), null)
        background(); assertEquals(1, paidRequests); assertEquals(16, embedded)
        db.append(1, ChatMessage("Version 42", true), null); background(); assertEquals(1, paidRequests)
        SemanticArchive(db).index(1, db.load().single().messages, "space", { texts -> paidRequests++; assertEquals(listOf("Version 42"), texts); texts.map { floatArrayOf(1f) } })
        assertEquals(2, paidRequests); assertEquals(33, db.load().single().messages.size)
        assertTrue(ContextStore(db).search(1, "Version 42").contains("Version 42"))
    }
}
