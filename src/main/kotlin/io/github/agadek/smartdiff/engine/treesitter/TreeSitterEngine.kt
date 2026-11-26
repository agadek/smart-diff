package io.github.agadek.smartdiff.engine.treesitter

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import io.github.agadek.smartdiff.engine.api.AnalysisNote
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.TreeSitterSetting
import io.github.agadek.smartdiff.engine.declarations.analysis.DeclarationAnalysis
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.registry.ComparisonEngineFactory
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * The Tree-sitter engine (engines/05): parses both sides in the helper of ADR 0016, builds declaration trees with the
 * language's table and compares them with the declaration engine's matching and classification (R21).
 */
class TreeSitterEngine internal constructor(
    private val helpers: TreeSitterHelpers,
    private val enabled: () -> Boolean,
    private val languages: List<TreeSitterLanguage> = LANGUAGES,
) : ComparisonEngine {
    override val id: EngineId = ID
    override val displayName: String = "Declarations (Tree-sitter)"
    override val version: String = VERSION

    private val names = ConcurrentHashMap<TreeSitterGrammar, TreeSitterNames>()

    /** A Tree-sitter language while the setting is on and the helper can run (R1, R23). */
    override fun supports(languageId: String): Boolean =
        helpers.disabled == null && enabled() && languages.any { it.id == languageId }

    override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult {
        val language = languages.firstOrNull { it.id == request.languageId }
            ?: return ComparisonResult.unsupported(this, "no Tree-sitter grammar for language ${request.languageId}")
        return try {
            val (oldTree, newTree) = trees(language, request, cancellation)
            DeclarationAnalysis(id, version)
                .compare(oldTree, newTree, request.limits.maxDeclarations, cancellation, request.confidence, request.languageId)
                .copy(analysisNote = AnalysisNote.TREE_SITTER)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TreeSitterHelperException) {
            // R24: a failing helper is one failed comparison, never an exception out of the engine.
            ComparisonResult.failed(this, e.message ?: "the Tree-sitter helper failed")
        } catch (e: Exception) {
            if (e.javaClass.name == "com.intellij.openapi.progress.ProcessCanceledException") throw e
            ComparisonResult.threw(this, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Both sides' declaration trees, as [compare] builds them, or null for a language without a grammar. The relationship
     * benchmark reads the old tree to name a relationship's old endpoint (R30); it throws what [compare] catches.
     */
    internal fun trees(request: ComparisonRequest, cancellation: Cancellation): Pair<DeclarationTree, DeclarationTree>? {
        val language = languages.firstOrNull { it.id == request.languageId } ?: return null
        return trees(language, request, cancellation)
    }

    private fun trees(
        language: TreeSitterLanguage,
        request: ComparisonRequest,
        cancellation: Cancellation,
    ): Pair<DeclarationTree, DeclarationTree> {
        // engines/06 R3: the other side of a three-way merge is read the same way, so the base is read alike in both.
        val texts = listOf(request.oldText, request.newText) + if (language.recovers) request.relatedTexts else emptyList()
        val parses = helpers.use(cancellation) { helper ->
            // One check whether or not the names are cached, so every comparison checks cancellation equally often.
            cancellation.checkCanceled()
            val grammarNames = names.getOrPut(language.grammar) { helper.names(language.grammar) }
            val trees = texts.map { helper.parse(language.grammar, it, cancellation) }
            val first = texts.indices.map { TreeSitterSyntax(trees[it], grammarNames, texts[it]) }
            // engines/06 R1, R2: a second parse only when a version has a parse error and the language has a recovery.
            if (trees.none(::hasParseError)) return@use listOf(first)
            val recoveries = texts.map(language::recover)
            if (recoveries.all { it == null }) return@use listOf(first)
            // R3: every version is parsed again; one without a pattern parses as it did.
            val recovered = texts.indices.map { i ->
                recoveries[i]?.let { TreeSitterSyntax(helper.parse(language.grammar, it.text, cancellation), grammarNames, texts[i], it) }
                    ?: first[i]
            }
            listOf(first, recovered)
        }
        val candidates = parses.map { syntaxes -> syntaxes.map { TreeSitterTreeBuilder.build(it, language, cancellation) } }
        // engines/06 R1, R3: the parse that leaves less source unanalyzed in all versions together, the first on a tie.
        val chosen = candidates.minBy { trees -> trees.sumOf(::unanalyzed) }
        return chosen[0] to chosen[1]
    }

    private fun hasParseError(tree: TreeSitterTree): Boolean = (0 until tree.size).any { tree.isError(it) || tree.isMissing(it) }

    /** The characters of [tree] in a declaration or file region with a parse error, without their children's. */
    private fun unanalyzed(tree: DeclarationTree): Int {
        val declarations = tree.all.filter { it.hasParseError }.sumOf { declaration ->
            declaration.range.length - declaration.children.sumOf { it.range.length }
        }
        val region = if (tree.fileRegion.hasParseError) tree.fileRegion.ranges.sumOf { it.length } else 0
        return declarations + region
    }

    companion object {
        val ID = EngineId("smartdiff.treesitter")

        /**
         * The engine version and the version of each grammar and the runtime (R29). Raise the leading number when the
         * tree builder or a language table changes; the build checks that the grammar versions match the pinned sources.
         */
        const val VERSION = "3 (tree-sitter v0.25.3; swift 0.7.4-with-generated-files; c-sharp v0.23.1; c v0.23.2; cpp v0.23.4)"

        internal val LANGUAGES: List<TreeSitterLanguage> = listOf(SwiftLanguage, CSharpLanguage, CLanguage, CppLanguage)

        /** The setting, read through the API interface the UI registers; on while no service is registered. */
        internal fun settingEnabled(): Boolean =
            ApplicationManager.getApplication()?.getService(TreeSitterSetting::class.java)?.isEnabled()
                ?: TreeSitterSetting.DEFAULT_ENABLED
    }
}

/** Registers the Tree-sitter engine on the `comparisonEngine` extension point (engines/05-tree-sitter-details §4). */
class TreeSitterEngineFactory : ComparisonEngineFactory {
    override fun create(project: Project?): ComparisonEngine =
        TreeSitterEngine(TreeSitterHelpers.INSTANCE, TreeSitterEngine::settingEnabled)
}
