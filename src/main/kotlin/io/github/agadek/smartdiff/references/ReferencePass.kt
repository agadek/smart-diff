package io.github.agadek.smartdiff.references

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Confidence
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.Evidence
import io.github.agadek.smartdiff.engine.api.EvidenceOrder
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.Relationship
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.RenameNameMapping
import io.github.agadek.smartdiff.engine.api.Status as ResultStatus
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationProvider
import io.github.agadek.smartdiff.engine.declarations.psi.cancellableReadAction
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

/**
 * Adds reference evidence to the high-confidence rename relationships of an engine result (relationships/04).
 *
 * The engine stays text-only (ADR 0002). This pass runs after it, on the pooled thread, in a cancellable read action:
 * it parses two light copies with the declaration provider's dialect, resolves each side's usages with the language's
 * [ReferenceResolver], pairs them inside the matched declaration that contains them, and adds an
 * [Evidence.Reference] for every usage whose only change is the rename.
 *
 * [run] is the outline path and applies R3: the diff's file must belong to the open project. [runForBenchmark] is the
 * benchmark path and needs no file. Both stop after [BUDGET_MILLIS] and return the engine's result when they run out.
 */
object ReferencePass {

    /** Raised when a resolver or the grouping rule changes. Separate from `DeclarationEngine.VERSION` (R2). */
    const val VERSION = "1"

    /** R17: the pass adds at most this much per file, beyond the engine's own limit. */
    const val BUDGET_MILLIS = 1_000L

    enum class Status { GROUPED, NOT_GROUPED, TIMED_OUT }

    /**
     * The engine's result, or a copy with reference evidence, and how the pass ended. [dumb] marks a pass skipped
     * because the project was dumb, which the outline shows as `usages not grouped` like a timeout.
     */
    data class Outcome(
        val result: ComparisonResult,
        val status: Status,
        val version: String = VERSION,
        val dumb: Boolean = false,
    ) {
        val notGrouped: Boolean get() = status == Status.TIMED_OUT || dumb
    }

    /** Whether a resolver handles [languageId]; the benchmark requires usage labels for those languages (R18). */
    fun supports(languageId: String): Boolean = ReferenceResolvers.all.any { it.supports(languageId) }

    /**
     * The cheap part of R3, safe on the event dispatch thread: the outline runs the pass only for an analyzed result
     * with a project file. Whether the file belongs to the project is [run]'s job, on the pooled thread, because the
     * first project file index read can make the workspace index data up to date and must not run on the EDT.
     */
    fun canRun(project: Project?, file: VirtualFile?, result: ComparisonResult): Boolean {
        if (project == null || file == null) return false
        return result.status == ResultStatus.ANALYZED || result.status == ResultStatus.PARTIAL
    }

    /**
     * Test seam: the project file index check [run] performs on the pooled thread. Null uses the project file index;
     * a test can observe the calling thread and its result. Production never sets it.
     */
    internal var inContentCheck: ((Project, VirtualFile) -> Boolean)? = null

    /** R3's project file index check. Only [run] calls it, on the pooled thread, never on the EDT. */
    private fun isInContent(project: Project, file: VirtualFile): Boolean = try {
        ApplicationManager.getApplication().runReadAction(
            Computable { inContentCheck?.invoke(project, file) ?: ProjectFileIndex.getInstance(project).isInContent(file) },
        )
    } catch (e: Exception) {
        false
    }

    /** The outline entry point: applies R3 and returns the engine's result when the file is not in the project. */
    fun run(
        project: Project?,
        file: VirtualFile?,
        request: ComparisonRequest,
        result: ComparisonResult,
        cancellation: Cancellation,
    ): Outcome {
        if (!canRun(project, file, result)) return Outcome(result, Status.NOT_GROUPED)
        val openProject = project ?: return Outcome(result, Status.NOT_GROUPED)
        val diffFile = file ?: return Outcome(result, Status.NOT_GROUPED)
        // The file index is read here, on the pooled thread: its first read can be slow and is forbidden on the EDT.
        if (DumbService.isDumb(openProject)) return Outcome(result, Status.NOT_GROUPED, dumb = true)
        if (!isInContent(openProject, diffFile)) return Outcome(result, Status.NOT_GROUPED)
        return runWith(openProject, request, result, cancellation, ReferenceResolvers.all, BUDGET_MILLIS)
    }

    /** The benchmark entry point: the same resolvers and light copies, with no project file (R3). */
    fun runForBenchmark(
        project: Project?,
        request: ComparisonRequest,
        result: ComparisonResult,
        cancellation: Cancellation,
    ): Outcome = runWith(
        // A test's default project cannot resolve Java PSI: prefer an open project, as the IDE always has one.
        project ?: ProjectManager.getInstance().openProjects.firstOrNull() ?: ProjectManager.getInstance().defaultProject,
        request,
        result,
        cancellation,
        ReferenceResolvers.all,
        BUDGET_MILLIS,
    )

