package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.declarations.analysis.DeclarationAnalysis
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import java.util.concurrent.CancellationException

/**
 * The declaration engine (technical specification §6 to §8): a language's [DeclarationProvider] turns each side into a
 * declaration tree, then [DeclarationAnalysis] matches and classifies them.
 *
 * Each side is parsed in its own cancellable read action; matching and classification run outside any read action and
 * hold no PSI.
 */
class DeclarationEngine(private val project: Project?) : ComparisonEngine {
    override val id: EngineId = ID
    override val displayName: String = "Declarations"

    /** The engine's own version and the platform build whose parsers it uses, so results from another IDE differ. */
    override val version: String = "$VERSION (${ApplicationInfo.getInstance().build.asStringWithoutProductCode()})"

    override fun supports(languageId: String): Boolean = provider(languageId) != null

    override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult {
        val provider = provider(request.languageId)
            ?: return ComparisonResult.unsupported(this, "no declaration provider for language ${request.languageId}")
        return try {
            val old = build(provider, request.oldText, request.fileName, cancellation)
            val new = build(provider, request.newText, request.fileName, cancellation)
            DeclarationAnalysis(id, version).compare(old, new, request.limits.maxDeclarations, cancellation)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            ComparisonResult.failed(this, "declaration engine failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun build(provider: DeclarationProvider, text: CharSequence, fileName: String?, cancellation: Cancellation): DeclarationTree =
        cancellableReadAction(cancellation) {
            val file = provider.parse(project ?: ProjectManager.getInstance().defaultProject, text, fileName)
            provider.build(file, cancellation)
        }

    private fun provider(languageId: String): DeclarationProvider? =
        DeclarationProvider.EP_NAME.extensionList.firstOrNull { it.supports(languageId) }

    companion object {
        val ID = EngineId("smartdiff.declarations")

        /** Raise when a change to a provider, matching or classification can change results. */
        const val VERSION = "1"
    }
}
