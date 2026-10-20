package com.ayuemin.ilmar.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.chat.*
import com.ayuemin.ilmar.settings.RuntimeLimits
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SemanticArchiveTest {
    private lateinit var app: Context
    private lateinit var store: ChatStore
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext(); app.deleteDatabase("ilmar_chats.db")
        RuntimeLimits.install(app); RuntimeLimits.reset()
        store = ChatStore(app); store.create(1, "Archive"); store.create(2, "Private")
    }
    @After fun cleanup() { store.close() }
    @Test fun semanticSearchFindsNoWordOverlapAndIsScopedToChatModelAndArchivedPrefix() = runTest {
        val relevant = store.append(1, ChatMessage("Доставка занимает три рабочих дня", false), null)
        store.append(1, ChatMessage("Кошка спит на диване", false), null)
        store.append(2, ChatMessage("Private foreign history", false), null)
        val archive = SemanticArchive(store)
        for (chat in store.load()) archive.index(chat.id, chat.messages, "space-v1", { texts -> texts.map { if (it.contains("Доставка")) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f) } })
        assertEquals("", ContextStore(store).search(1, "Когда прибудет посылка"))
        val found = archive.search(1, "space-v1", floatArrayOf(1f, 0f))
        assertTrue(found.contains("Доставка")); assertTrue(found.contains("message:${relevant.id}")); assertFalse(found.contains("Private"))
        assertEquals("", archive.search(1, "other-model", floatArrayOf(1f, 0f)))
        assertEquals("", archive.search(1, "space-v1", floatArrayOf(1f, 0f), relevant.id - 1))
        assertEquals(2, archive.count(1, "space-v1")); assertEquals(2, store.load().first().messages.size)
        store.clearMessages(1); assertEquals(0, archive.count(1, "space-v1")); assertEquals(1, archive.count(2, "space-v1"))
        store.delete(2); assertEquals(0, archive.count(2, "space-v1"))
    }
    @Test fun cancelledIndexResumesWithoutBuyingPersistedBatchesAgain() = runTest {
        val values = RuntimeLimits.fields.associate { it.key to RuntimeLimits.get(it.key) }.toMutableMap()
        values["embedding_batch"] = 1; RuntimeLimits.save(values)
        val message = store.append(1, ChatMessage("a".repeat(4500), true), null)
        val archive = SemanticArchive(store); var calls = 0
        try { archive.index(1, listOf(message), "space", { calls++; if (calls == 2) throw CancellationException(); listOf(floatArrayOf(1f, 0f)) }) }
        catch (_: CancellationException) { }
        assertEquals(1, archive.count(1, "space"))
        var resumed = 0
        archive.index(1, listOf(message), "space", { resumed++; listOf(floatArrayOf(1f, 0f)) })
        assertEquals(2, resumed); assertEquals(3, archive.count(1, "space"))
        archive.index(1, listOf(message), "space", { error("Must reuse index") })
    }
    @Test fun deletedChatCannotBeResurrectedByInFlightEmbeddingBatch() = runTest {
        val message = store.append(1, ChatMessage("Some old facts", true), null)
        val archive = SemanticArchive(store)
        archive.index(1, listOf(message), "space", { store.delete(1); listOf(floatArrayOf(1f)) })
        assertEquals(0, archive.count(1, "space")); assertEquals(listOf(2L), store.load().map { it.id })
    }
    @Test fun embeddingProtocolUsesDedicatedEndpointAndRestoresResponseOrder() = runTest {
        val config = EmbeddingConfig(true, "p", "embedding-model")
        assertEquals("https://api.example/v1/embeddings", config.endpoint("https://api.example/v1/chat/completions"))
        assertEquals("https://api.example/api/v1/embeddings", config.endpoint("https://api.example/api/v1"))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            assertEquals("embedding-model", body.getString("model")); assertEquals("float", body.getString("encoding_format"))
            assertEquals("Bearer protected", request.header("Authorization")); assertEquals(2, body.getJSONArray("input").length())
            Response.Builder().request(request).code(200).message("OK").protocol(Protocol.HTTP_1_1).body(
                """{"data":[{"index":1,"embedding":[0,3]},{"index":0,"embedding":[2,0]}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val vectors = EmbeddingClient(client).embed(config.endpoint("https://api.example/v1"), "protected", config.model, listOf("one", "two"))
        assertArrayEquals(floatArrayOf(1f, 0f), vectors[0], .0001f); assertArrayEquals(floatArrayOf(0f, 1f), vectors[1], .0001f)
    }
    @Test fun providerModelAndEndpointChangesCreateDifferentIndexSpaces() {
        val one = EmbeddingConfig(true, "provider1", "embedding-model")
        assertNotEquals(one.space("https://one.example/v1"), one.copy(model = "different").space("https://one.example/v1"))
        assertNotEquals(one.space("https://one.example/v1"), one.space("https://two.example/v1"))
        assertNotEquals(one.space("https://one.example/v1"), one.copy(provider = "provider2").space("https://one.example/v1"))
    }
    @Test fun versionFiveUpgradeKeepsOriginalsAndCreatesDerivedIndex() {
        store.append(1, ChatMessage("Original survived", true), null); store.close()
        android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath("ilmar_chats.db").path, null, 0).use { db ->
            db.execSQL("DROP TRIGGER semantic_delete"); db.execSQL("DROP TRIGGER semantic_update")
            db.execSQL("DROP TABLE semantic_chunks"); db.execSQL("DROP TABLE semantic_sources"); db.version = 5
        }
        store = ChatStore(app)
        assertEquals("Original survived", store.load().first().messages.single().text)
        assertEquals(0, SemanticArchive(store).count(1, "space"))
    }
}
