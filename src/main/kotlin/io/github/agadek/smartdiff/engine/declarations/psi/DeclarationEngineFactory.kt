package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.openapi.project.Project
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.registry.ComparisonEngineFactory

/** Registers the declaration engine on the `comparisonEngine` extension point (technical specification §3.2). */
class DeclarationEngineFactory : ComparisonEngineFactory {
    override fun create(project: Project?): ComparisonEngine = DeclarationEngine(project)
}
