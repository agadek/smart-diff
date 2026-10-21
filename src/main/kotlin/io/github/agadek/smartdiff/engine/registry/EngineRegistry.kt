package io.github.agadek.smartdiff.engine.registry

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.EngineId

/**
 * The comparison engines of one project, from the `comparisonEngine` extension point (technical specification §3.2, §4).
 *
 * Engines are created on first use and recreated when an engine plugin is installed or removed. For a diff that
 * belongs to no project, use the default project's registry.
 */
@Service(Service.Level.PROJECT)
class EngineRegistry(private val project: Project) {

    /** The engines, with the extension list they were created from; the platform replaces that list when it changes. */
    @Volatile
    private var cached: Pair<List<ComparisonEngineFactory>, List<ComparisonEngine>>? = null

    /** All registered engines in extension order. A later engine with an id already taken is skipped. */
    fun engines(): List<ComparisonEngine> {
        val factories = ComparisonEngineFactory.EP_NAME.extensionList
        cached?.let { (from, engines) -> if (from === factories) return engines }
        return createEngines(factories).also { cached = factories to it }
    }

    fun findEngine(id: EngineId): ComparisonEngine? = engines().firstOrNull { it.id == id }

    /** Candidates and the selected engine for [languageId], honoring the user's stored default. */
    fun select(languageId: String): EngineSelection =
        EngineSelection.of(engines(), languageId, EngineDefaults.getInstance().get(languageId))

    /** Stores [engineId] as the default for [languageId] only, as choosing it from the dropdown does. */
    fun choose(languageId: String, engineId: EngineId) {
        EngineDefaults.getInstance().set(languageId, engineId)
    }

    private fun createEngines(factories: List<ComparisonEngineFactory>): List<ComparisonEngine> {
        val engines = mutableListOf<ComparisonEngine>()
        for (factory in factories) {
            val engine = try {
                factory.create(project.takeUnless { it.isDefault })
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                thisLogger().error("Cannot create comparison engine from ${factory.javaClass.name}", e)
                continue
            }
            if (engines.any { it.id == engine.id }) {
                thisLogger().warn("Comparison engine id ${engine.id} is registered twice; ignoring ${factory.javaClass.name}")
                continue
            }
            engines += engine
        }
        return engines
    }

    companion object {
        fun getInstance(project: Project): EngineRegistry = project.service()
    }
}
