package com.ayuemin.ilmar.model

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CatalogState(val revision: Int = 0, val loading: Set<String> = emptySet(), val errors: Map<String, String> = emptyMap())

/** One request per provider; cache and favorites persist independently of the UI. */
class ModelCatalogViewModel(application: Application) : AndroidViewModel(application) {
    val registry = ProviderRegistry(application)
    private val client = ModelCatalogClient()
    private val mutable = MutableStateFlow(CatalogState())
    val state = mutable.asStateFlow()
    private val attempted = mutableSetOf<String>()

    fun load(providerId: String, force: Boolean = false) {
        if (providerId in mutable.value.loading || (!force && (registry.catalogLoaded(providerId) || !attempted.add(providerId)))) return
        attempted.add(providerId)
        mutable.update { it.copy(loading = it.loading + providerId, errors = it.errors - providerId) }
        viewModelScope.launch {
            try {
                val (provider, key) = registry.catalogTarget(providerId)
                val names = client.loadDetailed(provider.endpoint, key)
                registry.applyCatalogDetails(providerId, provider.endpoint, names)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = when (e) {
                    is ProviderFailure -> e.message.orEmpty()
                    is IllegalStateException -> e.message ?: "Проверь настройки подключения"
                    is IllegalArgumentException -> "Каталог требует стандартный базовый адрес API"
                    else -> "Не удалось обновить список моделей. Сохранённый список доступен."
                }
                mutable.update { it.copy(errors = it.errors + (providerId to message)) }
            } finally {
                mutable.update { it.copy(loading = it.loading - providerId, revision = it.revision + 1) }
            }
        }
    }
    fun favorite(modelId: String) { registry.toggleFavorite(modelId); changed() }
    fun changed() { mutable.update { it.copy(revision = it.revision + 1) } }
}