    /** Test entry point with its own resolvers and budget; production uses [run] and [runForBenchmark]. */
    internal fun runWith(
        project: Project,
        request: ComparisonRequest,
        result: ComparisonResult,
        cancellation: Cancellation,
        resolvers: List<ReferenceResolver>,
        budgetMillis: Long,
    ): Outcome {
        if (result.status != ResultStatus.ANALYZED && result.status != ResultStatus.PARTIAL) return Outcome(result, Status.NOT_GROUPED)
        val resolver = resolvers.firstOrNull { it.supports(request.languageId) } ?: return Outcome(result, Status.NOT_GROUPED)
        val provider = DeclarationProvider.EP_NAME.extensionList.firstOrNull { it.supports(request.languageId) }
            ?: return Outcome(result, Status.NOT_GROUPED)
        if (DumbService.isDumb(project)) return Outcome(result, Status.NOT_GROUPED, dumb = true)

        val passCancellation = PassCancellation(cancellation, budgetMillis)
        return try {
            val grouped = cancellableReadAction(passCancellation) {
                group(project, request, result, provider, resolver, passCancellation)
            }
            Outcome(grouped, if (grouped === result) Status.NOT_GROUPED else Status.GROUPED)
        } catch (e: CancellationException) {
            if (passCancellation.timedOut) Outcome(result, Status.TIMED_OUT) else throw e
        } catch (e: Exception) {
            Logger.getInstance(ReferencePass::class.java).debug("reference pass failed; keeping the engine result", e)
            Outcome(result, Status.NOT_GROUPED)
        }
    }

    private class RenameTarget(
        val relationship: Relationship,
        val oldTarget: PsiElement,
        val newTarget: PsiElement,
        val newSubject: Subject.Declaration,
    )

    private fun group(
        project: Project,
        request: ComparisonRequest,
        result: ComparisonResult,
        provider: DeclarationProvider,
        resolver: ReferenceResolver,
        cancellation: Cancellation,
    ): ComparisonResult {
        val dialect = provider.dialect(project, listOf(request.oldText, request.newText) + request.relatedTexts, request.fileName)
        val oldFile = provider.parse(project, request.oldText, request.fileName, dialect)
        val newFile = provider.parse(project, request.newText, request.fileName, dialect)
        val oldPolicy = resolver.whitespacePolicy(oldFile)
        val newPolicy = resolver.whitespacePolicy(newFile)
        val nodes = matchedNodes(result)
        val references = IdentityHashMap<Relationship, List<Evidence.Reference>>()
        var grouped = false
        val targets = mutableListOf<RenameTarget>()

        for (relationship in result.relationships) {
            cancellation.checkCanceled()
            if (RelationshipKind.RENAME !in relationship.kinds || relationship.confidence != Confidence.HIGH) continue
            if (relationship.old.size != 1 || relationship.new.size != 1) continue
            val oldEndpoint = relationship.old.single()
            val newEndpoint = relationship.new.single()
            val oldSubject = oldEndpoint.subject as? Subject.Declaration ?: continue
            val newSubject = newEndpoint.subject as? Subject.Declaration ?: continue
            if (unanalysedEndpoint(nodes, oldEndpoint.range, oldSide = true)) continue
            if (unanalysedEndpoint(nodes, newEndpoint.range, oldSide = false)) continue
            val oldTarget = resolver.target(oldFile, oldEndpoint.range, oldSubject) ?: continue
            val newTarget = resolver.target(newFile, newEndpoint.range, newSubject) ?: continue
            targets += RenameTarget(relationship, oldTarget, newTarget, newSubject)
        }
        if (targets.isEmpty()) return result

        // One resolver walk per side for every rename target, then one grouping per rename. Without this cache each
        // rename would resolve every reference in the file again.
        val oldUsages = resolver.usages(oldFile, targets.mapTo(LinkedHashSet()) { it.oldTarget }, cancellation)
        val newUsages = resolver.usages(newFile, targets.mapTo(LinkedHashSet()) { it.newTarget }, cancellation)
        for (target in targets) {
            cancellation.checkCanceled()
            val found = pair(
                oldUsages[target.oldTarget].orEmpty(),
                newUsages[target.newTarget].orEmpty(),
                nodes, oldFile, newFile, resolver, oldPolicy, newPolicy, target.newSubject, cancellation,
            )
            if (found.isNotEmpty()) {
                references[target.relationship] = found
                grouped = true
            }
        }
        if (!grouped) return result
        return result.copy(
            relationships = result.relationships.map { relationship ->
                val found = references[relationship] ?: return@map relationship
                relationship.copy(evidence = (relationship.evidence + found).sortedWith(EvidenceOrder))
            },
        )
    }

