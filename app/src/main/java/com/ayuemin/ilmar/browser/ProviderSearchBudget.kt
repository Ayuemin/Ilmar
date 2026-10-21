package com.ayuemin.ilmar.browser

/** Optional provider search must not be attached to every browser reasoning round. No network retries. */
internal class ProviderSearchBudget(
    private val route: SearchRoute,
    private val protocol: ProviderSearchProtocol?,
    private val browser: Boolean,
    private val limit: Int = com.ayuemin.ilmar.settings.RuntimeLimits.get("browser_provider_search_requests")
) {
    private var used = 0
    fun next(purpose: String): String? {
        if (purpose != "answer" || route != SearchRoute.PROVIDER || protocol == null) return null
        // Native search is intrinsic to that model/API; omitting an addon cannot disable it.
        if (browser && protocol != ProviderSearchProtocol.NATIVE && used >= limit) return null
        used++
        return protocol.name
    }
}
