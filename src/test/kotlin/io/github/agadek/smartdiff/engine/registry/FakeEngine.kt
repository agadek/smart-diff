package io.github.agadek.smartdiff.engine.registry

import com.intellij.openapi.project.Project
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Status

/** An engine that supports [languages] and reports nothing. */
class FakeEngine(id: String, private vararg val languages: String) : ComparisonEngine {
    override val id = EngineId(id)
    override val displayName = id
    override val version = "1"
    override fun supports(languageId: String) = languageId in languages
    override fun compare(request: ComparisonRequest, cancellation: Cancellation) = ComparisonResult(id, version, Status.ANALYZED)
}

class FakeEngineFactory(private val engine: (Project?) -> ComparisonEngine) : ComparisonEngineFactory {
    val projects = mutableListOf<Project?>()
    override fun create(project: Project?): ComparisonEngine = engine(project).also { projects += project }
}