    /**
     * Pairs old and new usages inside the same matched declaration by ordinal, then keeps a pair only when the smallest
     * expression around it changes only by the rename (R5 to R7).
     */
    private fun pair(
        oldUsages: List<TextRange>,
        newUsages: List<TextRange>,
        nodes: List<ChangeNode>,
        oldFile: PsiFile,
        newFile: PsiFile,
        resolver: ReferenceResolver,
        oldPolicy: WhitespacePolicy,
        newPolicy: WhitespacePolicy,
        symbol: Subject.Declaration,
        cancellation: Cancellation,
    ): List<Evidence.Reference> {
        val oldByNode = groupByNode(oldUsages, nodes, oldSide = true)
        val newByNode = groupByNode(newUsages, nodes, oldSide = false)
        val found = mutableListOf<Evidence.Reference>()
        for ((oldNode, sortedOld) in oldByNode) {
            val sortedNew = newByNode[oldNode] ?: continue
            for ((oldRange, newRange) in sortedOld.zip(sortedNew)) {
                cancellation.checkCanceled()
                val oldContext = resolver.context(oldFile, oldRange)
                val newContext = resolver.context(newFile, newRange)
                if (oldContext == null || newContext == null) continue
                val reference = Evidence.Reference(symbol, oldRange, newRange)
                val policy = if (oldPolicy == newPolicy) oldPolicy else newPolicy
                if (RenameNameMapping.isRenameOnly(
                        oldFile.viewProvider.contents,
                        newFile.viewProvider.contents,
                        listOf(reference),
                        oldContext,
                        newContext,
                        policy,
                    )
                ) {
                    found += reference
                }
                cancellation.checkCanceled()
            }
        }
        return found.sortedWith(compareBy({ it.oldRange.start }, { it.newRange.start }))
    }

    /** A parse-error or unanalyzed endpoint is never resolved, so it adds no reference evidence (R12). */
    private fun unanalysedEndpoint(nodes: List<ChangeNode>, range: TextRange, oldSide: Boolean): Boolean =
        nodes.any { node ->
            val nodeRange = if (oldSide) node.oldRange else node.newRange
            nodeRange == range && node.content == Content.UNANALYZED
        }

    private fun groupByNode(
        usages: List<TextRange>,
        nodes: List<ChangeNode>,
        oldSide: Boolean,
    ): Map<ChangeNode, List<TextRange>> {
        val grouped = LinkedHashMap<ChangeNode, MutableList<TextRange>>()
        for (usage in usages) {
            val node = nodes
                .filter { node ->
                    val range = if (oldSide) node.oldRange else node.newRange
                    range != null && usage.start >= range.start && usage.end <= range.end
                }
                .minByOrNull { node ->
                    val range = if (oldSide) node.oldRange!! else node.newRange!!
                    range.length
                } ?: continue
            // Find the innermost containing node first, then skip it when it is unanalyzed: an analyzed class must not
            // collect a usage that lies inside an unanalyzed method.
            if (node.content == Content.UNANALYZED) continue
            grouped.getOrPut(node, ::mutableListOf) += usage
        }
        return grouped.mapValues { (_, ranges) -> ranges.sortedBy { it.start } }
    }

    private fun matchedNodes(result: ComparisonResult): List<ChangeNode> {
        val nodes = mutableListOf<ChangeNode>()
        fun visit(node: ChangeNode) {
            if (node.presence == Presence.MATCHED &&
                node.subject is Subject.Declaration && node.oldRange != null && node.newRange != null
            ) {
                nodes += node
            }
            node.children.forEach(::visit)
        }
        result.fileRegion?.children?.forEach(::visit)
        result.changes.forEach(::visit)
        return nodes
    }

    /** The pass's own clock on top of the outline's cancellation; an external cancel still wins (R16, R17). */
    private class PassCancellation(private val external: Cancellation, budgetMillis: Long) : Cancellation {
        private val deadline = System.nanoTime() + budgetMillis * 1_000_000

        @Volatile
        var timedOut = false
            private set

        override fun checkCanceled() {
            external.checkCanceled()
            // A pending write action cancels the read action's platform indicator; let the helper retry the read, rather
            // than returning a partial grouping from the first pass.
            if (ProgressManager.getInstance().progressIndicator?.isCanceled == true) throw ProcessCanceledException()
            if (System.nanoTime() - deadline > 0) {
                timedOut = true
                throw ProcessCanceledException()
            }
        }
    }
}
