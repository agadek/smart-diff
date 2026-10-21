package io.github.agadek.smartdiff.engine.registry

import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.EngineId

/** The engines that can compare one language, and the one to use (technical specification §4). */
class EngineSelection(
    /** Registered engines that support the language, in extension order. */
    val candidates: List<ComparisonEngine>,
    /** The engine to run, or null when no engine supports the language (status `UNSUPPORTED`, native diff only). */
    val selected: ComparisonEngine?,
) {
    /** The engine dropdown is shown only when there is a choice. */
    val offersChoice: Boolean get() = candidates.size >= 2

    companion object {
        /**
         * Selects [storedDefault] if it is a candidate, otherwise the first candidate. A stored default that is not
         * registered, for example because its plugin was removed, is ignored but left stored.
         */
        fun of(engines: List<ComparisonEngine>, languageId: String, storedDefault: EngineId?): EngineSelection {
            val candidates = engines.filter { it.supports(languageId) }
            val selected = candidates.firstOrNull { it.id == storedDefault } ?: candidates.firstOrNull()
            return EngineSelection(candidates, selected)
        }
    }
}
