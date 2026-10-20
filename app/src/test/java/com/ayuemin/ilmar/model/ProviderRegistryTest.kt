package com.ayuemin.ilmar.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.security.SecretStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class MemorySecrets : SecretStore {
    val values = mutableMapOf<String, String>()
    override fun saveApiKey(value: String, providerId: String) { values[providerId] = value }
    override fun loadApiKey(providerId: String) = values[providerId]
    override fun hasApiKey(providerId: String) = providerId in values
    override fun clearApiKey(providerId: String) { values.remove(providerId) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProviderRegistryTest {
    private lateinit var context: Context
    private lateinit var secrets: MemorySecrets
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        listOf("ilmar_ui", "ilmar_models", "ilmar_chat_modes").forEach {
            context.getSharedPreferences(it, 0).edit().clear().commit()
        }
        secrets = MemorySecrets()
    }
    @Test fun migratesLegacyMetadataAndKeepsOriginalKeyAndChatChoice() {
        context.getSharedPreferences("ilmar_ui", 0).edit().putString("api_endpoint", "https://example.com/v1/chat/completions")
            .putString("api_model", "existing").commit()
        context.getSharedPreferences("ilmar_chat_modes", 0).edit().putString("42_api_model", "existing")
            .putString("42_api_endpoint", "https://example.com/v1/chat/completions").commit()
        secrets.saveApiKey("old-key")
        val registry = ProviderRegistry(context, secrets)
        assertEquals("old-key", registry.target(42).apiKey)
        assertEquals("legacy-model", registry.selectedModelId(42))
        assertEquals(registry.snapshot(), ProviderRegistry(context, secrets).snapshot())
    }
    @Test fun keyOnlyLegacySettingsRetainOldDefaults() {
        secrets.saveApiKey("old-key")
        val target = ProviderRegistry(context, secrets).target(1)
        assertEquals("gpt-4o-mini", target.model.name)
        assertEquals("https://api.openai.com/v1/chat/completions", target.connection.endpoint)
    }
    @Test fun unknownLegacyOverrideDoesNotSilentlyUseDefault() {
        context.getSharedPreferences("ilmar_chat_modes", 0).edit().putString("1_api_model", "unknown").commit()
        val registry = ProviderRegistry(context, secrets)
        registry.saveConnection(null, "A", "https://a.example/v1/chat/completions", listOf("model"), "key")
        assertNull(registry.modelForChat(1))
    }
    @Test fun providerKeysAndChatSelectionsStayIndependentAcrossRecreation() {
        val registry = ProviderRegistry(context, secrets)
        registry.saveConnection(null, "A", "https://a.example/v1/chat/completions", listOf("same"), "key-a")
        registry.saveConnection(null, "B", "https://b.example/v1/chat/completions", listOf("same"), "key-b")
        val models = registry.snapshot().models
        registry.select(1, models[0].id); registry.select(2, models[1].id)
        val recreated = ProviderRegistry(context, secrets)
        assertEquals("key-a", recreated.target(1).apiKey)
        assertEquals("key-b", recreated.target(2).apiKey)
        registry.clearKey(models[1].providerId)
        assertEquals(models[1].id, registry.modelForChat(2)?.id)
        assertThrows(IllegalStateException::class.java) { registry.target(2) }
        assertEquals("key-a", registry.target(1).apiKey)
    }
    @Test fun editKeepsModelIdsAndDoesNotCopySecretsIntoMetadata() {
        val registry = ProviderRegistry(context, secrets)
        registry.saveConnection(null, "A", "https://a.example/v1/chat/completions", listOf("x", "y"), "private-key")
        val before = registry.snapshot()
        registry.saveConnection(before.providers.single().id, "Renamed", "https://new.example/v1/chat/completions", listOf("x", "y", "z"), "")
        assertEquals(before.models, registry.snapshot().models.take(2))
        assertFalse(ProviderRegistry.encode(registry.snapshot()).contains("private-key"))
        assertEquals("private-key", registry.target(1).apiKey)
    }
    @Test fun rejectsUnsafeEndpointsAndRoundTripsSnapshot() {
        listOf("http://example.com", "https://user:pass@example.com", "https://example.com/#fragment", "https:///path", "https://example.com:99999").forEach {
            assertFalse(it, ProviderRegistry.validEndpoint(it))
        }
        assertTrue(ProviderRegistry.validEndpoint("https://example.com/v1/chat/completions"))
        val data = RegistrySnapshot(listOf(ProviderConnection("p", "Name", "https://example.com")), listOf(RegisteredModel("m", "p", "x")), "m")
        assertEquals(data, ProviderRegistry.decode(ProviderRegistry.encode(data)))
    }
    @Test fun providerCanBeCreatedWithoutChoosingAModel() {
        val registry = ProviderRegistry(context, secrets)
        registry.saveProvider(null, "OpenRouter", "https://openrouter.ai/api/v1", "key")
        assertEquals(1, registry.snapshot().providers.size)
        assertTrue(registry.snapshot().models.isEmpty())
        assertNull(registry.snapshot().defaultModelId)
    }
    @Test fun refreshingCatalogPreservesSelectionFavoritesAndProviderIsolation() {
        val registry = ProviderRegistry(context, secrets)
        registry.saveProvider(null, "A", "https://a.example/v1", "key-a")
        registry.saveProvider(null, "B", "https://b.example/v1", "key-b")
        val a = registry.snapshot().providers.first { it.name == "A" }
        val b = registry.snapshot().providers.first { it.name == "B" }
        registry.applyCatalog(a.id, a.endpoint, listOf("vendor/model"))
        registry.applyCatalog(b.id, b.endpoint, listOf("vendor/model"))
        val model = registry.snapshot().models.first { it.providerId == a.id }
        val other = registry.snapshot().models.first { it.providerId == b.id }
        registry.select(1, model.id); registry.toggleFavorite(model.id)
        registry.applyCatalog(a.id, a.endpoint, listOf("vendor/model", "new-model"))
        val recreated = ProviderRegistry(context, secrets)
        assertEquals(model.id, recreated.modelForChat(1)?.id)
        assertTrue(recreated.isFavorite(model.id))
        assertFalse(recreated.isFavorite(other.id))
        assertEquals("key-a", recreated.target(1).apiKey)
        recreated.applyCatalog(a.id, a.endpoint, emptyList())
        assertTrue(recreated.isFavorite(model.id))
        assertEquals(model.id, recreated.modelForChat(1)?.id)
        assertThrows(IllegalStateException::class.java) { recreated.target(1) }
        assertTrue(recreated.isAvailable(other.id))
    }
    @Test fun staleCatalogCannotOverwriteEditedConnection() {
        val registry = ProviderRegistry(context, secrets)
        registry.saveProvider(null, "A", "https://a.example/v1", "key")
        val provider = registry.snapshot().providers.single()
        registry.saveProvider(provider.id, "A", "https://new.example/v1", "")
        assertThrows(IllegalStateException::class.java) { registry.applyCatalog(provider.id, provider.endpoint, listOf("stale")) }
        assertTrue(registry.snapshot().models.isEmpty())
    }

}
