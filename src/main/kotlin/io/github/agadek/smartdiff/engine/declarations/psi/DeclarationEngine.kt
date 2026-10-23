package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.lang.Language
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
 * The provider first chooses the dialect from both texts; then each side is parsed in its own cancellable read action.
 * Matching and classification run outside any read action and hold no PSI.
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
            val (old, new) = trees(provider, request, cancellation)
            DeclarationAnalysis(id, version).compare(old, new, request.limits.maxDeclarations, cancellation)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            ComparisonResult.failed(this, "declaration engine failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** The declaration trees of both sides of [request], for the corpus validation's diagnostics. */
    internal fun trees(request: ComparisonRequest, cancellation: Cancellation): Pair<DeclarationTree, DeclarationTree>? =
        provider(request.languageId)?.let { trees(it, request, cancellation) }

    private fun trees(provider: DeclarationProvider, request: ComparisonRequest, cancellation: Cancellation): Pair<DeclarationTree, DeclarationTree> {
        val dialect = cancellableReadAction(cancellation) {
            provider.dialect(project(), request.oldText, request.newText, request.fileName)
        }
        return build(provider, request.oldText, request.fileName, dialect, cancellation) to
            build(provider, request.newText, request.fileName, dialect, cancellation)
    }

    private fun build(
        provider: DeclarationProvider,
        text: CharSequence,
        fileName: String?,
        dialect: Language?,
        cancellation: Cancellation,
    ): DeclarationTree =
        cancellableReadAction(cancellation) {
            provider.build(provider.parse(project(), text, fileName, dialect), cancellation)
        }

    private fun project(): Project = project ?: ProjectManager.getInstance().defaultProject

    private fun provider(languageId: String): DeclarationProvider? =
        DeclarationProvider.EP_NAME.extensionList.firstOrNull { it.supports(languageId) }

    companion object {
        val ID = EngineId("smartdiff.declarations")

        /** Raise when a change to a provider, matching or classification can change results. */
        const val VERSION = "6"
    }
}
