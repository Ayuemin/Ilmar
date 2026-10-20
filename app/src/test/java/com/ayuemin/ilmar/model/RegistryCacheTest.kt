package com.ayuemin.ilmar.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RegistryCacheTest {
    @Test fun largeAudioCatalogIsDecodedOnceAndInvalidatedWhenUpdatedByAnotherInstance() {
        val app: Context = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("ilmar_models", 0).edit().clear().commit()
        app.getSharedPreferences("ilmar_ui", 0).edit().clear().commit()
        val registry = ProviderRegistry(app, MemorySecrets())
        registry.saveProvider(null, "Router", "https://openrouter.ai/api/v1", "key")
        val provider = registry.snapshot().providers.single()
        registry.applyCatalogDetails(provider.id, provider.endpoint, (1..800).map { CatalogModel("m$it", ModelCapabilities(outputModalities = if (it % 10 == 0) setOf("audio") else setOf("text"))) })
        val metadata = registry.providerCapabilities(provider.id)
        val snapshot = registry.snapshot()
        repeat(100) { assertSame(snapshot, registry.snapshot()); assertSame(metadata, registry.providerCapabilities(provider.id)) }
        assertEquals(80, metadata.values.count { "audio" in it.outputModalities })
        val other = ProviderRegistry(app, MemorySecrets())
        other.applyCatalogDetails(provider.id, provider.endpoint, listOf(CatalogModel("new", ModelCapabilities(outputModalities = setOf("audio")))))
        assertNotSame(metadata, registry.providerCapabilities(provider.id)); assertNotSame(snapshot, registry.snapshot())
        assertEquals(1, registry.providerCapabilities(provider.id).size)
    }
}
