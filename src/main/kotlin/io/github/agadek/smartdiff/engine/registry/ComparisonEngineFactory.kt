package io.github.agadek.smartdiff.engine.registry

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import io.github.agadek.smartdiff.engine.api.ComparisonEngine

/**
 * Implementation of the internal `io.github.agadek.smartdiff.comparisonEngine` extension point (technical
 * specification §3.2). Engines that need IntelliJ services get them here, so [ComparisonEngine] stays IntelliJ-free.
 */
interface ComparisonEngineFactory {
    /** Creates the engine for [project], or for no project in particular when it is null. */
    fun create(project: Project?): ComparisonEngine

    companion object {
        val EP_NAME: ExtensionPointName<ComparisonEngineFactory> =
            ExtensionPointName.create("io.github.agadek.smartdiff.comparisonEngine")
    }
}
