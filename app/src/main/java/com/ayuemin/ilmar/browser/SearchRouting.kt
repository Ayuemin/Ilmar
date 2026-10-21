package com.ayuemin.ilmar.browser


enum class SearchSource(val title: String) { AUTO("Авто"), PROVIDER("Через поставщика"), BROWSER("Локальный браузер") }
internal enum class SearchRoute { OFF, PROVIDER, BROWSER }

/** Resolve before any request. Errors never trigger a second paid provider request. */
internal object SearchRouting {
    fun source(value: String) = SearchSource.entries.firstOrNull { it.name.lowercase() == value } ?: SearchSource.AUTO
    fun resolve(enabled: Boolean, source: SearchSource, providerAvailable: Boolean, tools: Boolean, explicitBrowser: Boolean = false): SearchRoute {
        if (!enabled) return SearchRoute.OFF
        return when (source) {
            SearchSource.PROVIDER -> { require(providerAvailable) { "Встроенный поиск этого поставщика не настроен. Выбери протокол в настройках поиска, локальный браузер или авто." }; SearchRoute.PROVIDER }
            SearchSource.BROWSER -> { require(tools) { "Локальному браузеру нужна модель с поддержкой tools. Обнови каталог или выбери другую модель." }; SearchRoute.BROWSER }
            SearchSource.AUTO -> if (explicitBrowser && tools) SearchRoute.BROWSER else if (providerAvailable) SearchRoute.PROVIDER else {
                require(tools) { "Для этого поставщика выбери модель с tools для локального браузера." }; SearchRoute.BROWSER
            }
        }
    }
}
