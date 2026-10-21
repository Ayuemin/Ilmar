package com.ayuemin.ilmar.documents

import android.content.Context

internal enum class DocumentMode { LEGACY, LOCAL, COMPARE }
internal enum class ExperimentalBrowser { EXISTING, DISABLED, LIMITED }
internal data class DocumentExperiment(val mode: DocumentMode = DocumentMode.LEGACY,
    val options: RetrievalOptions = RetrievalOptions(), val fragments: Int = 5,
    val browser: ExperimentalBrowser = ExperimentalBrowser.EXISTING) {
    init { require(fragments in setOf(3, 5, 10)) }
}
internal class DocumentExperimentPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("ilmar_document_experiment", Context.MODE_PRIVATE)
    fun load(): DocumentExperiment = runCatching {
        DocumentExperiment(DocumentMode.valueOf(prefs.getString("mode", "LEGACY")!!),
            RetrievalOptions(prefs.getBoolean("structure", true), prefs.getBoolean("heat", true),
                prefs.getBoolean("morphology", true), prefs.getBoolean("feedback", false), prefs.getBoolean("context", true)),
            prefs.getInt("fragments", 5), ExperimentalBrowser.valueOf(prefs.getString("browser", "EXISTING")!!))
    }.getOrDefault(DocumentExperiment())
    fun save(value: DocumentExperiment) { check(prefs.edit().putString("mode", value.mode.name).putInt("fragments", value.fragments)
        .putString("browser", value.browser.name).putBoolean("structure", value.options.structure).putBoolean("heat", value.options.heat)
        .putBoolean("morphology", value.options.morphology).putBoolean("feedback", value.options.feedback)
        .putBoolean("context", value.options.context).commit()) }
}
