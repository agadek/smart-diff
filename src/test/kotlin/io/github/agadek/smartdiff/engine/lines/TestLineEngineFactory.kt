package io.github.agadek.smartdiff.engine.lines

import com.intellij.openapi.project.Project
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.registry.ComparisonEngineFactory

class TestLineEngineFactory : ComparisonEngineFactory {
    override fun create(project: Project?): ComparisonEngine = TestLineEngine()
}
